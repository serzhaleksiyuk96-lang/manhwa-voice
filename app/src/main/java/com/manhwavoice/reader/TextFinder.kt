package com.manhwavoice.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Надпись, найденная по форме букв: несколько строк букв одного размера, стоящих друг под другом.
 * [inverse] — светлые буквы на тёмном фоне.
 */
class TextBlock(
    val box: Box,
    /** Типичная высота буквы, пикселей */
    val letterH: Int,
    val inverse: Boolean,
    val lines: Int,
    /** Примерное число букв */
    val letters: Int,
    /** Яркость фона вокруг букв (для светлых букв — в перевёрнутом виде) */
    val ring: Int,
    /** Похоже на текст само по себе (а не только как часть пузыря) */
    val standalone: Boolean,
    /** Рамки букв блока */
    val parts: List<Box> = emptyList()
) {
    /** Сколько отдельных пятен-букв (у обводки вокруг слов их меньше, чем букв) */
    val comps get() = parts.size
}

/**
 * Ищет текст на экране без OCR — по связным областям «чернил» (тёмных или, для светлого текста
 * на тёмном фоне, светлых пикселей): буквы → строки → блоки строк. Так находится и текст
 * вне пузырей (поверх рисунка, с обводкой), и текст, который Tesseract в режиме «весь экран»
 * пропускает. Потом каждый блок распознаётся отдельно, на чистой вырезке, где оставлены
 * только буквы. Чистый Kotlin без Android — проверяется на компьютере.
 */
object TextFinder {

    /** Порог «чернил» для обычного текста: темнее — буквы */
    const val DARK = 120
    /** Порог для светлых букв на тёмном фоне: светлее — буквы */
    const val LIGHT = 150

    private const val MIN_H = 8
    private const val MAX_H = 160
    /** Отладочный вывод отброшенных блоков (только для проверок на компьютере) */
    @JvmField var debug = false

    /** Мелкий текст увеличивается до такой высоты букв перед распознаванием */
    @JvmField var TARGET_H = 22
    /** «Полутёмное»: бледные штрихи букв (темнее этого) прирастают к буквам */
    private const val WEAK = 190
    /** Знак рядом с буквами оставляем, только если вокруг него светло (как у букв с обводкой) */
    private const val RING_KEEP = 185

    /** Крупнее — звуки и рисунок, а не реплики (крупные надписи вроде «ЖДЁТ ТОЛЬКО СМЕРТЬ» — ещё да) */
    private const val MAX_TEXT_H = 110
    /** Насколько светлым должен быть фон вокруг букв (у текста — пузырь или обводка) */
    private const val RING_MIN = 172

    class Result(val blocks: List<TextBlock>, /** рамки всех похожих на буквы пятен */ val letters: List<Box>)

    // ------------------------------------------------------------------ связные области

    /** Пятна «чернил»: рамка и площадь. */
    class Comps(val n: Int, val l: IntArray, val t: IntArray, val r: IntArray, val b: IntArray, val area: IntArray)

    /**
     * Связные области (8-связность) маски в прямоугольнике [x0,x1)×[y0,y1).
     * [labels] — рабочий массив размером не меньше w*h (содержимое затирается);
     * в нём остаются номера областей (0 — фон) для точек прямоугольника.
     */
    fun components(mask: ByteArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, labels: IntArray): Comps {
        var parent = IntArray(1024)
        var next = 1
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int): Int {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return ra
            // меньший номер — корень (нужно для быстрой «расплющивания» ниже)
            return if (ra < rb) { parent[rb] = ra; ra } else { parent[ra] = rb; rb }
        }
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val i = row + x
                if (mask[i].toInt() == 0) { labels[i] = 0; continue }
                var lab = 0
                // соседи слева, слева-сверху, сверху, справа-сверху
                if (x > x0) { val q = labels[i - 1]; if (q != 0) lab = q }
                if (y > y0) {
                    val up = i - w
                    if (x > x0) { val q = labels[up - 1]; if (q != 0) lab = if (lab == 0) q else union(lab, q) }
                    run { val q = labels[up]; if (q != 0) lab = if (lab == 0) q else union(lab, q) }
                    if (x < x1 - 1) { val q = labels[up + 1]; if (q != 0) lab = if (lab == 0) q else union(lab, q) }
                }
                if (lab == 0) {
                    if (next >= parent.size) parent = parent.copyOf(parent.size * 2)
                    parent[next] = next
                    lab = next++
                }
                labels[i] = lab
            }
        }
        // Корни → плотные номера
        val id = IntArray(next)
        var n = 0
        for (k in 1 until next) {
            val p = parent[k]
            if (p == k) { id[k] = ++n } else {
                // parent[k] < k и уже «расплющен»
                parent[k] = parent[p]
                id[k] = id[parent[k]]
            }
        }
        val l = IntArray(n + 1) { Int.MAX_VALUE }
        val t = IntArray(n + 1) { Int.MAX_VALUE }
        val r = IntArray(n + 1) { -1 }
        val b = IntArray(n + 1) { -1 }
        val area = IntArray(n + 1)
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val i = row + x
                val q = labels[i]
                if (q == 0) continue
                val c = id[q]
                labels[i] = c
                if (x < l[c]) l[c] = x
                if (x > r[c]) r[c] = x
                if (y < t[c]) t[c] = y
                if (y > b[c]) b[c] = y
                area[c]++
            }
        }
        return Comps(n, l, t, r, b, area)
    }

    // ------------------------------------------------------------------ поиск блоков

    private class Letter(val l: Int, val t: Int, val r: Int, val b: Int, val ring: Int) {
        val w get() = r - l + 1
        val h get() = b - t + 1
    }

    private class Line(val items: List<Letter>) {
        val l = items.minOf { it.l }
        val t = items.minOf { it.t }
        val r = items.maxOf { it.r }
        val b = items.maxOf { it.b }
        val hm: Int = items.map { it.h }.sorted()[items.size / 2]
        /** Примерное число букв: слипшиеся буквы — одно широкое пятно */
        val letters = items.sumOf { max(1, (it.w / (0.8f * hm)).roundToInt()) }
        val aligned: Boolean
        init {
            aligned = when (items.size) {
                1 -> false
                2 -> min(abs(items[0].t - items[1].t), abs(items[0].b - items[1].b)) <= 0.15f * hm
                else -> {
                    val mt = mad(items.map { it.t })
                    val mb = mad(items.map { it.b })
                    min(mt, mb) <= 0.12f * hm
                }
            }
        }
        /** Строка, которая сама по себе похожа на текст */
        val strong get() = aligned && letters >= 2
    }

    private fun mad(v: List<Int>): Float {
        val s = v.sorted()
        val med = s[s.size / 2]
        val d = s.map { abs(it - med) }.sorted()
        return d[d.size / 2].toFloat()
    }

    /**
     * Ищет блоки текста в полосе экрана [y0, y1). [skip] — уже найденное другим способом
     * или закрытое (кнопка, панели сайта): там не ищем.
     */
    fun find(gray: IntArray, w: Int, h: Int, y0: Int, y1: Int, skip: List<Box> = emptyList()): Result {
        val mask = ByteArray(w * h)
        val labels = IntArray(w * h)
        val blocks = ArrayList<TextBlock>()
        val letters = ArrayList<Box>()
        for (inverse in booleanArrayOf(false, true)) {
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    val g = gray[row + x]
                    mask[row + x] = if (if (inverse) g > LIGHT else g < DARK) 1 else 0
                }
            }
            for (s in skip) {
                val t = max(y0, s.top); val b = min(y1, s.bottom)
                val l = max(0, s.left); val r = min(w, s.right)
                for (y in t until b) java.util.Arrays.fill(mask, y * w + l, y * w + r, 0)
            }
            val c = components(mask, w, 0, y0, w, y1, labels)
            val found = findIn(gray, mask, w, c, inverse)
            blocks += found.first
            letters += found.second
        }
        return Result(dropNested(blocks), letters)
    }

    private fun findIn(gray: IntArray, mask: ByteArray, w: Int, c: Comps, inverse: Boolean): Pair<List<TextBlock>, List<Box>> {
        // 1) пятна, похожие на буквы
        val cand = ArrayList<Letter>()
        val all = ArrayList<Box>()
        for (k in 1..c.n) {
            val cw = c.r[k] - c.l[k] + 1
            val ch = c.b[k] - c.t[k] + 1
            if (ch < MIN_H || ch > MAX_H) continue
            if (cw > 15 * ch) continue
            val fill = c.area[k].toFloat() / (cw * ch)
            // сплошное пятно — не буква; но узкая сплошная палочка — часть «Ы», «!», «Ъ»
            if (fill < 0.08f || (fill > 0.92f && cw > 0.35f * ch)) continue
            if (cw <= 2 && ch > 6 * cw) continue
            val ring = ringBrightness(gray, mask, w, c.l[k], c.t[k], c.r[k], c.b[k], inverse)
            cand += Letter(c.l[k], c.t[k], c.r[k], c.b[k], ring)
            all += Box(c.l[k], c.t[k], c.r[k] + 1, c.b[k] + 1)
        }
        if (cand.isEmpty()) return emptyList<TextBlock>() to all

        // Пятно, внутри которого целиком лежит другое крупное пятно, — не буква, а обводка вокруг
        // букв (у белых букв с чёрной каймой и белым ореолом) или рамка
        cand.sortBy { it.l }
        val container = BooleanArray(cand.size)
        for (i in cand.indices) {
            val a = cand[i]
            var j = i + 1
            while (j < cand.size && cand[j].l <= a.r) {
                val b = cand[j]
                if (b.t >= a.t && b.b <= a.b && b.r <= a.r && b.h >= 0.4f * a.h && b.h < a.h) { container[i] = true; break }
                j++
            }
        }
        if (container.any { it }) {
            val keep = cand.filterIndexed { i, _ -> !container[i] }
            cand.clear()
            cand.addAll(keep)
        }
        if (cand.isEmpty()) return emptyList<TextBlock>() to all

        // 2) буквы → строки
        cand.sortBy { it.l }
        val p1 = IntArray(cand.size) { it }
        fun f1(a: Int): Int { var x = a; while (p1[x] != x) { p1[x] = p1[p1[x]]; x = p1[x] }; return x }
        for (i in cand.indices) {
            val a = cand[i]
            val reach = a.r + (1.3f * 1.8f * a.h).toInt()
            var j = i + 1
            while (j < cand.size && cand[j].l <= reach) {
                val b = cand[j]
                val hMax = max(a.h, b.h); val hMin = min(a.h, b.h)
                if (hMax <= 1.8f * hMin) {
                    val ov = min(a.b, b.b) - max(a.t, b.t) + 1
                    val gap = max(b.l - a.r, a.l - b.r)
                    if (ov >= 0.55f * hMin && gap <= 1.3f * hMax) {
                        val ra = f1(i); val rb = f1(j)
                        if (ra != rb) p1[ra] = rb
                    }
                }
                j++
            }
        }
        val lines = cand.indices.groupBy { f1(it) }.values.map { idx -> Line(idx.map { cand[it] }) }

        // 3) строки → блоки (строки одного кегля друг под другом)
        val p2 = IntArray(lines.size) { it }
        fun f2(a: Int): Int { var x = a; while (p2[x] != x) { p2[x] = p2[p2[x]]; x = p2[x] }; return x }
        val order = lines.indices.sortedBy { lines[it].t }
        for (ii in order.indices) {
            val a = lines[order[ii]]
            for (jj in ii + 1 until order.size) {
                val b = lines[order[jj]]
                val hMax = max(a.hm, b.hm)
                if (b.t - a.b > 2.2f * hMax) break        // дальше только ниже
                if (hMax > 1.35f * min(a.hm, b.hm)) continue
                val vgap = max(a.t, b.t) - min(a.b, b.b) - 1
                if (vgap > 1.5f * hMax) continue
                if (vgap < -0.5f * min(a.hm, b.hm)) continue   // одна высота — это не строки одного блока
                val hov = min(a.r, b.r) - max(a.l, b.l)
                if (hov < -0.3f * hMax) continue
                // строки одного абзаца выровнены: по центру (пузырь), по левому или правому краю
                val wMax = max(a.r - a.l, b.r - b.l)
                val centered = abs((a.l + a.r) - (b.l + b.r)) / 2 <= 0.25f * wMax + 0.5f * hMax
                if (!centered && abs(a.l - b.l) > hMax && abs(a.r - b.r) > hMax) continue
                // хотя бы одна из строк должна быть похожа на текст
                if (!a.strong && !b.strong) continue
                val ra = f2(order[ii]); val rb = f2(order[jj])
                if (ra != rb) p2[ra] = rb
            }
        }
        val out = ArrayList<TextBlock>()
        for (g in lines.indices.groupBy { f2(it) }.values) {
            val ls = g.map { lines[it] }
            val dbgBox = Box(ls.minOf { it.l }, ls.minOf { it.t }, ls.maxOf { it.r } + 1, ls.maxOf { it.b } + 1)
            fun reject(why: String) { if (debug && ls.sumOf { it.letters } >= 3) println("  отброшен ${if (inverse) "светлый" else "обычный"} $dbgBox строк ${ls.size}: $why") }
            if (ls.none { it.strong }) { reject("нет ровной строки"); continue }
            val nLetters = ls.sumOf { it.letters }
            if (nLetters < 2) continue
            val all2 = ls.flatMap { it.items }
            val ring = all2.map { it.ring }.sorted()[all2.size / 2]
            if (ring < RING_MIN) { reject("тёмный фон $ring"); continue }
            val hm = all2.map { it.h }.sorted()[all2.size / 2]
            if (hm > MAX_TEXT_H) { reject("крупно $hm"); continue }
            val box = Box(ls.minOf { it.l }, ls.minOf { it.t }, ls.maxOf { it.r } + 1, ls.maxOf { it.b } + 1)
            if (box.width < 1.2f * hm) { reject("узко"); continue }
            // Буквы одного размера: у мусора с рисунка размеры пятен скачут
            val same = all2.count { abs(it.h - hm) <= 0.25f * hm }.toFloat() / all2.size
            if (same < 0.6f) { reject("разный размер $same"); continue }
            var standalone = true
            if (ls.size == 1 && (nLetters < 4 || same < 0.75f || ring < RING_MIN + 13)) standalone = false
            if (inverse && ((ls.size < 2 && nLetters < 6) || outerDark(gray, w, box, hm) < 0.25f)) standalone = false
            out += TextBlock(box, hm, inverse, ls.size, nLetters, ring, standalone,
                all2.map { Box(it.l, it.t, it.r + 1, it.b + 1) })
        }
        return out to all
    }

    /** Доля тёмного вокруг блока (на расстоянии полбуквы — буква): светлый текст пишут на тёмном. */
    private fun outerDark(gray: IntArray, w: Int, box: Box, hm: Int): Float {
        val h = gray.size / w
        val m1 = (0.5f * hm).toInt(); val m2 = (0.9f * hm).toInt() + 1
        val x0 = max(0, box.left - m2); val x1 = min(w, box.right + m2)
        val y0 = max(0, box.top - m2); val y1 = min(h, box.bottom + m2)
        var dark = 0
        var all = 0
        var y = y0
        while (y < y1) {
            val inY = y >= box.top - m1 && y < box.bottom + m1
            var x = x0
            while (x < x1) {
                if (!(inY && x >= box.left - m1 && x < box.right + m1)) {
                    all++
                    if (gray[y * w + x] < 100) dark++
                }
                x += 2
            }
            y += 2
        }
        return if (all == 0) 0f else dark.toFloat() / all
    }

    /** Средняя яркость фона (не «чернил») вокруг пятна: у букв — белый пузырь или обводка. */
    private fun ringBrightness(gray: IntArray, mask: ByteArray, w: Int, l: Int, t: Int, r: Int, b: Int, inverse: Boolean): Int {
        val h = gray.size / w
        val x0 = max(0, l - 2); val x1 = min(w - 1, r + 2)
        val y0 = max(0, t - 2); val y1 = min(h - 1, b + 2)
        var sum = 0L
        var cnt = 0
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val i = row + x
                if (mask[i].toInt() != 0) continue
                sum += if (inverse) 255 - gray[i] else gray[i]
                cnt++
            }
        }
        return if (cnt == 0) 0 else (sum / cnt).toInt()
    }

    /**
     * Из пересекающихся блоков (обычный и светлый проход) оставляем тот, где больше отдельных
     * букв: у букв с обводкой один проход находит сами буквы, другой — обводку, слитую в слова.
     */
    private fun dropNested(blocks: List<TextBlock>): List<TextBlock> {
        // настоящие буквы — каждая отдельным пятном; обводка вокруг них сливается в пятна-слова
        val sorted = blocks.sortedWith(compareByDescending<TextBlock> { it.standalone }.thenByDescending { it.comps }.thenByDescending { it.ring })
        val kept = ArrayList<TextBlock>()
        for (b in sorted) {
            val clash = kept.any { k ->
                val ix = min(k.box.right, b.box.right) - max(k.box.left, b.box.left)
                val iy = min(k.box.bottom, b.box.bottom) - max(k.box.top, b.box.top)
                ix > 0 && iy > 0 && ix.toLong() * iy >= 0.5 * b.box.width.toLong() * b.box.height
            }
            if (!clash) kept += b
        }
        return kept
    }

    // ------------------------------------------------------------------ чистая вырезка для OCR

    /**
     * Вырезка для распознавания: в рамке [region] (координаты рабочего масштаба) оставлены
     * только буквы — пятна не крупнее [maxH] и (если [dropTouching]) не выходящие за край рамки;
     * всё остальное белое. [inside] — дополнительная маска (форма пузыря). Светлый текст
     * переворачивается в тёмный. Пиксели берутся из [src] — по возможности из исходного снимка
     * в полном разрешении. Мелкий текст увеличивается до высоты букв ~[targetH]: так Tesseract
     * не теряет строки.
     */
    fun cleanCrop(
        src: GraySrc,
        region: Box, inverse: Boolean, maxH: Int, letterH: Int,
        dropTouching: Boolean,
        inside: ((Int, Int) -> Boolean)? = null,
        pad: Int = 12,
        targetH: Int = TARGET_H,
        /** Рамки найденных букв: тогда остаются только они и мелкие знаки рядом (точки, запятые) */
        letters: List<Box>? = null
    ): Crop {
        val s = src.scale
        val l = max(0, (region.left * s).toInt()); val t = max(0, (region.top * s).toInt())
        val r = min(src.w, kotlin.math.ceil(region.right * s).toInt())
        val b = min(src.h, kotlin.math.ceil(region.bottom * s).toInt())
        val cw = max(1, r - l); val ch = max(1, b - t)
        val sub = src.copy(l, t, l + cw, t + ch)
        val mask = ByteArray(cw * ch)
        for (y in 0 until ch) {
            val wy = ((y + t) / s).toInt()
            for (x in 0 until cw) {
                val g = sub[y * cw + x]
                val ink = if (inverse) g > LIGHT else g < DARK
                if (ink && (inside == null || inside(((x + l) / s).toInt(), wy))) mask[y * cw + x] = 1
            }
        }
        val labels = IntArray(cw * ch)
        val c = components(mask, cw, 0, 0, cw, ch, labels)
        val keep = BooleanArray(c.n + 1)
        val edgeL = l > 0; val edgeT = t > 0
        val edgeR = l + cw < src.w; val edgeB = t + ch < src.h
        val maxHs = maxH * s
        val maxWs = 15 * max(letterH, 8) * s
        for (k in 1..c.n) {
            val kh = c.b[k] - c.t[k] + 1
            val kw = c.r[k] - c.l[k] + 1
            if (kh > maxHs || kw > maxWs) continue
            if (dropTouching && ((edgeL && c.l[k] == 0) || (edgeT && c.t[k] == 0) ||
                    (edgeR && c.r[k] == cw - 1) || (edgeB && c.b[k] == ch - 1))) continue
            if (letters != null && !nearLetters(c, k, l, t, s, letters, letterH)) continue
            keep[k] = true
        }
        // Буквы на рисунке: у настоящих букв и знаков вокруг светлая обводка, у кусочков рисунка — нет
        if (letters != null) {
            for (k in 1..c.n) {
                if (!keep[k]) continue
                if (ringOf(sub, mask, cw, ch, c.l[k], c.t[k], c.r[k], c.b[k], inverse) < RING_KEEP) keep[k] = false
            }
        }
        // Бледные штрихи букв (светлее порога): наращиваем буквы по «полутёмным» точкам,
        // но не дальше d от них — так штрих не теряется, а серый рисунок рядом не прилипает
        val d = max(2, (0.2f * letterH * s).roundToInt())
        val zone = dilate(labels, keep, cw, ch, d)
        val grown = BooleanArray(cw * ch)
        val queue = IntArray(cw * ch)
        var qh = 0
        var qt = 0
        for (i in 0 until cw * ch) {
            val q = labels[i]
            if (q != 0 && keep[q]) { grown[i] = true; queue[qt++] = i }
        }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % cw
            val y = i / cw
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= ch) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= cw) continue
                    val j = yy * cw + xx
                    if (grown[j] || !zone[j]) continue
                    val q = labels[j]
                    if (q != 0 && !keep[q]) continue
                    val g = sub[j]
                    if (if (inverse) g <= 255 - WEAK else g >= WEAK) continue
                    if (inside != null && !inside(((xx + l) / s).toInt(), ((yy + t) / s).toInt())) continue
                    grown[j] = true
                    queue[qt++] = j
                }
            }
        }
        // Картинка: буквы и их сглаженная кайма — как есть, остальное белое
        val outW = cw + 2 * pad
        val outH = ch + 2 * pad
        val out = ByteArray(outW * outH) { -1 }
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val i = y * cw + x
                var on = grown[i]
                if (!on) {
                    val q = labels[i]
                    if (q != 0) continue      // выброшенное пятно
                    loop@ for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= ch) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= cw) continue
                            if (grown[yy * cw + xx]) { on = true; break@loop }
                        }
                    }
                    if (on && inside != null && !inside(((x + l) / s).toInt(), ((y + t) / s).toInt())) on = false
                }
                if (!on) continue
                val g = sub[i]
                out[(y + pad) * outW + x + pad] = (if (inverse) 255 - g else g).toByte()
            }
        }
        val img = GrayImage(outW, outH, out)
        val hs = letterH * s
        val up = if (hs > 0 && hs < targetH) min(2f, targetH / hs) else 1f
        return if (up >= 1.2f) Crop(upscale(img, up), l - pad, t - pad, up, s)
        else Crop(img, l - pad, t - pad, 1f, s)
    }

    /** Вырезка: картинка, её начало в пикселях источника, увеличение и масштаб источника. */
    class Crop(val img: GrayImage, val x0: Int, val y0: Int, val scale: Float, val srcScale: Float = 1f) {
        /** Рамку слова из вырезки — в координаты экрана (рабочий масштаб). */
        fun toScreen(bx: Box): Box = Box(
            ((x0 + bx.left / scale) / srcScale).toInt(), ((y0 + bx.top / scale) / srcScale).toInt(),
            ((x0 + bx.right / scale) / srcScale).toInt(), ((y0 + bx.bottom / scale) / srcScale).toInt()
        )
    }

    /**
     * Пятно — одна из найденных букв (в том числе палочка «Ы», «!») или мелкий знак рядом с ними
     * (точка, запятая, дефис, «й»). Всё прочее в рамке — рисунок или обводка вокруг букв.
     */
    private fun nearLetters(c: Comps, k: Int, l: Int, t: Int, s: Float, letters: List<Box>, letterH: Int): Boolean {
        val bl = ((c.l[k] + l) / s).toInt(); val bt = ((c.t[k] + t) / s).toInt()
        val br = ((c.r[k] + l + 1) / s).toInt(); val bb = ((c.b[k] + t + 1) / s).toInt()
        val small = (bb - bt) < 0.7f * letterH && (br - bl) < 1.5f * letterH
        val m = if (small) (0.6f * letterH).toInt() + 2 else 2
        for (b in letters) {
            if (br + m <= b.left || b.right + m <= bl || bb + m <= b.top || b.bottom + m <= bt) continue
            if (small) return true
            // крупное пятно: большая часть — внутри рамки одной буквы
            val ix = min(br, b.right + 2) - max(bl, b.left - 2)
            val iy = min(bb, b.bottom + 2) - max(bt, b.top - 2)
            if (ix > 0 && iy > 0 && ix.toLong() * iy * 10 >= 7L * (br - bl) * (bb - bt)) return true
        }
        return false
    }

    /** Яркость фона вокруг пятна в вырезке (для светлых букв — в перевёрнутом виде). */
    private fun ringOf(sub: IntArray, mask: ByteArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, inverse: Boolean): Int {
        val x0 = max(0, l - 2); val x1 = min(w - 1, r + 2)
        val y0 = max(0, t - 2); val y1 = min(h - 1, b + 2)
        var sum = 0L
        var cnt = 0
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            if (mask[i].toInt() != 0) continue
            sum += if (inverse) 255 - sub[i] else sub[i]
            cnt++
        }
        return if (cnt == 0) 255 else (sum / cnt).toInt()
    }

    /** Точки не дальше [d] (по горизонтали и вертикали) от оставленных пятен. */
    private fun dilate(labels: IntArray, keep: BooleanArray, w: Int, h: Int, d: Int): BooleanArray {
        // по строкам: есть ли буква в окне ±d
        val horiz = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var last = -1_000_000
            for (x in 0 until w) {
                val q = labels[row + x]
                if (q != 0 && keep[q]) last = x
                if (x - last <= d) horiz[row + x] = true
            }
            last = 1_000_000
            for (x in w - 1 downTo 0) {
                val q = labels[row + x]
                if (q != 0 && keep[q]) last = x
                if (last - x <= d) horiz[row + x] = true
            }
        }
        // по столбцам
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -1_000_000
            for (y in 0 until h) {
                if (horiz[y * w + x]) last = y
                if (y - last <= d) out[y * w + x] = true
            }
            last = 1_000_000
            for (y in h - 1 downTo 0) {
                if (horiz[y * w + x]) last = y
                if (last - y <= d) out[y * w + x] = true
            }
        }
        return out
    }

    /** Билинейное увеличение. */
    fun upscale(src: GrayImage, s: Float): GrayImage {
        val nw = (src.w * s).roundToInt()
        val nh = (src.h * s).roundToInt()
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) / s - 0.5f).coerceIn(0f, (src.h - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(src.h - 1, y0 + 1); val ay = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) / s - 0.5f).coerceIn(0f, (src.w - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(src.w - 1, x0 + 1); val ax = fx - x0
                val p00 = src.px[y0 * src.w + x0].toInt() and 255
                val p01 = src.px[y0 * src.w + x1].toInt() and 255
                val p10 = src.px[y1 * src.w + x0].toInt() and 255
                val p11 = src.px[y1 * src.w + x1].toInt() and 255
                val v = (p00 * (1 - ax) + p01 * ax) * (1 - ay) + (p10 * (1 - ax) + p11 * ax) * ay
                out[y * nw + x] = v.roundToInt().toByte()
            }
        }
        return GrayImage(nw, nh, out)
    }
}

/**
 * Откуда брать пиксели для вырезок: сам экран в рабочем масштабе или исходный снимок
 * в полном разрешении ([scale] — во сколько раз он крупнее рабочего).
 */
class GraySrc private constructor(
    val w: Int, val h: Int, val scale: Float,
    private val ints: IntArray?, private val bytes: ByteArray?
) {
    /** Копия прямоугольника [l,r)×[t,b) построчно. */
    fun copy(l: Int, t: Int, r: Int, b: Int): IntArray {
        val cw = r - l
        val ch = b - t
        val out = IntArray(cw * ch)
        if (ints != null) {
            for (y in 0 until ch) System.arraycopy(ints, (y + t) * w + l, out, y * cw, cw)
        } else {
            val px = bytes!!
            for (y in 0 until ch) {
                val so = (y + t) * w + l
                for (x in 0 until cw) out[y * cw + x] = px[so + x].toInt() and 255
            }
        }
        return out
    }

    companion object {
        fun of(gray: IntArray, w: Int, h: Int) = GraySrc(w, h, 1f, gray, null)
        fun hiRes(px: ByteArray, w: Int, h: Int, scale: Float) = GraySrc(w, h, scale, null, px)
    }
}
