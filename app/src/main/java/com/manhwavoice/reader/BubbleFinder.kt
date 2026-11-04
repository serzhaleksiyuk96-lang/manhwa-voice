package com.manhwavoice.reader

/**
 * Ищет речевые пузыри и рамки с текстом: светлые области, замкнутые контуром
 * (не касаются края экрана) и достаточно «плотные» по форме.
 * Каждый пузырь потом распознаётся отдельно — так яркая картинка вокруг
 * не сбивает порог яркости и текст в пузыре не теряется.
 * Чистый Kotlin без Android, чтобы можно было проверить на компьютере.
 */
object BubbleFinder {

    fun find(gray: IntArray, w: Int, h: Int, maxCount: Int = 10): List<Box> {
        val n = w * h
        val light = BooleanArray(n) { gray[it] > 200 }
        val label = IntArray(n)
        val queue = IntArray(n)
        val result = ArrayList<Pair<Int, Box>>()
        var current = 0
        val minArea = (0.002 * n).toInt()
        val maxArea = (0.5 * n).toInt()

        for (start in 0 until n) {
            if (!light[start] || label[start] != 0) continue
            current++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = current
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            var touches = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                if (x > 0) { val q = p - 1; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
            }
            val area = tail
            if (touches || area < minArea || area > maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 60 || bh < 40) continue
            val fill = area.toDouble() / (bw.toLong() * bh)
            if (fill < 0.45) continue
            result += area to Box(minX, minY, maxX + 1, maxY + 1)
        }
        return result.sortedByDescending { it.first }.take(maxCount).map { it.second }
    }
}
