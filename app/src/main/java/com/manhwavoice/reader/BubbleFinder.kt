package com.manhwavoice.reader

/**
 * Речевой пузырь: светлая область, замкнутая контуром. Хранит форму (по строкам и столбцам),
 * чтобы при распознавании закрасить всё вокруг — контур, хвостик, кусочки рисунка.
 */
class Bubble(
    val box: Box,
    private val rowMin: IntArray, private val rowMax: IntArray,
    private val colMin: IntArray, private val colMax: IntArray
) {
    /** Точка внутри пузыря (вместе с буквами, но без контура и рисунка вокруг). */
    fun inside(x: Int, y: Int): Boolean {
        val r = y - box.top
        val c = x - box.left
        if (r < 0 || c < 0 || r >= rowMin.size || c >= colMin.size) return false
        return x >= rowMin[r] && x <= rowMax[r] && y >= colMin[c] && y <= colMax[c]
    }
}

/**
 * Ищет речевые пузыри и рамки с текстом. Каждый пузырь потом распознаётся отдельно,
 * без контура и рисунка вокруг — так текст читается почти без ошибок.
 * Чистый Kotlin без Android, чтобы можно было проверить на компьютере.
 */
object BubbleFinder {

    class Result(
        val bubbles: List<Bubble>,
        /** Светлые области, обрезанные нижним краем экрана: их текст прочитаем после прокрутки */
        val cutAtBottom: List<Box>
    )

    fun find(gray: IntArray, w: Int, h: Int, maxCount: Int = 12): Result {
        val n = w * h
        val light = BooleanArray(n) { gray[it] > 200 }
        val label = IntArray(n)
        val queue = IntArray(n)
        val found = ArrayList<Pair<Int, Bubble>>()
        val cut = ArrayList<Box>()
        var current = 0
        val minArea = (0.002 * n).toInt()
        val maxArea = (0.3 * n).toInt()

        for (start in 0 until n) {
            if (!light[start] || label[start] != 0) continue
            current++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = current
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            var tLeft = false; var tRight = false; var tTop = false; var tBottom = false
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0) tLeft = true
                if (x == w - 1) tRight = true
                if (y == 0) tTop = true
                if (y == h - 1) tBottom = true
                if (x > 0) { val q = p - 1; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (light[q] && label[q] == 0) { label[q] = current; queue[tail++] = q } }
            }
            val area = tail
            if (area < minArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val fill = area.toDouble() / (bw.toLong() * bh)

            // Пузырь, обрезанный нижним краем (не во всю ширину — это не фон страницы)
            if (tBottom && !tLeft && !tRight && !tTop && fill >= 0.4 && bw < w * 0.9) {
                cut += Box(minX, minY, maxX + 1, maxY + 1)
                continue
            }
            if (tLeft || tRight || tTop || tBottom) continue
            if (area > maxArea || bw < 60 || bh < 40 || fill < 0.45) continue

            // Форма: для каждой строки и столбца — от первого до последнего светлого пикселя
            val rowMin = IntArray(bh) { Int.MAX_VALUE }
            val rowMax = IntArray(bh) { -1 }
            val colMin = IntArray(bw) { Int.MAX_VALUE }
            val colMax = IntArray(bw) { -1 }
            for (y in minY..maxY) {
                val row = y * w
                for (x in minX..maxX) {
                    if (label[row + x] != current) continue
                    val r = y - minY
                    val c = x - minX
                    if (x < rowMin[r]) rowMin[r] = x
                    if (x > rowMax[r]) rowMax[r] = x
                    if (y < colMin[c]) colMin[c] = y
                    if (y > colMax[c]) colMax[c] = y
                }
            }
            val bubble = Bubble(Box(minX, minY, maxX + 1, maxY + 1), rowMin, rowMax, colMin, colMax)

            // Доля светлого внутри фигуры (с буквами). У пузыря с текстом она высокая,
            // у светлого куска рисунка (волосы, лицо) внутри много тёмных линий.
            var filled = 0
            for (y in minY..maxY) for (x in minX..maxX) if (bubble.inside(x, y)) filled++
            val lightShare = area.toDouble() / filled.coerceAtLeast(1)
            val needed = if (area > 0.08 * n) 0.88 else 0.84
            if (lightShare < needed) continue

            found += area to bubble
        }
        val bubbles = found.sortedByDescending { it.first }.take(maxCount).map { it.second }
        return Result(bubbles, cut)
    }
}
