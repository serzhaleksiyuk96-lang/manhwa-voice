package com.manhwavoice.reader

import kotlin.math.max
import kotlin.math.min

/** Картинка в оттенках серого, по байту на пиксель, построчно. */
class GrayImage(val w: Int, val h: Int, val px: ByteArray)

/** Распознавание картинки одним экземпляром Tesseract (режим разметки задан при создании). */
fun interface OcrFn {
    fun read(img: GrayImage): List<OcrWord>
}

/** Подготовленный снимок экрана. */
class Prepared(
    val gray: IntArray,
    val w: Int,
    val h: Int,
    val topLimit: Int?,
    val bottomLimit: Int?,
    /** На экране только фон (промежуток между кадрами) */
    val blank: Boolean,
    /** Доля тёмных пикселей — нужен ли проход контурного шрифта */
    val darkShare: Float,
    val bubbles: List<Bubble>,
    val cutAtBottom: List<Box>
)

/**
 * Узнаёт пузыри, уже распознанные на прошлых экранах: после прокрутки это те же картинки,
 * просто выше. Сравниваются размер и «отпечаток» — сетка средних яркостей внутри пузыря.
 * Так уже прочитанные пузыри (они вверху экрана) не задерживают распознавание новых.
 */
class BubbleCache(private val capacity: Int = 40) {
    class Hit(val phrase: Phrase?)

    private class Entry(val w: Int, val h: Int, val sig: IntArray, val phrase: Phrase?)

    private val items = ArrayList<Entry>()

    fun signature(p: Prepared, b: Bubble): IntArray {
        val bx = b.box
        val sums = IntArray(GX * GY)
        val cnt = IntArray(GX * GY)
        var y = bx.top
        while (y < bx.bottom) {
            val cy = (y - bx.top) * GY / bx.height
            var x = bx.left
            while (x < bx.right) {
                val v = if (b.inside(x, y)) p.gray[y * p.w + x] else 255
                val i = cy * GX + (x - bx.left) * GX / bx.width
                sums[i] += v
                cnt[i]++
                x += 2
            }
            y += 2
        }
        return IntArray(GX * GY) { if (cnt[it] > 0) sums[it] / cnt[it] else 255 }
    }

    @Synchronized
    fun find(b: Bubble, sig: IntArray): Hit? {
        for (e in items.asReversed()) {
            if (kotlin.math.abs(e.w - b.box.width) > 4 || kotlin.math.abs(e.h - b.box.height) > 4) continue
            var d = 0
            for (i in sig.indices) d += kotlin.math.abs(sig[i] - e.sig[i])
            if (d < sig.size * 5) return Hit(e.phrase?.copy(box = b.box))
        }
        return null
    }

    @Synchronized
    fun put(b: Bubble, sig: IntArray, phrase: Phrase?) {
        items += Entry(b.box.width, b.box.height, sig, phrase)
        if (items.size > capacity) items.removeAt(0)
    }

    @Synchronized
    fun clear() = items.clear()

    private companion object {
        const val GX = 24
        const val GY = 12
    }
}

/** Найденный быстрой моделью текст и вырезка вокруг него — для уточнения точной моделью. */
class Candidate(val phrase: Phrase, val crop: GrayImage, val x0: Int, val y0: Int)

/**
 * Весь путь от снимка экрана до реплик, без зависимостей от Android:
 *  1) пузыри — каждый отдельно, без контура и рисунка вокруг, точной моделью;
 *  2) остальной экран — быстрой моделью (только чтобы найти, где текст);
 *  3) найденный вне пузырей текст — ещё раз точной моделью по маленькой вырезке.
 */
object ScreenText {

    private const val PAD = 12
    private const val WHITE: Byte = -1   // 255

    fun prepare(gray: IntArray, w: Int, h: Int, topLimit: Int?, bottomLimit: Int?): Prepared {
        val n = w * h
        val hist = IntArray(256)
        var dark = 0
        for (g in gray) {
            hist[g]++
            if (g <= 128) dark++
        }
        // Пустой экран: почти все пиксели одного цвета
        var mode = 0
        for (i in 1 until 256) if (hist[i] > hist[mode]) mode = i
        var different = 0
        for (i in 0 until 256) if (kotlin.math.abs(i - mode) > 40) different += hist[i]
        val blank = different < 250
        val found = if (blank) null else BubbleFinder.find(gray, w, h)
        return Prepared(
            gray, w, h, topLimit, bottomLimit, blank, dark / n.toFloat(),
            found?.bubbles ?: emptyList(), found?.cutAtBottom ?: emptyList()
        )
    }

    // ---------------- Пузыри ----------------

    /** Пузырь без контура, хвостика и рисунка вокруг: всё снаружи — белое. */
    fun bubbleImage(p: Prepared, b: Bubble): Candidate {
        val bx = b.box
        val cw = bx.width + 2 * PAD
        val ch = bx.height + 2 * PAD
        val out = ByteArray(cw * ch) { WHITE }
        for (y in bx.top until bx.bottom) {
            val src = y * p.w
            val dst = (y - bx.top + PAD) * cw + PAD - bx.left
            for (x in bx.left until bx.right) {
                if (b.inside(x, y)) out[dst + x] = p.gray[src + x].toByte()
            }
        }
        return Candidate(Phrase("", bx), GrayImage(cw, ch, out), bx.left - PAD, bx.top - PAD)
    }

    fun readBubbles(p: Prepared, best: OcrFn): List<Phrase> {
        val res = ArrayList<Phrase>()
        for (b in p.bubbles) {
            val c = bubbleImage(p, b)
            val words = best.read(c.crop).map { shift(it, c.x0, c.y0) }
            TextLayout.blockPhrase(words, b.box)?.let { res += it }
        }
        return res.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Результат первого (быстрого) прочтения пузыря. */
    class BubbleRead(val bubble: Bubble, val crop: Candidate, val checked: TextLayout.Checked, val textLike: Boolean) {
        /** Перечитать точной моделью: результат сомнительный, но в пузыре похоже есть текст. */
        val needsSecondPass get() = checked.doubtful && (checked.phrase != null || textLike)
    }

    /** Первый проход: пузырь быстрой моделью. */
    fun bubbleFirstPass(p: Prepared, b: Bubble, ocr: OcrFn): BubbleRead {
        val c = bubbleImage(p, b)
        val words = ocr.read(c.crop).map { shift(it, c.x0, c.y0) }
        return BubbleRead(b, c, TextLayout.blockPhraseChecked(words, b.box), TextLayout.looksLikeText(words))
    }

    /** Второй проход для сомнительного пузыря: точная модель. */
    fun bubbleSecondPass(r: BubbleRead, best: OcrFn): Phrase? {
        val words = best.read(r.crop.crop).map { shift(it, r.crop.x0, r.crop.y0) }
        return TextLayout.blockPhraseChecked(words, r.bubble.box).phrase ?: r.checked.phrase
    }

    /** Пузыри: быстрой моделью, сомнительные — ещё раз точной (последовательно, для проверок). */
    fun readBubblesAdaptive(p: Prepared, fast: OcrFn, best: OcrFn, cache: BubbleCache? = null): List<Phrase> =
        p.bubbles.sortedBy { it.box.top }.mapNotNull { b ->
            val sig = cache?.signature(p, b)
            val hit = if (cache != null && sig != null) cache.find(b, sig) else null
            if (hit != null) return@mapNotNull hit.phrase
            val r = bubbleFirstPass(p, b, fast)
            val ph = if (r.needsSecondPass) bubbleSecondPass(r, best) else r.checked.phrase
            if (cache != null && sig != null) cache.put(b, sig, ph)
            ph
        }

    // ---------------- Остальной экран ----------------

    /** Ищет текст вне пузырей быстрой моделью. Вызывать в потоке быстрой модели. */
    fun detectRest(p: Prepared, fast: OcrFn): List<Candidate> {
        val n = p.w * p.h
        // Экран без пузырей (их читает точная модель)
        val restPx = ByteArray(n) { p.gray[it].toByte() }
        for (b in p.bubbles) {
            val l = max(0, b.box.left - 4); val r = min(p.w, b.box.right + 4)
            val t = max(0, b.box.top - 4); val bt = min(p.h, b.box.bottom + 4)
            for (y in t until bt) java.util.Arrays.fill(restPx, y * p.w + l, y * p.w + r, WHITE)
        }
        val restImg = GrayImage(p.w, p.h, restPx)
        val wordsA = fast.read(restImg)
        val candA = TextLayout.buildPhrases(wordsA, p.h, p.topLimit, p.bottomLimit)
            .filter { notCut(p, it) }
            .map { maskedCrop(it, restImg, wordsA) }

        // Тёмные буквы с белой обводкой на тёмном фоне — только на тёмных экранах
        var candB = emptyList<Candidate>()
        if (p.darkShare >= OutlineFix.MIN_BACKGROUND) {
            val fixed = OutlineFix.process(p.gray, p.w, p.h).pixels
            if (fixed != null) {
                val bImg = GrayImage(p.w, p.h, ByteArray(n) { fixed[it].toByte() })
                candB = TextLayout.buildPhrases(fast.read(bImg), p.h, p.topLimit, p.bottomLimit)
                    .filter { notCut(p, it) }
                    .map { plainCrop(it, bImg) }
            }
        }

        // Объединяем: из пересекающихся берём ту, где прочитано больше и увереннее
        val result = candA.toMutableList()
        for (cb in candB) {
            val overlapping = result.filter { TextLayout.intersects(it.phrase.box, cb.phrase.box) }
            if (overlapping.isEmpty()) {
                result += cb
            } else if (TextLayout.score(cb.phrase) >= 0.9 * overlapping.sumOf { TextLayout.score(it.phrase) }) {
                result.removeAll(overlapping)
                result += cb
            }
        }
        return result.sortedWith(compareBy({ it.phrase.box.top }, { it.phrase.box.left }))
    }

    /** Уточняет найденный текст точной моделью. Вызывать в потоке точной модели. */
    fun refine(cands: List<Candidate>, best: OcrFn): List<Phrase> = cands.mapNotNull { c ->
        val words = best.read(c.crop).map { shift(it, c.x0, c.y0) }
        val r = TextLayout.blockPhrase(words, c.phrase.box)
        when {
            r != null && r.conf >= c.phrase.conf - 15 -> r
            // точная модель не справилась — берём вариант быстрой, если он похож на речь
            TextLayout.plausible(c.phrase.text) -> c.phrase
            else -> r
        }
    }

    /** Текст из пузыря, обрезанного нижним краем, прочитаем целиком после прокрутки. */
    private fun notCut(p: Prepared, ph: Phrase): Boolean =
        p.bottomLimit == null || p.cutAtBottom.none { TextLayout.intersects(it, ph.box) }

    /** Вырезка, где видны только найденные слова (рисунок вокруг закрашен белым). */
    private fun maskedCrop(ph: Phrase, src: GrayImage, words: List<OcrWord>): Candidate {
        val m = 14
        val l = max(0, ph.box.left - m); val r = min(src.w, ph.box.right + m)
        val t = max(0, ph.box.top - m); val b = min(src.h, ph.box.bottom + m)
        val region = Box(l, t, r, b)
        val cw = r - l + 2 * PAD
        val ch = b - t + 2 * PAD
        val out = ByteArray(cw * ch) { WHITE }
        for (wd in words) {
            if (!TextLayout.intersects(wd.box, region)) continue
            val e = max(3, wd.box.height / 4)
            val x1 = max(l, wd.box.left - e); val x2 = min(r, wd.box.right + e)
            val y1 = max(t, wd.box.top - e); val y2 = min(b, wd.box.bottom + e)
            for (y in y1 until y2) {
                val so = y * src.w
                val dO = (y - t + PAD) * cw + PAD - l
                for (x in x1 until x2) out[dO + x] = src.px[so + x]
            }
        }
        return Candidate(ph, GrayImage(cw, ch, out), l - PAD, t - PAD)
    }

    private fun plainCrop(ph: Phrase, src: GrayImage): Candidate {
        val m = 14
        val l = max(0, ph.box.left - m); val r = min(src.w, ph.box.right + m)
        val t = max(0, ph.box.top - m); val b = min(src.h, ph.box.bottom + m)
        val cw = r - l + 2 * PAD
        val ch = b - t + 2 * PAD
        val out = ByteArray(cw * ch) { WHITE }
        for (y in t until b) {
            System.arraycopy(src.px, y * src.w + l, out, (y - t + PAD) * cw + PAD, r - l)
        }
        return Candidate(ph, GrayImage(cw, ch, out), l - PAD, t - PAD)
    }

    private fun shift(wd: OcrWord, dx: Int, dy: Int) =
        wd.copy(box = Box(wd.box.left + dx, wd.box.top + dy, wd.box.right + dx, wd.box.bottom + dy))
}
