package com.manhwavoice.reader

/**
 * Речевой пузырь: светлая область, замкнутая контуром. Хранит форму (по строкам и столбцам),
 * чтобы при распознавании закрасить всё вокруг — контур, хвостик, кусочки рисунка.
 * Форма хранится в уменьшенном масштабе [step] (поиск идёт по уменьшенной картинке).
 */
class Bubble(
    /** Рамка в полном масштабе */
    val box: Box,
    private val step: Int,
    private val left: Int, private val top: Int,          // начало рамки в уменьшенном масштабе
    private val rowMin: IntArray, private val rowMax: IntArray,
    private val colMin: IntArray, private val colMax: IntArray
) {
    /** Точка (в полном масштабе) внутри пузыря — вместе с буквами, но без контура и рисунка вокруг. */
    fun inside(x: Int, y: Int): Boolean {
        val sx = x / step
        val sy = y / step
        val r = sy - top
        val c = sx - left
        if (r < 0 || c < 0 || r >= rowMin.size || c >= colMin.size) return false
        // строго внутри: крайние уменьшенные точки могут задевать контур
        return if (step > 1) sx > rowMin[r] && sx < rowMax[r] && sy > colMin[c] && sy < colMax[c]
        else sx >= rowMin[r] && sx <= rowMax[r] && sy >= colMin[c] && sy <= colMax[c]
    }
}

/**
 * Ищет речевые пузыри и рамки с текстом. Каждый пузырь потом распознаётся отдельно,
 * без контура и рисунка вокруг — так текст читается почти без ошибок.
 * Для скорости поиск идёт по картинке, уменьшенной в [step] раз (в 4 раза меньше точек).
 * Чистый Kotlin без Android, чтобы можно было проверить на компьютере.
 */
object BubbleFinder {

    class Result(
        val bubbles: List<Bubble>,
        /** Светлые области, обрезанные нижним краем экрана: их текст прочитаем после прокрутки */
        val cutAtBottom: List<Box>
    )

    fun find(grayFull: IntArray, wFull: Int, hFull: Int, maxCount: Int = 12, step: Int = 2): Result {
        // Уменьшенная картинка: среднее по квадрату step×step
        val w = wFull / step
        val h = hFull / step
        val gray: IntArray
        if (step == 1) {
            gray = grayFull
        } else {
            gray = IntArray(w * h)
            val area = step * step
            for (y in 0 until h) {
                val dst = y * w
                for (x in 0 until w) {
                    var sum = 0
                    val sx = x * step
                    for (dy in 0 until step) {
                        val row = (y * step + dy) * wFull + sx
                        for (dx in 0 until step) sum += grayFull[row + dx]
                    }
                    gray[dst + x] = sum / area
                }
            }
        }

        val n = w * h
        val light = BooleanArray(n) { gray[it] > 200 }
        val label = IntArray(n)
        val queue = IntArray(n)
        val found = ArrayList<Pair<Int, Bubble>>()
        val cut = ArrayList<Box>()
        var current = 0
        val minArea = (0.002 * n).toInt()
        val maxArea = (0.3 * n).toInt()
        val minW = 60 / step
        val minH = 40 / step

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
            val fullBox = Box(minX * step, minY * step, (maxX + 1) * step, (maxY + 1) * step)

            // Пузырь, обрезанный нижним краем (не во всю ширину — это не фон страницы)
            if (tBottom && !tLeft && !tRight && !tTop && fill >= 0.4 && bw < w * 0.9) {
                cut += fullBox
                continue
            }
            if (tLeft || tRight || tTop || tBottom) continue
            if (area > maxArea || bw < minW || bh < minH || fill < 0.45) continue

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
            // Доля светлого внутри фигуры (с буквами). У пузыря с текстом она высокая,
            // у светлого куска рисунка (волосы, лицо) внутри много тёмных линий.
            var filled = 0
            for (y in minY..maxY) {
                val r = y - minY
                for (x in minX..maxX) {
                    val c = x - minX
                    if (x >= rowMin[r] && x <= rowMax[r] && y >= colMin[c] && y <= colMax[c]) filled++
                }
            }
            val lightShare = area.toDouble() / filled.coerceAtLeast(1)
            val needed = if (area > 0.08 * n) 0.88 else 0.84
            if (lightShare < needed) continue

            found += area to Bubble(fullBox, step, minX, minY, rowMin, rowMax, colMin, colMax)
        }
        val bubbles = found.sortedByDescending { it.first }.take(maxCount).map { it.second }
        return Result(bubbles, cut)
    }
}
