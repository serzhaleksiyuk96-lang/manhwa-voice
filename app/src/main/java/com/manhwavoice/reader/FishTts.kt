package com.manhwavoice.reader

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Озвучка через Fish Audio (нейросетевые голоса, нужен интернет и API-ключ).
 * Документация: https://docs.fish.audio — POST /v1/tts, ответ — mp3.
 */
object FishTts {

    const val ENGINE_ID = "fish"
    const val MODEL_FREE = "s2.1-pro-free"
    const val MODEL_PAID = "s2.1-pro"
    private const val API = "https://api.fish.audio/v1/tts"

    class FishError(message: String) : Exception(message)

    fun enabled(p: SharedPreferences) = p.getString(Prefs.KEY_ENGINE, null) == ENGINE_ID

    /** Из ссылки вида https://fish.audio/m/802e3bc2... достаёт ID голоса. */
    fun normalizeVoiceId(raw: String): String {
        val s = raw.trim()
        return Regex("[0-9a-fA-F]{32}").find(s)?.value?.lowercase() ?: s
    }

    fun model(p: SharedPreferences) =
        if (p.getBoolean(Prefs.KEY_FISH_FREE, true)) MODEL_FREE else MODEL_PAID

    /** Синтезирует фразу в mp3-файл. Блокирующий вызов — не из главного потока. */
    fun synthesize(context: Context, p: SharedPreferences, text: String, voiceOverride: String? = null): File {
        val key = p.getString(Prefs.KEY_FISH_KEY, "").orEmpty().trim()
        if (key.isEmpty()) throw FishError("не указан API-ключ Fish Audio (раздел «Голос»)")
        val voice = normalizeVoiceId(voiceOverride ?: p.getString(Prefs.KEY_FISH_VOICE, "").orEmpty())

        val body = JSONObject().apply {
            put("text", text)
            put("format", "mp3")
            put("mp3_bitrate", 64)   // речь — хватает и 64 кбит/с, скачивается вдвое быстрее
            put("latency", "balanced")
            put("normalize", true)
            put("language", "ru")
            if (voice.isNotEmpty()) put("reference_id", voice)
            put("prosody", JSONObject().put("speed", Prefs.rate(p).toDouble().coerceIn(0.5, 2.0)))
        }

        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("model", model(p))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val details = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.let { JSONObject(it).optString("message") }
                }.getOrNull().orEmpty()
                throw FishError(
                    when (code) {
                        401 -> "неверный API-ключ Fish Audio"
                        402 -> "на счёте Fish Audio нет денег — включите «Бесплатная модель» или пополните баланс"
                        404 -> "голос не найден — проверьте ID или ссылку на голос"
                        429 -> "слишком много запросов к Fish Audio, попробуйте позже"
                        503 -> "сервер Fish Audio перегружен, попробуйте позже"
                        else -> "Fish Audio ответил $code ${details.take(120)}"
                    }
                )
            }
            val file = File(context.cacheDir, "fish_${System.nanoTime()}.mp3")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            if (file.length() < 100) {
                file.delete()
                throw FishError("Fish Audio вернул пустой звук")
            }
            return file
        } finally {
            conn.disconnect()
        }
    }

    private fun newPlayer(file: File): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        setDataSource(file.absolutePath)
        prepare()
    }

    /** Проигрывает файл и ждёт окончания. При отмене (пауза) сразу замолкает. */
    suspend fun play(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val mp = try {
            newPlayer(file)
        } catch (e: Exception) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        mp.setOnCompletionListener {
            runCatching { it.release() }
            if (cont.isActive) cont.resume(true)
        }
        mp.setOnErrorListener { m, _, _ ->
            runCatching { m.release() }
            if (cont.isActive) cont.resume(false)
            true
        }
        cont.invokeOnCancellation {
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        mp.start()
    }

    /** Проигрывание без корутин — для кнопки «Прослушать» на экране настроек. */
    fun playOnce(file: File, onDone: () -> Unit = {}): MediaPlayer? = try {
        newPlayer(file).apply {
            setOnCompletionListener { runCatching { it.release() }; file.delete(); onDone() }
            start()
        }
    } catch (e: Exception) {
        file.delete()
        onDone()
        null
    }
}
