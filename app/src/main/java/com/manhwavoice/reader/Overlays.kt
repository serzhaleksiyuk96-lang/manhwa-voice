package com.manhwavoice.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Неподвижные панели поверх страницы — шапка сайта сверху, панель кнопок и счётчик страниц снизу.
 * Узнаются после прокрутки: страница сдвинулась, а они остались на месте. Текст под ними
 * виден не целиком, поэтому читается только то, что между панелями.
 * Чистый Kotlin без Android — проверяется на компьютере.
 */
object Overlays {

    /** Уменьшенная копия экрана в оттенках серого. */
    class Small(val w: Int, val h: Int, val px: IntArray)

    /** Панели: высота сверху и снизу в долях высоты экрана. */
    data class Insets(val top: Float, val bottom: Float)

    private const val CELL = 16

    /** Отладочный вывод (только для проверок на компьютере) */
    @JvmField var debug = false

    fun small(argb: IntArray, w: Int, h: Int): Small {
        val px = IntArray(w * h)
        for (i in px.indices) {
            val p = argb[i]
            px[i] = (((p shr 16) and 0xff) * 30 + ((p shr 8) and 0xff) * 59 + (p and 0xff) * 11) / 100
        }
        return Small(w, h, px)
    }

    /**
     * Сравнивает экраны до и после прокрутки. Возвращает панели или null, если по этой паре
     * не понять (страница не сдвинулась, сменилась целиком и т.п.). [skipTop] — доля сверху,
     * которую не смотрим (строка состояния).
     */
    fun detect(prev: Small, cur: Small, skipTop: Float): Insets? {
        if (prev.w != cur.w || prev.h != cur.h) return null
        val w = cur.w
        val h = cur.h
        // 1) насколько сдвинулось содержимое: сравниваем середину экрана (без полос у краёв,
        //    где могут быть панели) со сдвинутой на d строк прошлой картинкой
        val lo = (h * 0.12f).toInt()
        val hi = (h * 0.88f).toInt()
        var bestD = -1
        var bestErr = Double.MAX_VALUE
        var zeroErr = 0.0
        for (d in 0 until (h * 0.85f).toInt()) {
            if (hi - d - lo < h * 0.04f) break
            var err = 0L
            var n = 0
            var y = lo
            while (y + d < hi) {
                val a = y * w
                val b = (y + d) * w
                var x = 0
                while (x < w) { err += abs(cur.px[a + x] - prev.px[b + x]); n++; x += 2 }
                y += 2
            }
            if (n == 0) continue
            val e = err.toDouble() / n
            if (d == 0) zeroErr = e
            if (d >= 4 && e < bestErr) { bestErr = e; bestD = d }
        }
        if (debug) println("  сдвиг $bestD, ошибка ${"%.1f".format(bestErr)}, без сдвига ${"%.1f".format(zeroErr)}")
        if (bestD < 0 || bestErr > 40.0 || zeroErr < bestErr * 3) return null

        // 2) неподвижные строки: часть ширины не изменилась (и это не однотонный фон), хотя
        //    страница под ними уехала; или вся строка — однотонная панель, не уехавшая со страницей
        val cells = max(1, w / CELL)
        val panelRow = BooleanArray(h)
        for (y in (h * skipTop).toInt() until h) {
            var textured = 0
            var plain = 0
            val verifiable = y + bestD < h
            for (c in 0 until cells) {
                val xs = c * CELL
                val xe = min(w, xs + CELL)
                var still = 0L
                var moved = 0L
                var sum = 0L
                var sum2 = 0L
                var n = 0
                for (x in xs until xe) {
                    val v = cur.px[y * w + x]
                    still += abs(v - prev.px[y * w + x])
                    if (verifiable) moved += abs(v - prev.px[(y + bestD) * w + x])
                    sum += v
                    sum2 += v.toLong() * v
                    n++
                }
                val st = still.toDouble() / n
                val mean = sum.toDouble() / n
                val sd = sqrt(max(0.0, sum2.toDouble() / n - mean * mean))
                val mv = if (verifiable) moved.toDouble() / n else 255.0
                if (st < 12 && sd > 8 && mv > 14) textured++
                if (verifiable && st < 6 && mv > 14) plain++
            }
            panelRow[y] = textured * 5 >= cells || plain * 10 >= cells * 9
        }
        // 3) панели у краёв: сверху — в верхней пятой части экрана, снизу — в нижней
        var top = 0
        for (y in (h * skipTop).toInt() until (h * 0.2f).toInt()) if (panelRow[y]) top = y + 1
        var bottom = h
        for (y in h - 1 downTo (h * 0.8f).toInt()) if (panelRow[y]) bottom = y
        // однотонный тёмный верх панели (над кнопками) тоже неподвижен — прибавляем его
        if (bottom < h) {
            val limit = max((h * 0.75f).toInt(), bottom - (h * 0.08f).toInt())
            while (bottom - 1 > limit) {
                val y = bottom - 1
                var still = 0
                var cnt = 0
                var x = 0
                while (x < w) {
                    val v = cur.px[y * w + x]
                    if (v < 110 && abs(v - prev.px[y * w + x]) < 6) still++
                    cnt++
                    x += 2
                }
                if (still * 4 < cnt) break
                bottom = y
            }
        }
        val topF = if (top > 0) (top + 2).toFloat() / h else 0f
        val bottomF = if (bottom < h) (h - bottom + 2).toFloat() / h else 0f
        return Insets(topF, bottomF)
    }
}
