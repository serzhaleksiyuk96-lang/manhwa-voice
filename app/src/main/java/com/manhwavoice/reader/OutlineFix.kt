package com.manhwavoice.reader

import kotlin.math.min

/**
 * Обработка «контурного» шрифта: тёмные буквы с белой обводкой на тёмном фоне.
 * Для распознавания такие буквы выглядят полыми, поэтому делаем из них
 * обычные чёрные буквы на белом:
 *  - фон = тёмные области, связанные с краем экрана;
 *  - тело буквы = замкнутая тёмная область, отделённая от фона тонкой обводкой
 *    (расстояние до фона не больше ~1.3 толщины обводки);
 *  - дырки и просветы букв («О», «С», «Е») лежат глубже — они становятся белыми.
 * Чистый Kotlin без Android, чтобы можно было проверить на компьютере.
 */
object OutlineFix {

    class Result(val pixels: IntArray?, val backgroundShare: Float, val bodyComponents: Int)

    /** Контурный шрифт ищем только на тёмных экранах: фон должен занимать хотя бы такую долю */
    const val MIN_BACKGROUND = 0.3f

    /**
     * @param gray яркость 0..255, построчно, размер w*h
     * @return pixels — 0 (чернила) или 255 (бумага), либо null, если на экране нет такого текста
     */
    fun process(gray: IntArray, w: Int, h: Int): Result {
        val n = w * h
        val white = BooleanArray(n) { gray[it] > 128 }

        // 1) Связные области тёмного (4-связность)
        val label = IntArray(n)
        val queue = IntArray(n)
        var count = 0
        for (start in 0 until n) {
            if (white[start] || label[start] != 0) continue
            count++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = count
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x > 0) { val q = p - 1; if (!white[q] && label[q] == 0) { label[q] = count; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (!white[q] && label[q] == 0) { label[q] = count; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (!white[q] && label[q] == 0) { label[q] = count; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (!white[q] && label[q] == 0) { label[q] = count; queue[tail++] = q } }
            }
        }
        if (count == 0) return Result(null, 0f, 0)

        // 2) Фон — области, касающиеся края
        val isBorder = BooleanArray(count + 1)
        for (x in 0 until w) { isBorder[label[x]] = true; isBorder[label[(h - 1) * w + x]] = true }
        for (y in 0 until h) { isBorder[label[y * w]] = true; isBorder[label[y * w + w - 1]] = true }
        isBorder[0] = false
        var bgPixels = 0
        for (p in 0 until n) if (isBorder[label[p]]) bgPixels++
        val bgShare = bgPixels / n.toFloat()
        if (bgShare < MIN_BACKGROUND) return Result(null, bgShare, 0)

        // 3) Толщина обводки: медиана белых горизонтальных отрезков между тёмными
        val hist = IntArray(41)
        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (white[row + x]) {
                    val s = x
                    while (x < w && white[row + x]) x++
                    val len = x - s
                    if (s > 0 && x < w && len in 3..40) hist[len]++
                } else x++
            }
            y += 4
        }
        val total = hist.sum()
        var outline = 8
        if (total > 0) {
            var acc = 0
            for (i in hist.indices) { acc += hist[i]; if (acc * 2 >= total) { outline = i; break } }
        }
        val reach = (outline * 1.3f + 3).toInt().coerceIn(6, 40)

        // 4) Расстояние до фона (шахматная метрика, два прохода)
        val inf = 1 shl 20
        val dist = IntArray(n) { if (isBorder[label[it]]) 0 else inf }
        for (yy in 0 until h) for (xx in 0 until w) {
            val p = yy * w + xx
            var d = dist[p]
            if (d == 0) continue
            if (xx > 0) d = min(d, dist[p - 1] + 1)
            if (yy > 0) {
                d = min(d, dist[p - w] + 1)
                if (xx > 0) d = min(d, dist[p - w - 1] + 1)
                if (xx < w - 1) d = min(d, dist[p - w + 1] + 1)
            }
            dist[p] = d
        }
        for (yy in h - 1 downTo 0) for (xx in w - 1 downTo 0) {
            val p = yy * w + xx
            var d = dist[p]
            if (d == 0) continue
            if (xx < w - 1) d = min(d, dist[p + 1] + 1)
            if (yy < h - 1) {
                d = min(d, dist[p + w] + 1)
                if (xx < w - 1) d = min(d, dist[p + w + 1] + 1)
                if (xx > 0) d = min(d, dist[p + w - 1] + 1)
            }
            dist[p] = d
        }

        // 5) Тело буквы — замкнутая тёмная область, которая подходит к фону ближе reach
        val minDist = IntArray(count + 1) { inf }
        for (p in 0 until n) {
            val l = label[p]
            if (l != 0 && dist[p] < minDist[l]) minDist[l] = dist[p]
        }
        val body = BooleanArray(count + 1)
        var bodies = 0
        for (l in 1..count) if (!isBorder[l] && minDist[l] <= reach) { body[l] = true; bodies++ }
        if (bodies == 0) return Result(null, bgShare, 0)

        val out = IntArray(n) { if (body[label[it]]) 0 else 255 }
        return Result(out, bgShare, bodies)
    }
}
