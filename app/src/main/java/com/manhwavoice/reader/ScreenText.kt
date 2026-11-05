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
    val letters: List<Box>? = null
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
    /** Откуда брать пиксели для распознавания (по возможности — исходный снимок в полном разрешении) */
    val src: GraySrc = GraySrc.of(gray, w, h)
)

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
                items += TextItem(b.box, b, hs[hs.size / 2], false)
            }
        }
        for (i in text.blocks.indices) {
            if (used[i]) continue
            val bl = text.blocks[i]
            if (bl.standalone) items += TextItem(bl.box, null, bl.letterH, bl.inverse, bl.parts)
        }
        return items
    }

    // ---------------- Вырезка и распознавание ----------------

    /** Чистая вырезка надписи: только буквы, всё остальное белое; мелкий текст увеличен. */
    fun crop(p: Prepared, it: TextItem): TextFinder.Crop {
        val b = it.bubble
        return if (b != null) {
            TextFinder.cleanCrop(
                p.src, it.box, false, max((2.5f * it.letterH).toInt(), it.letterH + 12), it.letterH,
                dropTouching = false, inside = { x, y -> b.inside(x, y) }
            )
        } else {
            val m = max(6, (0.6f * it.letterH).toInt())
            val region = Box(it.box.left - m, max(p.regionTop, it.box.top - m), it.box.right + m, min(p.regionBottom, it.box.bottom + m))
            TextFinder.cleanCrop(
                p.src, region, it.inverse, max((1.6f * it.letterH).toInt(), it.letterH + 4), it.letterH,
                dropTouching = true, letters = it.letters
            )
        }
    }

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
        val merged = TextLayout.combine(r.words, read(best, r.crop))
        val again = TextLayout.blockPhraseChecked(merged, r.item.box).phrase
        // ничего не вышло — вариант быстрой берём, только если он похож на речь
        return again ?: r.checked.phrase?.takeIf { TextLayout.speechLike(it.text, it.conf) }
    }

    /** Итог для надписи: мусор с рисунка (почти нет настоящих слов) не читаем. */
    fun finalPhrase(r: ItemRead, ph: Phrase?): Phrase? =
        ph?.takeIf { TextLayout.speechLike(it.text, it.conf) }

    /** Всё подряд (для проверок на компьютере): быстрой моделью, сомнительное — точной. */
    fun readAll(p: Prepared, fast: OcrFn, best: OcrFn, cache: ItemCache? = null): List<Phrase> =
        p.items.mapNotNull { it ->
            val sig = cache?.signature(p, it)
            val hit = if (cache != null && sig != null) cache.find(it, sig) else null
            if (hit != null) return@mapNotNull hit.phrase
            val r = firstPass(p, it, fast)
            val ph = finalPhrase(r, if (r.needsSecondPass) secondPass(r, best) else r.checked.phrase)
            if (cache != null && sig != null) cache.put(sig, ph)
            ph
        }
}

/**
 * Порядок чтения. Строки надписей сверху вниз; в строке — слева направо (манхва, вебтун)
 * или справа налево (манга). Для манги надписи сначала разбираются по кадрам: кадры рядами
 * сверху вниз, в ряду — справа налево, внутри кадра — строками справа налево.
 */
object ReadingOrder {

    fun sort(items: List<TextItem>, gray: IntArray, w: Int, h: Int, rtl: Boolean): List<TextItem> {
        if (items.size < 2) return items
        if (!rtl) return rows(items, false)
        val panels = panels(gray, w, h, items)
        return panels.flatMap { rows(it, true) }
    }

    /** Строки: надпись в той же строке, если начинается не ниже середины первой надписи строки. */
    private fun rows(items: List<TextItem>, rtl: Boolean): List<TextItem> {
        val sorted = items.sortedBy { it.box.top }
        val out = ArrayList<TextItem>()
        var i = 0
        while (i < sorted.size) {
            val anchor = sorted[i]
            val row = arrayListOf(anchor)
            var j = i + 1
            while (j < sorted.size) {
                val c = sorted[j]
                val limit = anchor.box.top + 0.5f * min(anchor.box.height, c.box.height)
                if (c.box.top < limit) { row += c; j++ } else break
            }
            out += if (rtl) row.sortedByDescending { it.box.right } else row.sortedBy { it.box.left }
            i = j
        }
        return out
    }

    /**
     * Кадры манги: белые поля и промежутки между кадрами соединены с краем страницы, а кадр
     * обнесён рамкой. Заливаем белое от краёв экрана (в уменьшенном масштабе) — что не залилось,
     * то кадры. Кадры читаются рядами сверху вниз, в ряду — справа налево.
     */
    private fun panels(gray: IntArray, w: Int, h: Int, items: List<TextItem>): List<List<TextItem>> {
        val st = 4
        val sw = w / st
        val sh = h / st
        if (sw < 8 || sh < 8) return listOf(items)
        val white = BooleanArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0
            for (dy in 0 until st) {
                val row = (y * st + dy) * w + x * st
                for (dx in 0 until st) sum += gray[row + dx]
            }
            white[y * sw + x] = sum >= 225 * st * st
        }
        // заливка белого от краёв
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
        // кадры — связные области «не снаружи»
        val label = IntArray(sw * sh)
        val boxes = ArrayList<IntArray>()     // l, t, r, b, площадь
        for (start in 0 until sw * sh) {
            if (outside[start] || label[start] != 0) continue
            val id = boxes.size + 1
            val bx = intArrayOf(sw, sh, -1, -1, 0)
            qh = 0; qt = 0
            queue[qt++] = start; label[start] = id
            while (qh < qt) {
                val i = queue[qh++]
                val x = i % sw
                val y = i / sw
                bx[0] = min(bx[0], x); bx[1] = min(bx[1], y); bx[2] = max(bx[2], x); bx[3] = max(bx[3], y); bx[4]++
                if (x > 0 && !outside[i - 1] && label[i - 1] == 0) { label[i - 1] = id; queue[qt++] = i - 1 }
                if (x < sw - 1 && !outside[i + 1] && label[i + 1] == 0) { label[i + 1] = id; queue[qt++] = i + 1 }
                if (y > 0 && !outside[i - sw] && label[i - sw] == 0) { label[i - sw] = id; queue[qt++] = i - sw }
                if (y < sh - 1 && !outside[i + sw] && label[i + sw] == 0) { label[i + sw] = id; queue[qt++] = i + sw }
            }
            boxes += bx
        }
        val minArea = sw * sh / 50
        // надпись → кадр (по центру; если центр на белом поле — по ближайшей точке кадра рядом)
        val byPanel = HashMap<Int, MutableList<TextItem>>()
        val loose = ArrayList<TextItem>()
        for (it in items) {
            val cx = (centerX(it) / st).coerceIn(0, sw - 1)
            val cy = (centerY(it) / st).coerceIn(0, sh - 1)
            var id = label[cy * sw + cx]
            if (id == 0 || boxes[id - 1][4] < minArea) {
                id = 0
                val r = max(it.box.width, it.box.height) / (2 * st) + 2
                var bestD = Int.MAX_VALUE
                for (y in max(0, cy - r)..min(sh - 1, cy + r)) for (x in max(0, cx - r)..min(sw - 1, cx + r)) {
                    val q = label[y * sw + x]
                    if (q == 0 || boxes[q - 1][4] < minArea) continue
                    val d = abs(x - cx) + abs(y - cy)
                    if (d < bestD) { bestD = d; id = q }
                }
            }
            if (id == 0) loose += it else byPanel.getOrPut(id) { ArrayList() } += it
        }
        if (byPanel.size < 2) return listOf(items)
        // кадры: ряды сверху вниз, в ряду — справа налево; надписи вне кадров — как отдельные «кадры»
        class Unit(val l: Int, val t: Int, val r: Int, val b: Int, val items: List<TextItem>)
        val units = ArrayList<Unit>()
        for ((id, list) in byPanel) {
            val bx = boxes[id - 1]
            units += Unit(bx[0] * st, bx[1] * st, (bx[2] + 1) * st, (bx[3] + 1) * st, list)
        }
        for (it in loose) units += Unit(it.box.left, it.box.top, it.box.right, it.box.bottom, listOf(it))
        units.sortBy { it.t }
        val out = ArrayList<List<TextItem>>()
        var i = 0
        while (i < units.size) {
            val a = units[i]
            val row = arrayListOf(a)
            var j = i + 1
            while (j < units.size) {
                val c = units[j]
                if (c.t < a.t + 0.5f * min(a.b - a.t, c.b - c.t)) { row += c; j++ } else break
            }
            row.sortByDescending { it.r }
            row.forEach { out += it.items }
            i = j
        }
        return out
    }

    private fun centerX(it: TextItem) = (it.box.left + it.box.right) / 2
    private fun centerY(it: TextItem) = (it.box.top + it.box.bottom) / 2
}
