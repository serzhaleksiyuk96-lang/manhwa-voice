package com.manhwavoice.reader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.sqrt

object Prefs {
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Скорость речи, 50..200 (%) */
    fun rate(p: SharedPreferences) = p.getInt(KEY_RATE, 100) / 100f
    /** Шаг прокрутки в % высоты экрана, 40..75 */
    fun scrollFraction(p: SharedPreferences) = p.getInt(KEY_SCROLL, 60) / 100f
    /** Пауза после прочитанного экрана, мс */
    fun pauseAfterSpeech(p: SharedPreferences) = p.getInt(KEY_PAUSE_TEXT, 300).toLong()
    /** Пауза на экране без текста, мс */
    fun pauseNoText(p: SharedPreferences) = p.getInt(KEY_PAUSE_EMPTY, 800).toLong()

    const val KEY_RATE = "rate"
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
