package com.manhwavoice.reader

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние и действия экрана настроек (вся логика — здесь, отрисовка — в ui/). */
class AppController(private val activity: ComponentActivity) {

    val prefs: SharedPreferences = Prefs.get(activity)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Меняется при каждой записи настроек — чтобы экран перерисовался. */
    var tick by mutableIntStateOf(0)
        private set

    private fun touch() { tick++ }

    val versionName: String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    // ---------------- Настройки ----------------

    fun int(key: String, def: Int): Int { tick; return prefs.getInt(key, def) }
    fun bool(key: String, def: Boolean): Boolean { tick; return prefs.getBoolean(key, def) }
    fun setBool(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply(); touch() }
    fun setInt(key: String, v: Int) { prefs.edit().putInt(key, v).apply(); touch() }

    // ---------------- Служба спецвозможностей ----------------

    var serviceEnabled by mutableStateOf(false)
        private set

    fun refreshService() {
        serviceEnabled = ReaderService.instance != null || run {
            val enabled = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val me = ComponentName(activity, ReaderService::class.java)
            enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    fun openAccessibility() = activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppDetails() = activity.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))
    )

    fun openUrl(url: String) = runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    // ---------------- Голос телефона ----------------

    private var tts: TextToSpeech? = null
    var ttsOk by mutableStateOf(false)
        private set
    var engines by mutableStateOf<List<TextToSpeech.EngineInfo>>(emptyList())
        private set
    var defaultEngine by mutableStateOf<String?>(null)
        private set
    var systemVoices by mutableStateOf<List<Voice>>(emptyList())
        private set

    fun initTts() {
        tts?.shutdown()
        ttsOk = false
        tts = Voices.create(activity, Voices.enginePref(prefs)) { st ->
            val t = tts
            val ok = st == TextToSpeech.SUCCESS && t != null
            if (ok) t?.setLanguage(Voices.RU)
            activity.runOnUiThread {
                ttsOk = ok
                engines = t?.engines.orEmpty()
                defaultEngine = t?.defaultEngine
                systemVoices = if (ok && t != null) Voices.russianVoices(t) else emptyList()
            }
        }
    }

    val isFish: Boolean get() { tick; return FishTts.enabled(prefs) }
    val systemEnginePref: String? get() { tick; return Voices.enginePref(prefs) }
    val systemVoicePref: String? get() { tick; return prefs.getString(Prefs.KEY_VOICE, null) }

    fun engineLabel(pkg: String?): String {
        val e = engines
        return if (pkg == null) "По умолчанию" + (e.firstOrNull { it.name == defaultEngine }?.label?.let { " · $it" } ?: "")
        else e.firstOrNull { it.name == pkg }?.label ?: pkg
    }

    fun chooseSystemEngine(pkg: String?) {
        val e = prefs.edit().remove(Prefs.KEY_VOICE)
        if (pkg == null) e.remove(Prefs.KEY_ENGINE) else e.putString(Prefs.KEY_ENGINE, pkg)
        e.apply()
        initTts()
        touch()
    }

    fun chooseSystemVoice(name: String?) {
        val e = prefs.edit()
        if (name == null) e.remove(Prefs.KEY_VOICE) else e.putString(Prefs.KEY_VOICE, name)
        // выбрали голос телефона — значит, читаем им
        if (FishTts.enabled(prefs)) e.remove(Prefs.KEY_ENGINE)
        e.apply()
        touch()
        previewSystem()
    }

    fun useFish(on: Boolean) {
        val e = prefs.edit()
        if (on) e.putString(Prefs.KEY_ENGINE, FishTts.ENGINE_ID) else e.remove(Prefs.KEY_ENGINE)
        e.apply()
        initTts()
        touch()
    }

    fun previewSystem() {
        stopAudio()
        val t = tts
        if (t == null || !ttsOk) {
            toast("Синтез речи ещё загружается или не установлен русский голос")
            return
        }
        Voices.apply(t, prefs)
        t.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Прослушать тем голосом, которым сейчас идёт чтение. */
    fun previewCurrent() {
        if (FishTts.enabled(prefs)) {
            val id = prefs.getString(Prefs.KEY_FISH_VOICE, null)
            if (playingId == CURRENT) stopAudio() else synthPreview(CURRENT, id)
        } else previewSystem()
    }

    fun currentVoiceTitle(): String {
        tick
        return if (FishTts.enabled(prefs)) prefs.getString(Prefs.KEY_FISH_VOICE_TITLE, null)
            ?: (if (prefs.getString(Prefs.KEY_FISH_VOICE, "").isNullOrBlank()) "Голос Fish Audio по умолчанию" else "Голос Fish Audio")
        else {
            val name = prefs.getString(Prefs.KEY_VOICE, null)
            val idx = systemVoices.indexOfFirst { it.name == name }
            if (name == null || idx < 0) "Стандартный голос телефона" else Voices.describe(systemVoices[idx], idx).substringBefore("\n")
        }
    }

    fun currentVoiceSubtitle(): String {
        tick
        return if (FishTts.enabled(prefs)) "Fish Audio · " + (if (fishFree) "бесплатная модель" else "платная модель")
        else engineLabel(Voices.enginePref(prefs))
    }

    fun currentVoiceCover(): String? {
        tick
        return if (FishTts.enabled(prefs)) prefs.getString(Prefs.KEY_FISH_VOICE_COVER, null) else null
    }

    fun installVoices() = runCatching { activity.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        .onFailure { toast("Не удалось открыть установку голосов") }

    fun openTtsSettings() = runCatching { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        .onFailure { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }

    // ---------------- Fish Audio ----------------

    val fishKey: String get() { tick; return prefs.getString(Prefs.KEY_FISH_KEY, "").orEmpty() }
    val fishFree: Boolean get() { tick; return prefs.getBoolean(Prefs.KEY_FISH_FREE, true) }
    val fishVoiceId: String get() { tick; return FishTts.normalizeVoiceId(prefs.getString(Prefs.KEY_FISH_VOICE, "").orEmpty()) }

    var fishStatus by mutableStateOf<String?>(null)
        private set

    fun setFishFree(on: Boolean) { prefs.edit().putBoolean(Prefs.KEY_FISH_FREE, on).apply(); touch() }

    /** Вставить ключ из буфера обмена. */
    fun pasteKey() {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
        val key = raw.trim().removePrefix("Bearer ").trim()
        if (key.length < 16 || key.any { it.isWhitespace() }) {
            toast("В буфере нет ключа. Скопируйте ключ целиком и нажмите ещё раз.")
            return
        }
        prefs.edit().putString(Prefs.KEY_FISH_KEY, key).apply()
        fishStatus = null
        touch()
        toast("Ключ сохранён")
        checkKey()
    }

    fun clearKey() {
        prefs.edit().remove(Prefs.KEY_FISH_KEY).apply()
        fishStatus = null
        touch()
    }

    fun checkKey() {
        val key = fishKey
        if (key.isEmpty()) return
        fishStatus = "Проверяю ключ…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FishCatalog.credit(key) } }
            fishStatus = r.fold(
                { "✅ Ключ работает · баланс API: $it" },
                { "❌ " + ((it as? FishTts.FishError)?.message ?: "нет связи с Fish Audio") }
            )
        }
    }

    fun selectFishVoice(v: FishVoice) {
        FishCatalog.select(prefs, v)
        initTts()
        touch()
        toast("Голос «${v.title}» выбран")
    }

    fun setFishVoiceManually(raw: String) {
        val id = FishTts.normalizeVoiceId(raw)
        prefs.edit().putString(Prefs.KEY_FISH_VOICE, id).remove(Prefs.KEY_FISH_VOICE_TITLE).remove(Prefs.KEY_FISH_VOICE_COVER).apply()
        touch()
    }

    fun recents(): List<FishVoice> { tick; return FishCatalog.recents(prefs) }

    // ---------------- Прослушивание образцов ----------------

    var playingId by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    /** Прослушать голос из каталога: образец с сайта, а если его нет — короткая фраза. */
    fun playVoice(v: FishVoice) {
        if (playingId == v.id) { stopAudio(); return }
        stopAudio()
        playingId = v.id
        val url = v.sampleUrl
        if (url != null) playUrl(v.id, url) { synthPreview(v.id, v.id) } else synthPreview(v.id, v.id)
    }

    private fun playUrl(tag: String, url: String, onFail: () -> Unit) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { if (player === it && playingId == tag) it.start() }
            mp.setOnCompletionListener { if (player === it) stopAudio() }
            mp.setOnErrorListener { m, _, _ ->
                if (player === m) {
                    runCatching { m.release() }
                    player = null
                    onFail()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            player = null
            onFail()
        }
    }

    private fun synthPreview(tag: String, voiceId: String?) {
        if (fishKey.isEmpty()) {
            toast("Сначала вставьте API-ключ Fish Audio")
            playingId = null
            return
        }
        playingId = tag
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FishTts.synthesize(activity, prefs, SAMPLE_SHORT, voiceId) } }
            r.onSuccess { file ->
                if (playingId != tag) { file.delete(); return@onSuccess }
                playUrl(tag, file.absolutePath) { playingId = null }
            }.onFailure {
                playingId = null
                toast("Fish Audio: " + ((it as? FishTts.FishError)?.message ?: "нет связи"))
            }
        }
    }

    fun stopAudio() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playingId = null
        tts?.stop()
    }

    // ---------------- Обновления ----------------

    var updateStatus by mutableStateOf("")
        private set
    var offeredUpdate by mutableStateOf<Updater.Release?>(null)
        private set
    var downloadPct by mutableStateOf<Int?>(null)
        private set
    private var checking = false
    private var waitingPermissionFor: Updater.Release? = null

    fun checkUpdates() {
        if (checking || downloadPct != null) return
        checking = true
        updateStatus = "Проверяю обновления…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.fetchLatest() }.getOrNull() }
            checking = false
            val current = Updater.currentVersion(activity)
            when {
                r == null -> updateStatus = "Не удалось проверить (нет интернета?)"
                r.versionCode > current -> {
                    updateStatus = "Доступна версия 1.${r.versionCode}"
                    offeredUpdate = r
                }
                else -> updateStatus = "✅ Установлена последняя версия"
            }
        }
    }

    fun dismissUpdate() { offeredUpdate = null }

    fun startUpdate(r: Updater.Release) {
        offeredUpdate = null
        if (!activity.packageManager.canRequestPackageInstalls()) {
            waitingPermissionFor = r
            toast("Разрешите «Установку неизвестных приложений» и вернитесь назад")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        if (downloadPct != null) return
        downloadPct = 0
        updateStatus = "Скачиваю обновление…"
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(activity, r) { pct -> activity.runOnUiThread { downloadPct = pct } }
                }
                updateStatus = "Устанавливаю… подтвердите в окне Android"
                withContext(Dispatchers.IO) { Updater.install(activity, apk) }
            } catch (e: Exception) {
                updateStatus = "Ошибка обновления: ${e.message}"
            } finally {
                downloadPct = null
            }
        }
    }

    fun onResume() {
        refreshService()
        waitingPermissionFor?.let { r ->
            if (activity.packageManager.canRequestPackageInstalls()) {
                waitingPermissionFor = null
                startUpdate(r)
            }
        }
    }

    fun release() {
        stopAudio()
        tts?.shutdown()
        scope.cancel()
    }

    companion object {
        const val SAMPLE = "Хотите сказать, я, сильнейший рыцарь севера, сражён этим ничтожным выродком?!"
        const val SAMPLE_SHORT = "Привет! Вот так я буду читать твою манхву."
        private const val CURRENT = "__current__"
    }
}
