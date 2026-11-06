package com.manhwavoice.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Картинка в оттенках серого, по байту на пиксель, построчно. */
class GrayImage(val w: Int, val h: Int, val px: ByteArray)

/** Распознавание картинки одним экземпляром Tesseract (режим разметки задан при создании). */
fun interface OcrFn {
    fun read(img: GrayImage): List<OcrWord>
}

/**
 * Что прочитать на экране: пузырь (со своей формой) или надпись, найденная по буквам
 * (вне пузыря, на рисунке, светлыми буквами на тёмном).
 */
class TextItem(
    val box: Box,
    val bubble: Bubble?,
    /** Типичная высота буквы */
    val letterH: Int,
    val inverse: Boolean,
    /** Рамки букв надписи (для надписи вне пузыря) */
    val letters: List<Box>? = null,
    /**
     * Скорее всего это текст (пузырь с буквами, несколько ровных строк). Сомнительное — звуки,
     * куски рисунка — распознаётся во вторую очередь и не задерживает чтение настоящих реплик.
     */
    val likely: Boolean = true
)

/** Подготовленный снимок экрана. */
class Prepared(
    val gray: IntArray,
    val w: Int,
    val h: Int,
    /** Видимая часть страницы: ниже строки состояния и панелей сайта, выше нижних панелей */
    val regionTop: Int,
    val regionBottom: Int,
    /** На экране только фон (промежуток между кадрами) */
    val blank: Boolean,
    /** Что читать — уже в порядке чтения; только целиком видимое */
    val items: List<TextItem>,
    /** Верх самой высокой надписи, обрезанной снизу (прочитается после прокрутки), или null */
    val cutTop: Int?,
    /** Откуда брать пиксели для распознавания мелкого текста (по возможности — исходный снимок в полном разрешении) */
    val src: GraySrc = GraySrc.of(gray, w, h)
) {
    /** Рабочая копия экрана: для текста обычного размера её хватает, и распознаётся она быстрее */
    val lo: GraySrc = GraySrc.of(gray, w, h)
}

/**
 * Узнаёт надписи, уже распознанные на прошлых экранах: после прокрутки это те же картинки,
 * просто выше. Сравнивается форма букв (маска «чернил» в половинном масштабе) с небольшим
 * сдвигом — так разные пузыри одного размера не путаются, а тот же самый узнаётся.
 */
class ItemCache(private val capacity: Int = 60) {
    class Hit(val phrase: Phrase?)

    class Sig(val w: Int, val h: Int, val inverse: Boolean, val ink: ByteArray, val inkCount: Int)

    private class Entry(val sig: Sig, val phrase: Phrase?)

    private val items = ArrayList<Entry>()

    fun signature(p: Prepared, it: TextItem): Sig {
        val b = it.box
        val sw = max(1, b.width / 2)
        val sh = max(1, b.height / 2)
        val ink = ByteArray(sw * sh)
        var n = 0
        for (y in 0 until sh) {
            val fy = b.top + y * 2
            if (fy >= p.h) break
            for (x in 0 until sw) {
                val fx = b.left + x * 2
                if (fx >= p.w) break
                if (it.bubble != null && !it.bubble.inside(fx, fy)) continue
                val g = p.gray[fy * p.w + fx]
                if (if (it.inverse) g > TextFinder.LIGHT else g < TextFinder.DARK) {
                    ink[y * sw + x] = 1
                    n++
                }
            }
        }
        return Sig(sw, sh, it.inverse, ink, n)
    }

    @Synchronized
    fun find(it: TextItem, s: Sig): Hit? {
        if (s.inkCount < 8) return null
        for (e in items.asReversed()) {
            val o = e.sig
            if (o.inverse != s.inverse) continue
            if (abs(o.w - s.w) > 3 || abs(o.h - s.h) > 3) continue
            if (o.inkCount * 4 < s.inkCount * 3 || s.inkCount * 4 < o.inkCount * 3) continue
            if (iou(o, s) >= 0.72) return Hit(e.phrase?.copy(box = it.box))
        }
        return null
    }

    /** Лучшее совпадение масок при сдвиге до 2 точек в каждую сторону. */
    private fun iou(a: Sig, b: Sig): Double {
        var best = 0.0
        for (dy in -2..2) for (dx in -2..2) {
            var inter = 0
            for (y in 0 until a.h) {
                val by = y + dy
                if (by < 0 || by >= b.h) continue
                val ra = y * a.w
                val rb = by * b.w
                for (x in 0 until a.w) {
                    if (a.ink[ra + x].toInt() == 0) continue
                    val bx = x + dx
                    if (bx < 0 || bx >= b.w) continue
                    if (b.ink[rb + bx].toInt() != 0) inter++
                }
            }
            val u = a.inkCount + b.inkCount - inter
            if (u > 0) best = max(best, inter.toDouble() / u)
        }
        return best
    }

    @Synchronized
    fun put(s: Sig, phrase: Phrase?) {
        if (s.inkCount < 8) return
        items += Entry(s, phrase)
        if (items.size > capacity) items.removeAt(0)
    }

    @Synchronized
    fun clear() = items.clear()
}

/**
 * Весь путь от снимка экрана до реплик, без зависимостей от Android:
 *  1) пузыри ([BubbleFinder]) и надписи по форме букв ([TextFinder]) → что читать, в порядке чтения;
 *  2) каждая надпись — на чистой вырезке (только буквы) быстрой моделью;
 *  3) сомнительное по словарю — ещё раз точной моделью.
 */
object ScreenText {

    fun prepare(
        gray: IntArray, w: Int, h: Int,
        regionTop: Int, regionBottom: Int,
        skipTopCut: Boolean, skipBottomCut: Boolean,
        rtl: Boolean,
        src: GraySrc? = null
    ): Prepared {
        val source = src ?: GraySrc.of(gray, w, h)
        val n = w * h
        val hist = IntArray(256)
        for (g in gray) hist[g]++
        // Пустой экран: почти все пиксели одного цвета
        var mode = 0
        for (i in 1 until 256) if (hist[i] > hist[mode]) mode = i
        var different = 0
        for (i in 0 until 256) if (abs(i - mode) > 40) different += hist[i]
        if (different < 250 || n == 0) return Prepared(gray, w, h, regionTop, regionBottom, true, emptyList(), null, source)

        val found = BubbleFinder.find(gray, w, h)
        val text = TextFinder.find(gray, w, h, regionTop, regionBottom)
        val items = assemble(found.bubbles, text)

        // Обрезанное краями видимой части не читаем: верх уже прочитан, низ — после прокрутки
        val kept = ArrayList<TextItem>()
        var cutTop: Int? = null
        for (it in items) {
            val margin = if (it.bubble != null) 2 else (1.2f * it.letterH).toInt() + 2
            if (skipTopCut && it.box.top < regionTop + margin) continue
            val cutBottom = it.box.bottom > regionBottom - margin ||
                found.cutAtBottom.any { c -> TextLayout.intersects(c, it.box) }
            if (skipBottomCut && cutBottom) {
                cutTop = min(cutTop ?: Int.MAX_VALUE, it.box.top)
                continue
            }
            kept += it
        }
        return Prepared(gray, w, h, regionTop, regionBottom, false, ReadingOrder.sort(kept, gray, w, h, rtl), cutTop, source)
    }

    /** Надписи внутри пузыря читаются вместе с ним; пузыри без букв (кусок рисунка) пропускаются. */
    private fun assemble(bubbles: List<Bubble>, text: TextFinder.Result): List<TextItem> {
        val items = ArrayList<TextItem>()
        val used = BooleanArray(text.blocks.size)
        for (b in bubbles) {
            val inside = text.blocks.indices.filter { i ->
                val bl = text.blocks[i]
                val cx = (bl.box.left + bl.box.right) / 2
                val cy = (bl.box.top + bl.box.bottom) / 2
                !used[i] && !bl.inverse && cx >= b.box.left && cx < b.box.right && cy >= b.box.top && cy < b.box.bottom &&
                    b.inside(cx, cy)
            }
            if (inside.isNotEmpty()) {
                inside.forEach { used[it] = true }
                // Два соприкасающихся пузыря сливаются в одну белую область: столбцы текста рядом —
                // это разные реплики, каждая читается отдельно
                val blocks = inside.map { text.blocks[it] }
                if (columns(blocks) > 1) {
                    for (bl in blocks) items += TextItem(bl.box, null, bl.letterH, false, bl.parts, likely = true)
                    continue
                }
                val hs = inside.flatMap { i -> List(text.blocks[i].letters) { text.blocks[i].letterH } }.sorted()
                items += TextItem(b.box, b, hs[hs.size / 2], false)
                continue
            }
            // Буквы есть, но в строки не сложились (редкий шрифт) — всё равно прочитать
            val letters = text.letters.filter { l ->
                val cx = (l.left + l.right) / 2
                val cy = (l.top + l.bottom) / 2
                cx >= b.box.left && cx < b.box.right && cy >= b.box.top && cy < b.box.bottom && b.inside(cx, cy) &&
                    l.height < b.box.height / 2
            }
            if (letters.size >= 4) {
                val hs = letters.map { it.height }.sorted()
                items += TextItem(b.box, b, hs[hs.size / 2], false, likely = false)
            }
        }
        for (i in text.blocks.indices) {
            if (used[i]) continue
            val bl = text.blocks[i]
            if (bl.standalone) items += TextItem(bl.box, null, bl.letterH, bl.inverse, bl.parts, likelyText(bl))
        }
        return items
    }

    /**
     * Сколько столбцов текста в области: блоки, разнесённые по горизонтали больше чем на полторы
     * буквы, и оба в несколько строк — это разные пузыри (одна строка, разорванная широким
     * пробелом, столбцами не считается).
     */
    private fun columns(blocks: List<TextBlock>): Int {
        val parent = IntArray(blocks.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            val a = blocks[i]; val b = blocks[j]
            val gap = max(a.box.left, b.box.left) - min(a.box.right, b.box.right)
            val apart = gap > 1.5f * max(a.letterH, b.letterH) && a.lines >= 2 && b.lines >= 2
            if (!apart) parent[find(i)] = find(j)
        }
        return blocks.indices.map { find(it) }.toSet().size
    }

    /** Надпись вне пузыря, очень похожая на текст: несколько строк или длинная ровная строка. */
    private fun likelyText(b: TextBlock): Boolean {
        if (b.letterH > 45) return false
        return if (b.inverse) b.lines >= 2 && b.letters >= 8
        else (b.lines >= 2 && b.letters >= 6) || (b.letters >= 6 && b.ring >= 200)
    }

    // ---------------- Вырезка и распознавание ----------------

    /** Чистая вырезка надписи: только буквы, всё остальное белое; мелкий текст увеличен. */
    fun crop(p: Prepared, it: TextItem): TextFinder.Crop {
        val b = it.bubble
        return if (b != null) {
            TextFinder.cleanCrop(
                source(p, it), it.box, false, max((2.5f * it.letterH).toInt(), it.letterH + 12), it.letterH,
                dropTouching = false, inside = { x, y -> b.inside(x, y) }
            )
        } else {
            val m = max(6, (0.6f * it.letterH).toInt())
            val region = Box(it.box.left - m, max(p.regionTop, it.box.top - m), it.box.right + m, min(p.regionBottom, it.box.bottom + m))
            TextFinder.cleanCrop(
                source(p, it), region, it.inverse, max((1.6f * it.letterH).toInt(), it.letterH + 4), it.letterH,
                dropTouching = true, letters = it.letters
            )
        }
    }

    /** Мелкий текст — из снимка в полном разрешении, обычный — из рабочей копии (быстрее). */
    private fun source(p: Prepared, it: TextItem): GraySrc =
        if (it.letterH < HIRES_BELOW) p.src else p.lo

    /** Буквы ниже этого (в рабочем масштабе) берутся из снимка в полном разрешении */
    private const val HIRES_BELOW = 18

    /** Результат первого (быстрого) прочтения. */
    class ItemRead(
        val item: TextItem, val crop: TextFinder.Crop, val words: List<OcrWord>,
        val checked: TextLayout.Checked, val textLike: Boolean
    ) {
        /**
         * Перечитать точной моделью: результат сомнительный, но похоже, что там текст.
         * Для надписи вне пузыря нужно больше уверенности, что это текст, а не рисунок.
         */
        val needsSecondPass get() = checked.doubtful &&
            (if (item.bubble != null) checked.phrase != null || textLike else TextLayout.hasRealWords(words))
    }

    private fun read(ocr: OcrFn, c: TextFinder.Crop): List<OcrWord> =
        ocr.read(c.img).map { it.copy(box = c.toScreen(it.box)) }

    /** Первый проход: быстрой моделью. */
    fun firstPass(p: Prepared, it: TextItem, fast: OcrFn): ItemRead {
        val c = crop(p, it)
        val words = read(fast, c)
        return ItemRead(it, c, words, TextLayout.blockPhraseChecked(words, it.box), TextLayout.looksLikeText(words))
    }

    /**
     * Второй проход для сомнительного: точная модель. Слова двух моделей объединяются по
     * месту: из каждой пары берётся то, что есть в словаре и прочитано увереннее.
     */
    fun secondPass(r: ItemRead, best: OcrFn): Phrase? {
        // слова, разорванные переносом, сначала склеиваются в каждом прочтении отдельно:
        // половинки по отдельности в словаре не найти, и выбор между моделями был бы случайным
        val tailsF = java.util.IdentityHashMap<OcrWord, Box>()
        val tailsB = java.util.IdentityHashMap<OcrWord, Box>()
        fun prep(ws: List<OcrWord>, t: java.util.IdentityHashMap<OcrWord, Box>) =
            TextLayout.mergeHyphenated(ws.map { it.copy(text = TextLayout.cleanWord(it.text)) }, t)
        val merged = TextLayout.combine(prep(r.words, tailsF), prep(read(best, r.crop), tailsB), tailsF, tailsB)
        val again = TextLayout.blockPhraseChecked(merged, r.item.box).phrase
        // ничего не вышло — вариант быстрой берём, только если он похож на речь
        return again ?: r.checked.phrase?.takeIf { TextLayout.speechLike(it.text, it.conf) }
    }

    /** Итог для надписи: мусор с рисунка (почти нет настоящих слов) не читаем. */
    fun finalPhrase(r: ItemRead, ph: Phrase?): Phrase? =
        ph?.takeIf { TextLayout.speechLike(it.text, it.conf) }

    /** Всё подряд (для проверок на компьютере): быстрой моделью, сомнительное — точной. */
    /**
     * Все реплики экрана в том порядке, в каком их озвучивает приложение: по порядку чтения,
     * но сомнительная надпись, которой нужно точное прочтение, — в конце (см. ReaderService).
     */
    fun readAll(p: Prepared, fast: OcrFn, best: OcrFn, cache: ItemCache? = null): List<Phrase> {
        val later = ArrayList<Pair<ItemRead, ItemCache.Sig?>>()
        val out = ArrayList<Phrase>()
        fun second(r: ItemRead, sig: ItemCache.Sig?) {
            val ph = finalPhrase(r, secondPass(r, best))
            if (cache != null && sig != null) cache.put(sig, ph)
            if (ph != null) out += ph
        }
        for (it in p.items) {
            val sig = cache?.signature(p, it)
            val hit = if (cache != null && sig != null) cache.find(it, sig) else null
            if (hit != null) { hit.phrase?.let { ph -> out += ph }; continue }
            val r = firstPass(p, it, fast)
            if (r.needsSecondPass) {
                if (it.likely) second(r, sig) else later += r to sig
                continue
            }
            val ph = finalPhrase(r, r.checked.phrase)
            if (cache != null && sig != null) cache.put(sig, ph)
            if (ph != null) out += ph
        }
        for ((r, sig) in later) second(r, sig)
        return out
    }
}

/**
 * Порядок чтения. Строки надписей сверху вниз; в строке — слева направо (манхва, вебтун)
 * или справа налево (манга). Для манги надписи сначала разбираются по кадрам: кадры рядами
 * сверху вниз, в ряду — справа налево, внутри кадра — строками справа налево.
 */
object ReadingOrder {

    /** Отладочный вывод (только для проверок на компьютере) */
    @JvmField var debug = false

    fun sort(items: List<TextItem>, gray: IntArray, w: Int, h: Int, rtl: Boolean): List<TextItem> {
        if (items.size < 2) return items
        if (!rtl) return rows(items, false)
        val panels = panels(gray, w, h, items)
        return panels.flatMap { rows(it, true) }
    }

    /**
     * Порядок надписей: берём самую верхнюю; но если правее неё (для манги; для манхвы — левее)
     * есть надпись, которая начинается выше её середины, — сначала та. Так пузыри одного «ряда»
     * читаются в нужную сторону, даже если один чуть ниже другого.
     */
    private fun rows(items: List<TextItem>, rtl: Boolean): List<TextItem> =
        greedy(items, rtl) { it.box }

    private fun <T> greedy(units: List<T>, rtl: Boolean, box: (T) -> Box): List<T> {
        val left = units.toMutableList()
        val out = ArrayList<T>(units.size)
        while (left.isNotEmpty()) {
            var c = left.minByOrNull { box(it).top }!!
            while (true) {
                val cb = box(c)
                val cx = (cb.left + cb.right) / 2
                val next = left.filter { o ->
                    val ob = box(o)
                    val ox = (ob.left + ob.right) / 2
                    o !== c && ob.top < cb.top + cb.height / 2 &&
                        (if (rtl) ox > cx + cb.width / 4 else ox < cx - cb.width / 4)
                }.minByOrNull { box(it).top } ?: break
                c = next
            }
            left.remove(c)
            out += c
        }
        return out
    }

    /** Разметка кадров: шаг сетки, «белое снаружи», номера кадров и их рамки (l, t, r, b, площадь). */
    private class Segments(
        val st: Int, val sw: Int, val sh: Int,
        val outside: BooleanArray, val label: IntArray, val boxes: List<IntArray>, val minArea: Int
    )

    /**
     * Кадры манги: белые поля и промежутки между кадрами соединены с краем страницы, а кадр
     * обнесён рамкой. Заливаем белое от краёв экрана (сетка 2×2 точки — чтобы не терять тонкие
     * промежутки между кадрами): что не залилось, то кадры.
     */
    private fun segment(gray: IntArray, w: Int, h: Int): Segments? {
        val st = 2
        val sw = w / st
        val sh = h / st
        if (sw < 8 || sh < 8) return null
        val white = BooleanArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0
            for (dy in 0 until st) {
                val row = (y * st + dy) * w + x * st
                for (dx in 0 until st) sum += gray[row + dx]
            }
            white[y * sw + x] = sum >= 215 * st * st
        }
        val outside = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        var qh = 0
        var qt = 0
        fun push(i: Int) { if (white[i] && !outside[i]) { outside[i] = true; queue[qt++] = i } }
        for (x in 0 until sw) { push(x); push((sh - 1) * sw + x) }
        for (y in 0 until sh) { push(y * sw); push(y * sw + sw - 1) }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % sw
            val y = i / sw
            if (x > 0) push(i - 1)
            if (x < sw - 1) push(i + 1)
            if (y > 0) push(i - sw)
            if (y < sh - 1) push(i + sw)
        }
        // Кадры могут касаться друг друга тонкой перемычкой (рисунок выходит за рамку, растр у края):
        // сначала «сжимаем» области на несколько точек — перемычки рвутся, кадры разделяются
        val core = erode(outside, sw, sh, 4)
        val label = IntArray(sw * sh)
        val boxes = ArrayList<IntArray>()
        for (start in 0 until sw * sh) {
            if (!core[start] || label[start] != 0) continue
            val id = boxes.size + 1
            val bx = intArrayOf(sw, sh, -1, -1, 0)
            qh = 0; qt = 0
            queue[qt++] = start; label[start] = id
            while (qh < qt) {
                val i = queue[qh++]
                val x = i % sw
                val y = i / sw
                bx[0] = min(bx[0], x); bx[1] = min(bx[1], y); bx[2] = max(bx[2], x); bx[3] = max(bx[3], y); bx[4]++
                if (x > 0 && core[i - 1] && label[i - 1] == 0) { label[i - 1] = id; queue[qt++] = i - 1 }
                if (x < sw - 1 && core[i + 1] && label[i + 1] == 0) { label[i + 1] = id; queue[qt++] = i + 1 }
                if (y > 0 && core[i - sw] && label[i - sw] == 0) { label[i - sw] = id; queue[qt++] = i - sw }
                if (y < sh - 1 && core[i + sw] && label[i + sw] == 0) { label[i + sw] = id; queue[qt++] = i + sw }
            }
            boxes += bx
        }
        return Segments(st, sw, sh, outside, label, boxes, sw * sh / 80)
    }

    /** Точки «внутри», у которых в квадрате ±[r] нет ни одной точки «снаружи». */
    private fun erode(outside: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val horiz = BooleanArray(w * h)
        for (y in 0 until h) {
            var lastOut = -1_000_000
            val row = y * w
            // слева направо: расстояние до ближайшей «снаружи» слева
            val left = IntArray(w)
            for (x in 0 until w) { if (outside[row + x]) lastOut = x; left[x] = x - lastOut }
            lastOut = 1_000_000
            for (x in w - 1 downTo 0) {
                if (outside[row + x]) lastOut = x
                horiz[row + x] = left[x] > r && lastOut - x > r
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            val up = IntArray(h)
            var last = -1_000_000
            for (y in 0 until h) { if (!horiz[y * w + x]) last = y; up[y] = y - last }
            last = 1_000_000
            for (y in h - 1 downTo 0) {
                if (!horiz[y * w + x]) last = y
                out[y * w + x] = up[y] > r && last - y > r
            }
        }
        return out
    }

    /**
     * Надписи по кадрам. Страница режется на полосы по горизонтальным промежуткам между кадрами
     * (строки, почти целиком белые снаружи: рисунок, заходящий на промежуток, не мешает); полосы —
     * сверху вниз, в полосе кадры — справа налево, внутри кадра — надписи справа налево.
     * Надпись, чей центр на белом поле (пузырь заходит за рамку), относится к кадру, в рамке
     * которого лежит; если такого нет — к ближайшему кадру рядом; иначе стоит сама по себе.
     */
    private fun panels(gray: IntArray, w: Int, h: Int, items: List<TextItem>): List<List<TextItem>> {
        val sg = segment(gray, w, h) ?: return listOf(items)
        val st = sg.st; val sw = sg.sw; val sh = sg.sh
        // полосы
        val gutter = BooleanArray(sh) { y ->
            var n = 0
            for (x in 0 until sw) if (sg.outside[y * sw + x]) n++
            n >= sw * 0.6f
        }
        val strips = ArrayList<IntArray>()       // [от, до) в клетках сетки
        var y0 = -1
        for (y in 0..sh) {
            val g = y == sh || gutter[y]
            if (!g && y0 < 0) y0 = y
            if (g && y0 >= 0) { if (y - y0 >= 6) strips += intArrayOf(y0, y); y0 = -1 }
        }
        if (strips.isEmpty()) strips += intArrayOf(0, sh)
        fun stripOf(cy: Int): Int {
            var best = 0
            var bestD = Int.MAX_VALUE
            for ((k, s0) in strips.withIndex()) {
                val d = if (cy < s0[0]) s0[0] - cy else if (cy >= s0[1]) cy - s0[1] + 1 else 0
                if (d < bestD) { bestD = d; best = k }
            }
            return best
        }
        fun big(id: Int) = id > 0 && sg.boxes[id - 1][4] >= sg.minArea
        // надпись → (полоса, кадр)
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<TextItem>>()
        var looseId = -1
        for (it in items) {
            val cx = (centerX(it) / st).coerceIn(0, sw - 1)
            val cy = (centerY(it) / st).coerceIn(0, sh - 1)
            val strip = stripOf(cy)
            var id = sg.label[cy * sw + cx]
            if (!big(id)) {
                id = 0
                var bestArea = Int.MAX_VALUE
                for ((k, bx) in sg.boxes.withIndex()) {
                    if (bx[4] < sg.minArea) continue
                    if (cx < bx[0] || cx > bx[2] || cy < bx[1] || cy > bx[3]) continue
                    val area = (bx[2] - bx[0] + 1) * (bx[3] - bx[1] + 1)
                    if (area < bestArea) { bestArea = area; id = k + 1 }
                }
            }
            if (id == 0) {
                val r = max(it.box.width, it.box.height) / (2 * st) + 4
                var bestD = Int.MAX_VALUE
                for (y in max(0, cy - r)..min(sh - 1, cy + r)) for (x in max(0, cx - r)..min(sw - 1, cx + r)) {
                    val q = sg.label[y * sw + x]
                    if (!big(q)) continue
                    val d = abs(x - cx) + abs(y - cy)
                    if (d < bestD) { bestD = d; id = q }
                }
            }
            if (id == 0) id = looseId--
            groups.getOrPut(strip to id) { ArrayList() } += it
        }
        if (debug) groups.forEach { (k, v) -> println("  полоса ${k.first}, кадр ${k.second}: " + v.map { it.box }) }
        // рамка кадра в пределах полосы (у отдельной надписи — её рамка)
        class Unit(val box: Box, val items: List<TextItem>)
        val byStrip = groups.entries.groupBy { it.key.first }
        val out = ArrayList<List<TextItem>>()
        for (k in byStrip.keys.sorted()) {
            val units = byStrip.getValue(k).map { (key, list) ->
                val id = key.second
                val b = if (id > 0) {
                    val s0 = strips[k]
                    var l = sw; var t = sh; var r = -1; var bt = -1
                    for (y in s0[0] until s0[1]) for (x in 0 until sw) {
                        if (sg.label[y * sw + x] != id) continue
                        if (x < l) l = x
                        if (x > r) r = x
                        if (y < t) t = y
                        if (y > bt) bt = y
                    }
                    if (r < 0) list.map { it.box }.reduce { a, c -> a.union(c) }
                    else Box(l * st, t * st, (r + 1) * st, (bt + 1) * st)
                } else list.map { it.box }.reduce { a, c -> a.union(c) }
                Unit(b, list)
            }
            for (u in greedy(units, true) { it.box }) out += u.items
        }
        return out
    }

    /** Для проверок на компьютере: шаг сетки и метки (−1 — белое снаружи, 0 — мелкое, >0 — кадры). */
    fun debugPanels(gray: IntArray, w: Int, h: Int): Pair<Int, IntArray> {
        val r = segment(gray, w, h) ?: return 1 to IntArray(0)
        val out = IntArray(r.label.size) { i -> if (r.outside[i]) -1 else if (r.label[i] == 0) 0 else if (r.boxes[r.label[i] - 1][4] < r.minArea) 0 else r.label[i] }
        return r.st to out
    }

    private fun centerX(it: TextItem) = (it.box.left + it.box.right) / 2
    private fun centerY(it: TextItem) = (it.box.top + it.box.bottom) / 2
}
