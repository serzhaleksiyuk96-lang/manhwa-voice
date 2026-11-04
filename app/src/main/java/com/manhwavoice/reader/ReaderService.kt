package com.manhwavoice.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import kotlinx.coroutines.channels.ReceiveChannel
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
import kotlin.coroutines.resume
import kotlin.math.abs

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

    // ---------------- Озвучка реплик по порядку ----------------

    /** Что произнести ([say] — вся реплика или её непрочитанное продолжение) и что запомнить. */
    private class Utterance(val say: String, val remember: String)

    /**
     * Озвучивает реплики по мере их появления. [produce] выдаёт реплики по порядку,
     * следующая берётся, когда предыдущая уже звучит (для Fish Audio — скачивается заранее).
     * Возвращает число прочитанных реплик.
     */
    private suspend fun speakOrdered(produce: suspend (suspend (Utterance) -> Unit) -> Unit): Int = coroutineScope {
        val ch = Channel<Utterance>(Channel.RENDEZVOUS)
        launch {
            try {
                produce { ch.send(it) }
            } finally {
                ch.close()
            }
        }
        if (FishTts.enabled(prefs)) consumeFish(ch) else consumeSystem(ch)
    }

    /**
     * Системный голос: следующая реплика ставится в очередь синтеза, пока звучит текущая, —
     * так между репликами нет лишней паузы.
     */
    private suspend fun consumeSystem(ch: ReceiveChannel<Utterance>): Int {
        val inFlight = ArrayDeque<Pair<Utterance, CompletableDeferred<Boolean>?>>()
        var n = 0
        for (u in ch) {
            setState(State.SPEAKING)
            inFlight.addLast(u to submit(u.say))
            if (inFlight.size >= 2) {
                val (done, d) = inFlight.removeFirst()
                awaitSpeech(d)
                memory.add(done.remember)
                n++
            }
        }
        while (inFlight.isNotEmpty()) {
            val (done, d) = inFlight.removeFirst()
            awaitSpeech(d)
            memory.add(done.remember)
            n++
        }
        return n
    }

    /**
     * Fish Audio: следующие реплики (до трёх) скачиваются заранее, пока звучит текущая.
     * Если Fish Audio недоступен, реплика читается системным голосом.
     */
    private suspend fun consumeFish(ch: ReceiveChannel<Utterance>): Int = coroutineScope {
        val queue = ArrayDeque<Pair<Utterance, Deferred<File?>>>()
        var closed = false
        var n = 0
        fun enqueue(u: Utterance) {
            queue.addLast(u to async(Dispatchers.IO) { fishSynth(u.say) })
        }
        fun topUp() {
            while (!closed && queue.size < FISH_PREFETCH) {
                val r = ch.tryReceive()
                if (r.isSuccess) {
                    enqueue(r.getOrThrow())
                } else {
                    if (r.isClosed) closed = true
                    break
                }
            }
        }
        while (true) {
            topUp()
            if (queue.isEmpty()) {
                if (closed) break
                val r = ch.receiveCatching()
                if (r.isClosed) break
                enqueue(r.getOrThrow())
                continue
            }
            val (u, job) = queue.removeFirst()
            val file = job.await()
            topUp()
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
            memory.add(u.remember)
            n++
        }
        n
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

    /**
     * Распознаёт экран и озвучивает текст. Пузыри (точная модель, без контура и рисунка)
     * читаются сразу; остальной экран параллельно ищется быстрой моделью, уточняется точной
     * и вставляется по порядку сверху вниз.
     * Возвращает число прочитанных реплик или -1, если экран пустой.
     * [newScreen] = false — тот же экран читается ещё раз (в конце главы).
     */
    private suspend fun readScreen(
        shot: Bitmap,
        excl: Rect?,
        statusBar: Int,
        skipTopCut: Boolean,
        skipBottomCut: Boolean,
        newScreen: Boolean = true
    ): Int {
        val prep = try {
            withContext(Dispatchers.Default) { ocr.prepare(shot, excl, statusBar, skipTopCut, skipBottomCut) }
        } finally {
            shot.recycle()
        }
        if (newScreen) memory.nextScreen()
        if (prep.blank) return -1
        return coroutineScope {
            val rest = async {
                val cands = withContext(ocr.fastDispatcher) { ocr.detectRest(prep) }
                if (cands.isEmpty()) emptyList() else withContext(ocr.bestDispatcher) { ocr.refine(cands) }
            }
            val bubbles = withContext(ocr.bestDispatcher) { ocr.readBubbles(prep) }
            speakOrdered { emit ->
                val local = TextMemory()
                suspend fun offer(p: Phrase) {
                    if (local.seen(p.text)) return
                    // Новая реплика целиком, или продолжение той, что раньше была обрезана краем
                    val say = memory.unread(p.text) ?: return
                    local.add(p.text)
                    emit(Utterance(say, p.text))
                }
                // Текст вне пузырей; то, что пересекается с репликами из пузырей, уже прочитано
                fun outside(list: List<Phrase>) =
                    list.filter { r -> bubbles.none { TextLayout.intersects(it.box, r.box) } }
                var restList: List<Phrase>? = null
                val used = HashSet<Int>()
                for (p in bubbles) {
                    if (restList == null && rest.isCompleted) restList = outside(rest.await())
                    restList?.forEachIndexed { i, r ->
                        if (i !in used && r.box.top < p.box.top) {
                            used += i
                            offer(r)
                        }
                    }
                    offer(p)
                }
                val all = restList ?: outside(rest.await())
                all.forEachIndexed { i, r ->
                    if (i !in used) {
                        used += i
                        offer(r)
                    }
                }
            }
        }
    }

    // ---------------- Главный цикл ----------------

    private fun toggle() {
        if (loopJob?.isActive == true) stopReading(null) else startReading()
    }

    private fun startReading() {
        readingPkg = foregroundPkg
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
        loopJob?.cancel()
        loopJob = null
        tts?.stop()
        setState(State.IDLE)
        message?.let { toast(it) }
    }

    private suspend fun runLoop() {
        setState(State.BUSY)
        if (!ocr.ensureReady()) {
            toast("Не удалось загрузить модель распознавания текста")
            return
        }
        ensureEngine()
        if (!ttsReady.await()) {
            toast("Нет русского голоса. Откройте приложение → раздел «Голос»")
            return
        }
        val statusBar = statusBarHeight()
        var prevFp: IntArray? = null
        var noMove = 0
        var uniformRun = 0
        var firstScreen = true

        while (currentCoroutineContext().isActive) {
            setState(State.BUSY)
            val shot = takeShot()
            if (shot == null) {
                delay(400)
                continue
            }
            val excl = buttonRect()
            val fp = Fingerprint.of(shot, excl)
            val uniform = Fingerprint.isUniform(fp)
            uniformRun = if (uniform) uniformRun + 1 else 0

            val stuck = prevFp != null && !uniform && Fingerprint.same(prevFp, fp)
            if (stuck || uniformRun >= 15) {
                noMove++
                if (noMove == 1 && stuck) {
                    // Возможно, страница ещё подгружается — пробуем ещё раз
                    shot.recycle()
                    delay(1200)
                    scroll(excl)
                    delay(SETTLE_MS)
                    continue
                }
                // Дальше листать некуда — дочитываем низ экрана и останавливаемся
                readScreen(shot, excl, statusBar, skipTopCut = !firstScreen, skipBottomCut = false, newScreen = false)
                setState(State.SPEAKING)
                speak("Конец главы.")
                return
            }
            noMove = 0
            prevFp = fp

            val spoken = readScreen(shot, excl, statusBar, skipTopCut = !firstScreen, skipBottomCut = true)
            firstScreen = false
            setState(State.BUSY)
            delay(
                when {
                    spoken > 0 -> Prefs.pauseAfterSpeech(prefs)
                    spoken == 0 -> Prefs.pauseNoText(prefs)
                    else -> BLANK_PAUSE_MS   // пустой промежуток между кадрами — листаем сразу
                }
            )
            scroll(buttonRect())
            delay(SETTLE_MS)
        }
    }

    // ---------------- Снимок экрана ----------------

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

    private suspend fun scroll(excl: Rect?) {
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val dist = h * Prefs.scrollFraction(prefs)
        val startY = h * 0.5f + dist / 2f
        val endY = startY - dist
        var x = w * 0.5f
        if (excl != null && x >= excl.left - 30 && x <= excl.right + 30) {
            x = if (excl.centerX() > w / 2) w * 0.25f else w * 0.75f
        }
        // 1) ведём палец вверх...
        val move = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, startY); lineTo(x, endY) }, 0, 350, true
        )
        val ok = dispatch(GestureDescription.Builder().addStroke(move).build())
        // 2) ...и на секунду задерживаем его на месте перед отпусканием, чтобы страница
        //    не «улетела» по инерции и прокрутка была предсказуемой
        if (ok) {
            val hold = move.continueStroke(Path().apply { moveTo(x, endY) }, 0, 120, false)
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
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC2B2140.toInt())
                setStroke((2 * dp).toInt(), 0xAAFFD54F.toInt())
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

    private fun setState(s: State) {
        button?.text = when (s) {
            State.IDLE -> ICON_PLAY
            State.BUSY -> ICON_BUSY
            State.SPEAKING -> ICON_PAUSE
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
        private const val SETTLE_MS = 300L
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
