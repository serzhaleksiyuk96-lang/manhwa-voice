package com.manhwavoice.reader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

object Prefs {
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Скорость речи, 50..200 (%) */
    fun rate(p: SharedPreferences) = p.getInt(KEY_RATE, 100) / 100f
    /** Шаг прокрутки в % высоты экрана, 40..75 */
    fun scrollFraction(p: SharedPreferences) = p.getInt(KEY_SCROLL, 60) / 100f
    /** Пауза после прочитанного экрана, мс */
    fun pauseAfterSpeech(p: SharedPreferences) = p.getInt(KEY_PAUSE_TEXT, DEFAULT_PAUSE_TEXT).toLong()
    /** Пауза на экране без текста, мс */
    fun pauseNoText(p: SharedPreferences) = p.getInt(KEY_PAUSE_EMPTY, DEFAULT_PAUSE_EMPTY).toLong()

    const val DEFAULT_PAUSE_TEXT = 200
    const val DEFAULT_PAUSE_EMPTY = 600

    /** Высота голоса, 50..200 (%) */
    fun pitch(p: SharedPreferences) = p.getInt(KEY_PITCH, 100) / 100f

    const val KEY_RATE = "rate"
    const val KEY_PITCH = "pitch"
    const val KEY_ENGINE = "tts_engine"
    const val KEY_VOICE = "tts_voice"
    const val KEY_FISH_KEY = "fish_key"
    const val KEY_FISH_VOICE = "fish_voice"
    const val KEY_FISH_FREE = "fish_free"
    const val KEY_SCROLL = "scroll"
    const val KEY_PAUSE_TEXT = "pause_text"
    const val KEY_PAUSE_EMPTY = "pause_empty"
    const val KEY_BTN_X = "btn_x"
    const val KEY_BTN_Y = "btn_y"
}

/** Маленький «отпечаток» экрана, чтобы понять, сдвинулась ли страница после прокрутки. */
object Fingerprint {
    private const val GW = 36
    private const val GH = 64

    fun of(src: Bitmap, exclude: Rect?): IntArray {
        val small = Bitmap.createScaledBitmap(src, GW, GH, true)
        val px = IntArray(GW * GH)
        small.getPixels(px, 0, GW, 0, 0, GW, GH)
        if (small != src) small.recycle()
        val sx = src.width / GW.toFloat()
        val sy = src.height / GH.toFloat()
        val cell = Rect()
        return IntArray(px.size) { i ->
            val cx = i % GW
            val cy = i / GW
            cell.set((cx * sx).toInt(), (cy * sy).toInt(), ((cx + 1) * sx).toInt(), ((cy + 1) * sy).toInt())
            if (exclude != null && Rect.intersects(exclude, cell)) 0
            else {
                val p = px[i]
                (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
            }
        }
    }

    /** Экраны практически одинаковые. */
    fun same(a: IntArray, b: IntArray): Boolean {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size < 3.0
    }

    /** Экран почти однотонный (белый или чёрный промежуток между кадрами). */
    fun isUniform(a: IntArray): Boolean {
        val mean = a.average()
        var v = 0.0
        for (x in a) v += (x - mean) * (x - mean)
        return sqrt(v / a.size) < 6.0
    }
}

/** Выбор движка и голоса синтеза речи. */
object Voices {
    val RU: Locale = Locale.forLanguageTag("ru-RU")

    /** Системный движок синтеза речи (Fish Audio — не системный, для него берём движок по умолчанию). */
    fun enginePref(p: SharedPreferences): String? =
        p.getString(Prefs.KEY_ENGINE, null)?.takeIf { it.isNotEmpty() && it != FishTts.ENGINE_ID }

    fun create(context: Context, enginePkg: String?, listener: TextToSpeech.OnInitListener): TextToSpeech =
        if (enginePkg == null) TextToSpeech(context, listener) else TextToSpeech(context, listener, enginePkg)

    /** Русские голоса, установленные в движке: сначала те, что работают без интернета. */
    fun russianVoices(tts: TextToSpeech): List<Voice> =
        runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { v ->
                v.locale.language == "ru" &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))

    fun describe(v: Voice, index: Int): String {
        val net = if (v.isNetworkConnectionRequired) "нужен интернет" else "без интернета"
        return "Голос ${index + 1} · $net\n${v.name}"
    }

    /** Применяет сохранённые голос, высоту и скорость. */
    fun apply(tts: TextToSpeech, p: SharedPreferences) {
        val name = p.getString(Prefs.KEY_VOICE, null)
        if (name != null && tts.voice?.name != name) {
            russianVoices(tts).firstOrNull { it.name == name }?.let { tts.setVoice(it) }
        } else if (name == null && tts.voice?.locale?.language != "ru") {
            tts.setLanguage(RU)
        }
        tts.setPitch(Prefs.pitch(p))
        tts.setSpeechRate(Prefs.rate(p))
    }
}
