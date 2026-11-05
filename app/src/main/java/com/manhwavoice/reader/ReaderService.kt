package com.manhwavoice.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Служба спецвозможностей: плавающая кнопка поверх браузера, снимки экрана,
 * распознавание, озвучка и прокрутка.
 */
class ReaderService : AccessibilityService() {

    private enum class State { IDLE, BUSY, SPEAKING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var wm: WindowManager
    private lateinit var ocr: OcrEngine
    private var button: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private var ttsEngine: String? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val memory = TextMemory()
    @Volatile private var lastFishErrorAt = 0L
    private var foregroundPkg: String? = null
    private var readingPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs.get(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ocr = OcrEngine(this)
        initTts()
        showButton()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (event.className?.toString()?.contains("InputMethod") == true) return
        // Панели скриншота, шторка, всплывающие окна — не настоящие приложения, их пропускаем
        if (!isRealApp(pkg)) return
        foregroundPkg = pkg
        // Ушли из браузера (домой, в другое приложение) — ставим на паузу, чтобы не листать чужое
        val rp = readingPkg
        if (loopJob?.isActive == true && rp != null && pkg != rp) {
            stopReading("Пауза: вы вышли из браузера")
        }
    }

    private val realAppCache = HashMap<String, Boolean>()

    /** Обычное приложение со значком или главный экран (а не панель скриншота и т.п.). */
    private fun isRealApp(pkg: String): Boolean = realAppCache.getOrPut(pkg) {
        if (packageManager.getLaunchIntentForPackage(pkg) != null) return@getOrPut true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).any { it.activityInfo?.packageName == pkg }
    }

    override fun onInterrupt() {
        stopReading(null)
    }

    override fun onDestroy() {
        instance = null
        stopReading(null)
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        tts?.shutdown()
        scope.cancel()
        if (::ocr.isInitialized) ocr.release()
        super.onDestroy()
    }

    // ---------------- Синтез речи ----------------

    private fun initTts() {
        val enginePkg = Voices.enginePref(prefs)
        ttsEngine = enginePkg
        appliedVoiceKey = null
        val ready = CompletableDeferred<Boolean>()
        ttsReady = ready
        tts = Voices.create(this, enginePkg) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                ready.complete(false)
                return@create
            }
            val r = engine.setLanguage(Voices.RU)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                ready.complete(false)
            } else {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { pending.remove(it)?.complete(true) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { pending.remove(it)?.complete(false) }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        utteranceId?.let { pending.remove(it)?.complete(false) }
                    }
                })
                ready.complete(true)
            }
        }
    }

    /** Если в настройках выбрали другой движок — перезапускаем синтез речи. */
    private fun ensureEngine() {
        if (Voices.enginePref(prefs) != ttsEngine) {
            tts?.shutdown()
            tts = null
            initTts()
        }
    }

    private var appliedVoiceKey: String? = null

    /** Применяет голос, высоту и скорость, только если они изменились. */
    private fun applyVoice(engine: TextToSpeech) {
        val key = "${prefs.getString(Prefs.KEY_VOICE, null)}|${Prefs.pitch(prefs)}|${Prefs.rate(prefs)}"
        if (key != appliedVoiceKey) {
            Voices.apply(engine, prefs)
            appliedVoiceKey = key
        }
    }

    /** Ставит фразу в очередь синтеза речи. Возвращает «обещание» её окончания. */
    private fun submit(text: String): CompletableDeferred<Boolean>? {
        val engine = tts ?: return null
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        applyVoice(engine)
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return null
        }
        return done
    }

    private suspend fun awaitSpeech(done: CompletableDeferred<Boolean>?): Boolean {
        if (done == null) return false
        return try {
            done.await()
        } catch (c: CancellationException) {
            tts?.stop()
            throw c
        }
    }

    /** Произносит фразу и ждёт, пока она договорится. */
    private suspend fun speak(text: String): Boolean = awaitSpeech(submit(text))

    // ---------------- Очередь речи ----------------

    /** Что произнести ([say] — вся реплика или её непрочитанное продолжение) и что запомнить. */
    private class Utterance(val say: String, val remember: String)

    /**
     * Общая очередь речи для всех экранов. Реплики звучат по порядку и без пауз,
     * а цикл чтения тем временем может уже листать и распознавать следующий экран.
     */
    private inner class Speaker(scope: CoroutineScope) {
        private val fish = FishTts.enabled(prefs)
        /** Сколько реплик может ждать в очереди сверх звучащей */
        private val ahead = if (fish) FISH_PREFETCH else 1
        private val ch = Channel<Utterance>(Channel.UNLIMITED)
        private var enqueued = 0
        private val done = MutableStateFlow(0)
        /** Поставлены в очередь, но ещё не договорены — при паузе их надо «забыть» */
        val unfinished = ArrayList<Utterance>()

        init {
            scope.launch { if (fish) runFish() else runSystem() }
        }

        val busy: Boolean get() = done.value < enqueued

        suspend fun say(u: Utterance) {
            // Не забегать далеко вперёд: следующая реплика ставится, когда звучит предыдущая
            done.first { enqueued - it <= ahead }
            if (u.remember.isNotBlank()) memory.add(u.remember)
            unfinished += u
            enqueued++
            ch.send(u)
        }

        /** Дождаться, когда звучит последняя реплика (все предыдущие договорены). */
        suspend fun awaitLastPlaying() {
            val target = enqueued - 1
            done.first { it >= target }
        }

        /** Дождаться, когда всё договорено. */
        suspend fun awaitIdle() {
            val target = enqueued
            done.first { it >= target }
        }

        fun close() {
            ch.close()
        }

        private fun finished(u: Utterance) {
            unfinished.remove(u)
            done.value = done.value + 1
            if (done.value >= enqueued) setState(State.BUSY)
        }

        /** Системный голос: следующая реплика уже в очереди синтезатора, пока звучит текущая. */
        private suspend fun runSystem() {
            val playing = ArrayDeque<Pair<Utterance, CompletableDeferred<Boolean>?>>()
            var closed = false
            while (true) {
                if (playing.isEmpty()) {
                    if (closed) break
                    val u = ch.receiveCatching().getOrNull() ?: break
                    setState(State.SPEAKING)
                    playing.addLast(u to submit(u.say))
                    continue
                }
                val (head, headDone) = playing.first()
                if (headDone == null) {
                    playing.removeFirst()
                    finished(head)
                    continue
                }
                select<Unit> {
                    headDone.onAwait {
                        playing.removeFirst()
                        finished(head)
                        Unit
                    }
                    if (!closed && playing.size < 2) {
                        ch.onReceiveCatching { r ->
                            val u = r.getOrNull()
                            if (u == null) {
                                closed = true
                            } else {
                                playing.addLast(u to submit(u.say))
                            }
                            Unit
                        }
                    }
                }
            }
        }

        /** Fish Audio: несколько следующих реплик скачиваются заранее. */
        private suspend fun runFish() = coroutineScope {
            val play = Channel<Pair<Utterance, Deferred<File?>>>(Channel.UNLIMITED)
            launch {
                for (u in ch) play.send(u to async(Dispatchers.IO) { fishSynth(u.say) })
                play.close()
            }
            for ((u, job) in play) {
                val file = job.await()
                setState(State.SPEAKING)
                if (file != null) {
                    try {
                        if (!FishTts.play(file)) speak(u.say)
                    } finally {
                        file.delete()
                    }
                } else {
                    speak(u.say)
                }
                finished(u)
            }
        }
    }

    private fun fishSynth(text: String): File? = try {
        FishTts.synthesize(this, prefs, text)
    } catch (e: Exception) {
        Log.w(TAG, "Fish Audio failed: ${e.message}")
        val now = SystemClock.uptimeMillis()
        if (now - lastFishErrorAt > 60_000) {
            lastFishErrorAt = now
            val msg = if (e is FishTts.FishError) e.message else "нет связи с Fish Audio"
            scope.launch { toast("Fish Audio: $msg. Читаю обычным голосом.") }
        }
        null
    }

    // ---------------- Распознавание экрана ----------------

    /**
     * count — сколько новых реплик (-1 — пустой экран), lastTop — где последняя (доля высоты),
     * found — сколько текста вообще нашлось, cutTop — где начинается текст, обрезанный снизу
     * (прочитается после прокрутки; доля высоты), regionTop/regionBottom — видимая часть страницы.
     */
    private class ScreenResult(
        val count: Int,
        val lastTop: Float?,
        val found: Int = 0,
        val cutTop: Float? = null,
        val regionTop: Float = 0f,
        val regionBottom: Float = 1f
    )

    /**
     * Распознаёт экран и ставит реплики в очередь речи — строго в порядке чтения. Надписи
     * распознают две быстрые модели параллельно (сомнительное перечитывает точная), а реплика
     * звучит, как только распознаны она и все надписи перед ней. Уже прочитанные на прошлых
     * экранах надписи узнаются по памяти без распознавания.
     */
    private suspend fun readScreen(
        speaker: Speaker,
        shot: Bitmap,
        excl: Rect?,
        statusBar: Int,
        skipTopCut: Boolean,
        skipBottomCut: Boolean,
        newScreen: Boolean = true
    ): ScreenResult {
        val screenH = shot.height.toFloat()
        val ins = insets
        val topPx = ((ins?.top ?: 0f) * screenH).toInt()
        val bottomPx = ((ins?.bottom ?: 0f) * screenH).toInt()
        val rtl = Prefs.rtl(prefs)
        val prep = try {
            withContext(Dispatchers.Default) {
                ocr.prepare(shot, excl, statusBar, topPx, bottomPx, skipTopCut, skipBottomCut, rtl)
            }
        } finally {
            shot.recycle()
        }
        if (newScreen) memory.nextScreen()
        val regionTop = prep.regionTop / prep.h.toFloat()
        val regionBottom = prep.regionBottom / prep.h.toFloat()
        if (prep.blank) return ScreenResult(-1, null, regionTop = regionTop, regionBottom = regionBottom)
        val items = prep.items
        var count = 0
        var found = 0
        var lastTop: Float? = null
        val local = TextMemory()
        suspend fun offer(p: Phrase) {
            found++
            if (local.seen(p.text)) return
            // Новая реплика целиком, или продолжение той, что раньше была обрезана краем
            val say = memory.unread(p.text) ?: return
            local.add(p.text)
            speaker.say(Utterance(say, p.text))
            count++
            lastTop = p.box.top / prep.h.toFloat()
        }
        val results = Array(items.size) { CompletableDeferred<Phrase?>() }
        coroutineScope {
            val next = AtomicInteger(0)
            for (k in ocr.fastDispatchers.indices) {
                launch(ocr.fastDispatchers[k]) {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= items.size) break
                        val item = items[i]
                        try {
                            val sig = ocr.cache.signature(prep, item)
                            val hit = ocr.cache.find(item, sig)
                            if (hit != null) {
                                results[i].complete(hit.phrase)
                                continue
                            }
                            val r = ocr.firstPass(prep, item, k)
                            if (r.needsSecondPass) {
                                launch(ocr.bestDispatcher) {
                                    val ph = try {
                                        ScreenText.finalPhrase(r, ocr.secondPass(r))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "second pass failed", e)
                                        null
                                    }
                                    ocr.cache.put(sig, ph)
                                    results[i].complete(ph)
                                }
                            } else {
                                val ph = ScreenText.finalPhrase(r, r.checked.phrase)
                                ocr.cache.put(sig, ph)
                                results[i].complete(ph)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            Log.e(TAG, "item OCR failed", e)
                            results[i].complete(null)
                        }
                    }
                }
            }
            // Реплики — по порядку чтения
            for (i in items.indices) {
                val ph = results[i].await() ?: continue
                offer(ph)
            }
        }
        val cut = prep.cutTop?.let { it / prep.h.toFloat() }
        return ScreenResult(count, lastTop, found, cut, regionTop, regionBottom)
    }

    // ---------------- Главный цикл ----------------

    private var activeSpeaker: Speaker? = null

    /** Неподвижные панели сайта сверху и снизу (узнаются после прокрутки; помнятся для приложения). */
    private var insets: Overlays.Insets? = null
    private var prevSmall: Overlays.Small? = null

    private fun toggle() {
        if (loopJob?.isActive == true) stopReading(null) else startReading()
    }

    private fun startReading() {
        readingPkg = foregroundPkg
        insets = Prefs.insets(prefs, readingPkg)
        prevSmall = null
        cacheDir.listFiles()?.filter { it.name.startsWith("fish_") }?.forEach { it.delete() }
        loopJob = scope.launch {
            try {
                runLoop()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e(TAG, "loop failed", e)
                toast("Ошибка: ${e.message}")
            } finally {
                setState(State.IDLE)
            }
        }
    }

    private fun stopReading(message: String?) {
        // Реплики, которые не успели прозвучать, — забыть, чтобы после паузы прочитать их снова
        activeSpeaker?.unfinished?.toList()?.forEach { memory.remove(it.remember) }
        activeSpeaker = null
        loopJob?.cancel()
        loopJob = null
        tts?.stop()
        setState(State.IDLE)
        message?.let { toast(it) }
    }

    /** После прокрутки: какие панели сайта не сдвинулись вместе со страницей. */
    private fun updateInsets(small: Overlays.Small, screenH: Int, statusBar: Int, scrolled: Boolean) {
        val prev = prevSmall
        prevSmall = small
        if (!scrolled || prev == null) return
        val found = Overlays.detect(prev, small, statusBar / screenH.toFloat() + 0.005f) ?: return
        val old = insets
        if (old == null || abs(old.top - found.top) > 0.004f || abs(old.bottom - found.bottom) > 0.004f) {
            insets = found
            Prefs.saveInsets(prefs, readingPkg, found)
        }
    }

    private suspend fun runLoop() = coroutineScope {
        setState(State.BUSY)
        if (!ocr.ensureReady()) {
            toast("Не удалось загрузить модель распознавания текста")
            return@coroutineScope
        }
        ensureEngine()
        if (!ttsReady.await()) {
            toast("Нет русского голоса. Откройте приложение → раздел «Голос»")
            return@coroutineScope
        }
        val speaker = Speaker(this)
        activeSpeaker = speaker
        val statusBar = statusBarHeight()
        var prevFp: IntArray? = null
        var noMove = 0
        var uniformRun = 0
        var firstScreen = true
        var scrolled = false
        try {
            while (isActive) {
                if (!speaker.busy) setState(State.BUSY)
                val (shot, excl) = takeCleanShot()
                if (shot == null) {
                    delay(300)
                    continue
                }
                val fp = Fingerprint.of(shot, excl)
                val uniform = Fingerprint.isUniform(fp)
                uniformRun = if (uniform) uniformRun + 1 else 0

                val stuck = prevFp != null && !uniform && Fingerprint.same(prevFp, fp)
                if (!stuck) updateInsets(smallOf(shot), shot.height, statusBar, scrolled)
                scrolled = false
                if (stuck || uniformRun >= 15) {
                    noMove++
                    if (noMove == 1 && stuck) {
                        // Возможно, страница ещё подгружается — пробуем ещё раз
                        shot.recycle()
                        speaker.awaitIdle()
                        delay(1000)
                        scroll(null)
                        delay(SETTLE_MS)
                        continue
                    }
                    // Дальше листать некуда — дочитываем низ экрана и останавливаемся
                    readScreen(speaker, shot, excl, statusBar, skipTopCut = !firstScreen, skipBottomCut = false, newScreen = false)
                    speaker.say(Utterance("Конец главы.", ""))
                    speaker.awaitIdle()
                    break
                }
                noMove = 0
                prevFp = fp

                val res = readScreen(speaker, shot, excl, statusBar, skipTopCut = true, skipBottomCut = true)
                firstScreen = false

                // На сколько листать: не больше настройки и видимой части страницы; если внизу
                // есть обрезанная надпись — так, чтобы после прокрутки она оказалась целиком на экране
                val region = res.regionBottom - res.regionTop
                var frac = min(Prefs.scrollFraction(prefs), region - 0.06f)
                res.cutTop?.let { frac = min(frac, it - res.regionTop - 0.02f) }
                frac = frac.coerceAtLeast(0.2f)
                val lastTop = res.lastTop
                when {
                    // Последняя реплика экрана зазвучала, и её надпись после прокрутки останется
                    // на экране — листаем и распознаём следующий экран, пока она звучит
                    res.count > 0 && Prefs.earlyScroll(prefs) && lastTop != null &&
                        lastTop - frac - res.regionTop >= EARLY_MARGIN -> speaker.awaitLastPlaying()
                    res.count > 0 -> {
                        speaker.awaitIdle()
                        delay(Prefs.pauseAfterSpeech(prefs))
                    }
                    else -> {
                        // Не уводить с экрана надпись, которая ещё читается
                        if (speaker.busy) speaker.awaitIdle()
                        delay(
                            when {
                                res.count < 0 -> BLANK_PAUSE_MS                        // пустой промежуток
                                res.found > 0 -> Prefs.pauseAfterSpeech(prefs)         // только уже прочитанное
                                else -> Prefs.pauseNoText(prefs)                       // кадр без текста
                            }
                        )
                    }
                }
                scroll(frac, res.regionTop, res.regionBottom)
                scrolled = true
                delay(SETTLE_MS)
            }
        } finally {
            speaker.close()
            if (activeSpeaker === speaker) activeSpeaker = null
        }
    }

    /** Уменьшенная копия снимка для поиска неподвижных панелей. */
    private fun smallOf(shot: Bitmap): Overlays.Small {
        val sw = max(1, shot.width / 4)
        val sh = max(1, shot.height / 4)
        val s = Bitmap.createScaledBitmap(shot, sw, sh, true)
        val px = IntArray(sw * sh)
        s.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (s != shot) s.recycle()
        return Overlays.small(px, sw, sh)
    }

    // ---------------- Снимок экрана ----------------

    /**
     * Снимок без нашей кнопки: она прячется на долю секунды, чтобы не закрывать текст под собой.
     * Если кнопка всё же попала на снимок — возвращается её место, его закрасят.
     */
    private suspend fun takeCleanShot(): Pair<Bitmap?, Rect?> {
        val b = button
        val rect = buttonRect()
        var hidden = false
        if (b != null && b.visibility == View.VISIBLE) {
            b.visibility = View.INVISIBLE
            hidden = true
            delay(HIDE_MS)
        }
        val shot = try {
            takeShot()
        } finally {
            if (hidden) b?.visibility = View.VISIBLE
        }
        if (shot == null) return null to null
        return shot to (if (rect != null && buttonVisibleIn(shot, rect)) rect else null)
    }

    /** На снимке видна кнопка (её фиолетово-розовый цвет в середине)? */
    private fun buttonVisibleIn(shot: Bitmap, r: Rect): Boolean {
        val cx = r.centerX()
        val cy = r.centerY()
        if (cx !in 0 until shot.width || cy !in 0 until shot.height) return false
        var hits = 0
        var all = 0
        val d = r.width() / 5
        for (dy in -1..1) for (dx in -1..1) {
            val x = (cx + dx * d).coerceIn(0, shot.width - 1)
            val y = (cy + dy * d).coerceIn(0, shot.height - 1)
            val p = shot.getPixel(x, y)
            val red = Color.red(p); val green = Color.green(p); val blue = Color.blue(p)
            if (red > 100 && blue > 90 && green < 80 && red - green > 60) hits++
            all++
        }
        return hits * 2 > all
    }

    private suspend fun takeShot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hb = result.hardwareBuffer
                var bmp: Bitmap? = null
                try {
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    if (hw != null) {
                        bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "screenshot convert failed", e)
                } finally {
                    hb.close()
                }
                if (cont.isActive) cont.resume(bmp) else bmp?.recycle()
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed: $errorCode")
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    // ---------------- Прокрутка ----------------

    /**
     * Листает на [frac] высоты экрана. Палец ведётся внутри видимой части страницы
     * ([top]…[bottom] — доли высоты), чтобы не попасть на панели сайта.
     */
    private suspend fun scroll(frac: Float? = null, top: Float = 0f, bottom: Float = 1f) {
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val dist = h * (frac ?: Prefs.scrollFraction(prefs))
        var startY = h * 0.5f + dist / 2f
        startY = min(startY, h * bottom - h * 0.03f)
        val endY = max(h * 0.02f, startY - dist)
        var x = w * 0.5f
        val excl = buttonRect()
        if (excl != null && x >= excl.left - 30 && x <= excl.right + 30) {
            x = if (excl.centerX() > w / 2) w * 0.25f else w * 0.75f
        }
        // 1) ведём палец вверх...
        val move = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, startY); lineTo(x, endY) }, 0, 300, true
        )
        val ok = dispatch(GestureDescription.Builder().addStroke(move).build())
        // 2) ...и на мгновение задерживаем его на месте перед отпусканием, чтобы страница
        //    не «улетела» по инерции и прокрутка была предсказуемой
        if (ok) {
            val hold = move.continueStroke(Path().apply { moveTo(x, endY) }, 0, 90, false)
            dispatch(GestureDescription.Builder().addStroke(hold).build())
        }
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val accepted = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    // ---------------- Плавающая кнопка ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        val dp = resources.displayMetrics.density
        val size = (58 * dp).toInt()
        val screenH = wm.currentWindowMetrics.bounds.height()
        val tv = TextView(this).apply {
            text = ICON_PLAY
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, 0x66000000)
            // Градиент в цветах приложения: фиолетовый → розовый
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF27C3AED.toInt(), 0xF2DB2777.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke((2 * dp).toInt(), 0x66FFFFFF)
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.KEY_BTN_X, (12 * dp).toInt())
            y = prefs.getInt(Prefs.KEY_BTN_Y, screenH / 3)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    downTime = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt(Prefs.KEY_BTN_X, lp.x).putInt(Prefs.KEY_BTN_Y, lp.y).apply()
                    } else if (SystemClock.uptimeMillis() - downTime > 700) {
                        // Долгое нажатие — забыть прочитанное, текущий экран прочитается заново
                        memory.clear()
                        ocr.cache.clear()
                        toast("Память очищена — экран прочитается заново")
                    } else {
                        toggle()
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
        params = lp
    }

    private fun buttonRect(): Rect? {
        val b = button ?: return null
        if (b.width == 0) return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return Rect(loc[0] - 10, loc[1] - 10, loc[0] + b.width + 10, loc[1] + b.height + 10)
    }

    private var shownState: State? = null
    private var stateAnim: ValueAnimator? = null

    /** Значок и анимация кнопки: ▶ ожидание, ⋯ мигает — распознаёт, ❚❚ пульсирует — читает. */
    private fun setState(s: State) {
        val b = button ?: return
        if (s == shownState) return
        shownState = s
        b.text = when (s) {
            State.IDLE -> ICON_PLAY
            State.BUSY -> ICON_BUSY
            State.SPEAKING -> ICON_PAUSE
        }
        stateAnim?.cancel()
        stateAnim = null
        b.scaleX = 1f
        b.scaleY = 1f
        b.alpha = 1f
        stateAnim = when (s) {
            State.SPEAKING -> ObjectAnimator.ofPropertyValuesHolder(
                b,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.88f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.88f)
            ).apply { duration = 700 }
            State.BUSY -> ObjectAnimator.ofFloat(b, View.ALPHA, 1f, 0.55f).apply { duration = 500 }
            State.IDLE -> null
        }?.apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ManhwaReader"
        private const val SETTLE_MS = 220L
        private const val EARLY_MARGIN = 0.06f
        /** Сколько ждать, пока кнопка спрячется перед снимком */
        private const val HIDE_MS = 50L
        private const val BLANK_PAUSE_MS = 150L
        private const val FISH_PREFETCH = 3
        private const val ICON_PLAY = "▶"
        private const val ICON_PAUSE = "❚❚"
        private const val ICON_BUSY = "⋯"

        @Volatile
        var instance: ReaderService? = null
            private set
    }
}
