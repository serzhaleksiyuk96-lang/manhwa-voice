package com.manhwavoice.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Чистая логика без зависимостей от Android: фильтрация слов из OCR,
 * сборка их в реплики, порядок чтения и защита от повторов.
 */

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2
    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

data class OcrWord(val text: String, val box: Box, val conf: Float)

data class Phrase(val text: String, val box: Box, val conf: Double = 0.0)

object TextLayout {

    private val CYR = Regex("[А-Яа-яЁё]")
    // Однобуквенные русские слова, которые стоит оставлять
    private val SINGLE_OK = setOf("и", "а", "в", "я", "с", "к", "у", "о", "ж", "б")
    // Короткие реплики из одного слова, которые можно читать (остальное из 1–2 букв — обычно мусор)
    private val SHORT_OK = setOf("да", "не", "ну", "эй", "ох", "ах", "ой", "но", "ха", "ты", "он", "мы", "вы", "я", "а", "о", "и", "уф", "ух", "ага", "эх")

    // Служебные слова, которые OCR часто приклеивает к соседнему («непозволю», «яже», «ктоже»)
    // (предлоги вроде «на», «за», «по» сюда не входят: это ещё и приставки — «нападай»)
    private val GLUE_FIRST = setOf("не", "ни", "и", "а", "я", "ты", "он", "мы", "вы", "но", "что", "как", "так", "это")
    private val GLUE_LAST = setOf("же", "бы", "ли", "ка", "то")
    // Настоящие двухбуквенные слова
    private val TWO_OK = setOf(
        "не", "ни", "на", "за", "по", "до", "от", "из", "во", "со", "ко", "об", "то", "же", "бы", "ли",
        "ну", "да", "но", "он", "мы", "вы", "ты", "их", "ее", "ей", "им", "уж", "ах", "ох", "эх", "ой",
        "ай", "эй", "ха", "уф", "ух", "аж"
    )

    private const val KEEP_PUNCT = ".,!?…-—–:;\"'«»()"

    // Латинские буквы, которые выглядят как русские: OCR иногда путает их внутри русских слов
    private val LAT2CYR = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М',
        'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у'
    )

    private fun isCyr(c: Char) = c in 'А'..'я' || c == 'Ё' || c == 'ё'

    /**
     * Убирает из слова посторонние значки (©, @, | и т.п.), которые OCR находит в рисунке,
     * и заменяет похожие латинские буквы и цифры (0→О, 3→З) на русские.
     */
    fun cleanWord(s: String): String {
        var t = s.trim().filter { it.isLetterOrDigit() || it in KEEP_PUNCT }
        val letters = t.filter { it.isLetter() }
        if (letters.isEmpty()) return t
        val hasCyr = letters.any { isCyr(it) }
        val allLookalike = letters.all { isCyr(it) || it in LAT2CYR }
        if (hasCyr || allLookalike) {
            t = t.map { LAT2CYR[it] ?: it }.joinToString("")
            // Цифры посреди русского слова: 0 → О, 3 → З
            if (letters.length >= 2) {
                val sb = StringBuilder(t)
                for (i in sb.indices) {
                    val c = sb[i]
                    if (c != '0' && c != '3') continue
                    val prev = sb.getOrNull(i - 1)
                    val next = sb.getOrNull(i + 1)
                    val nearLetter = (prev != null && isCyr(prev)) || (next != null && isCyr(next))
                    if (!nearLetter) continue
                    val upper = listOfNotNull(prev, next).filter { isCyr(it) }.all { it.isUpperCase() }
                    sb[i] = when (c) {
                        '0' -> if (upper) 'О' else 'о'
                        else -> if (upper) 'З' else 'з'
                    }
                }
                t = sb.toString()
            }
        }
        return t
    }

    /** Оставляем только похожее на русский текст, мусор с рисунка отбрасываем. */
    fun keepWord(w: OcrWord, screenH: Int): Boolean {
        val t = w.text.trim()
        if (t.isEmpty()) return false
        val h = w.box.height
        if (h < 10 || h > screenH * 0.07) return false
        val letters = t.count { it.isLetter() }
        if (letters == 0) return false
        val cyr = CYR.findAll(t).count()
        if (cyr < letters * 0.7) return false
        val core = t.lowercase().filter { it.isLetter() }
        if (core.length == 1) return core in SINGLE_OK && w.conf >= 70f
        // Явный мусор: одна и та же буква много раз подряд и ничего больше
        if (core.length >= 4 && core.toSet().size == 1 && w.conf < 85f) return false
        return w.conf >= 45f
    }

    private fun near(a: Box, b: Box): Boolean {
        val hMax = max(a.height, b.height).toFloat()
        val hMin = max(1, min(a.height, b.height)).toFloat()
        if (hMax / hMin > 2.2f) return false          // разный кегль — скорее всего разные надписи
        val gx = max(0, max(a.left, b.left) - min(a.right, b.right))
        val gy = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
        return gx <= 1.6f * hMax && gy <= 1.0f * hMax
    }

    /** Группирует слова свободного текста (вне пузырей) в реплики, сверху вниз. */
    fun buildPhrases(
        rawWords: List<OcrWord>,
        screenH: Int,
        topCutLimit: Int?,      // если не null — пропускаем реплики, обрезанные сверху
        bottomCutLimit: Int?    // если не null — пропускаем реплики, обрезанные снизу
    ): List<Phrase> {
        val words = rawWords.map { it.copy(text = cleanWord(it.text)) }.filter { keepWord(it, screenH) }
        if (words.isEmpty()) return emptyList()

        // Объединение близких слов (система непересекающихся множеств)
        val parent = IntArray(words.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        for (i in words.indices) for (j in i + 1 until words.size) {
            if (near(words[i].box, words[j].box)) {
                val a = find(i); val b = find(j)
                if (a != b) parent[a] = b
            }
        }
        val groups = words.indices.groupBy { find(it) }.values.map { idx -> idx.map { words[it] } }

        val phrases = mutableListOf<Phrase>()
        for (g in groups) {
            val box = g.map { it.box }.reduce { a, b -> a.union(b) }
            val letters = g.sumOf { w -> w.text.count { it.isLetter() } }
            val meanConf = g.map { it.conf }.average()
            if (letters < 2) continue
            if (meanConf < 55.0) continue
            if (g.size == 1 && g[0].conf < 70f) continue
            if (g.size == 1) {
                val core = g[0].text.lowercase().filter { it.isLetter() }
                if (core.length <= 2 && core !in SHORT_OK) continue
            }
            if (topCutLimit != null && box.top < topCutLimit) continue
            if (bottomCutLimit != null && box.bottom > bottomCutLimit) continue
            val text = joinLines(g)
            if (text.isNotBlank()) phrases += Phrase(text, box, meanConf)
        }
        return phrases.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Частотный словарь (загружается при старте). Без него работают только фильтры по уверенности. */
    @Volatile
    var lexicon: Lexicon? = null

    private fun core(t: String) = t.filter { it.isLetter() || it == '-' }.trim('-')

    /**
     * Проверка слова по словарю: оставить как есть, исправить или выбросить (null).
     * Уверенность Tesseract ненадёжна: правильное слово бывает с уверенностью 0,
     * а мусор с рисунка — с 40. Поэтому главный критерий — есть ли такое слово в языке.
     */
    private fun checkWord(w: OcrWord): OcrWord? {
        val lex = lexicon ?: return if (w.conf >= 15f) w else null
        val c = core(w.text)
        if (c.isEmpty()) return null
        val k = lex.key(c)
        if (c in lex) {
            // В словаре из интернета много коротких обрывков («ра», «ня») — для 2–3 букв строже
            if (k.length == 2 && k !in TWO_OK && w.conf < 75f) return null
            if (k.length == 3 && (lex.rankOf(k) ?: Int.MAX_VALUE) > 20_000 && w.conf < 60f) return null
            return w
        }
        if (w.conf >= 60f) {
            // Уверенно, но нет в словаре: имя, редкое слово — или слипшиеся «непозволю»
            glueSplit(lex, k)?.let { return w.copy(text = replaceCore(w.text, c, it)) }
            return w
        }
        val fixed = lex.correct(k) ?: glueSplit(lex, k)
        if (fixed != null) return w.copy(text = replaceCore(w.text, c, fixed))
        return if (w.conf >= 30f && c.length >= 4) w else null
    }

    /** Разделить слипшиеся слова, только если одна часть — служебное слово. */
    private fun glueSplit(lex: Lexicon, k: String): String? {
        for ((a, b) in lex.splits(k)) {
            if (a in GLUE_FIRST && b.length >= 2) return "$a $b"
            if (b in GLUE_LAST && a.length >= 2) return "$a $b"
        }
        return null
    }

    private fun replaceCore(text: String, c: String, fixed: String): String {
        val i = text.indexOf(c)
        if (i < 0) return text
        val letters = c.filter { it.isLetter() }
        val cased = when {
            letters.isNotEmpty() && letters.all { it.isUpperCase() } -> fixed.uppercase()
            letters.firstOrNull()?.isUpperCase() == true -> fixed.replaceFirstChar { it.uppercaseChar() }
            else -> fixed
        }
        return text.substring(0, i) + cased + text.substring(i + c.length)
    }

    /**
     * Собирает ОДНУ реплику из всех слов одного пузыря (или одной уточнённой области).
     * Пузырь уже очищен от контура и рисунка, а слова проверяются по словарю, поэтому
     * неуверенно прочитанное, но настоящее слово не теряется.
     */
    fun blockPhrase(rawWords: List<OcrWord>, box: Box): Phrase? {
        val cleaned = mergeHyphenated(
            rawWords.map { it.copy(text = cleanWord(it.text)) }.filter { w -> w.text.any { it.isLetter() } }
        )
        if (cleaned.isEmpty()) return null
        val heights = cleaned.map { it.box.height }.sorted()
        val med = heights[heights.size / 2].toDouble()
        val kept = ArrayList<OcrWord>()
        for (w in cleaned) {
            val t = w.text
            val letters = t.count { it.isLetter() }
            val cyr = t.count { isCyr(it) }
            if (cyr < letters * 0.6) continue
            if (cleaned.size >= 3 && (w.box.height > med * 2.3 || w.box.height < med * 0.35)) continue
            val lc = t.lowercase().filter { it.isLetter() }
            if (lc.length == 1) {
                if (lc in SINGLE_OK && w.conf >= 20f) kept += w
                continue
            }
            if (lc.length >= 4 && lc.toSet().size == 1) continue
            checkWord(w)?.let { kept += it }
        }
        if (kept.isEmpty()) return null
        val letters = kept.sumOf { w -> w.text.count { it.isLetter() } }
        if (letters < 2) return null
        if (kept.size == 1) {
            val lc = kept[0].text.lowercase().filter { it.isLetter() }
            if (lc.length <= 2 && (lc !in SHORT_OK || kept[0].conf < 70f)) return null
        }
        // Мусор с рисунка: почти ничего не похоже на слова, и распознано неуверенно
        val lex = lexicon
        if (lex != null) {
            val known = kept.sumOf { w -> val c = core(w.text); if (c in lex) c.length else 0 }
            if (known < letters * 0.35 && kept.map { it.conf }.average() < 55) return null
        }
        val text = joinLines(kept)
        if (text.isBlank()) return null
        return Phrase(text, box, kept.map { it.conf }.average())
    }

    /**
     * Похоже ли на речь: больше половины букв — в настоящих словах
     * (с теми же строгими правилами для коротких слов). Без словаря — всегда да.
     */
    fun plausible(text: String): Boolean {
        val lex = lexicon ?: return true
        var known = 0
        var total = 0
        for (raw in text.split(' ')) {
            val c = core(raw)
            if (c.isEmpty()) continue
            total += c.length
            val k = lex.key(c)
            val ok = when {
                k.length == 1 -> k in SINGLE_OK
                k.length == 2 -> k in TWO_OK
                k.length == 3 -> (lex.rankOf(k) ?: Int.MAX_VALUE) <= 20_000
                else -> c in lex
            }
            if (ok) known += c.length
        }
        return total > 0 && known * 2 > total
    }

    /**
     * Объединяет результаты двух проходов распознавания. Если реплики пересекаются,
     * берём ту, в которой OCR увереннее.
     */
    fun mergePhrases(a: List<Phrase>, b: List<Phrase>): List<Phrase> {
        val result = a.toMutableList()
        for (pb in b) {
            val overlapping = result.filter { intersects(it.box, pb.box) }
            if (overlapping.isEmpty()) {
                result += pb
            } else if (score(pb) >= 0.9 * overlapping.sumOf { score(it) }) {
                result.removeAll(overlapping)
                result += pb
            }
        }
        return result.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** «Вес» реплики: сколько букв прочитано и насколько уверенно. */
    fun score(p: Phrase): Double = p.text.count { it.isLetter() } * p.conf.coerceAtLeast(1.0)

    fun intersects(a: Box, b: Box) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** Разбивает слова на строки (сверху вниз), внутри строки — слева направо. */
    private fun lines(g: List<OcrWord>): List<List<OcrWord>> {
        if (g.isEmpty()) return emptyList()
        val avgH = g.map { it.box.height }.average()
        val res = mutableListOf<MutableList<OcrWord>>()
        for (w in g.sortedBy { it.box.centerY }) {
            val last = res.lastOrNull()
            val lastCy = last?.map { it.box.centerY }?.average()
            if (last != null && lastCy != null && abs(w.box.centerY - lastCy) < 0.6 * avgH) last += w
            else res += mutableListOf(w)
        }
        return res.map { l -> l.sortedBy { it.box.left } }
    }

    /**
     * Склеивает слово, разорванное переносом: «СИЛЬНЕЙ-» + «ШИЙ» → «СИЛЬНЕЙШИЙ».
     * Делается до проверки по словарю: половинки слов в словаре не найдутся.
     */
    fun mergeHyphenated(words: List<OcrWord>): List<OcrWord> {
        val ls = lines(words)
        if (ls.size < 2) return words
        val out = ArrayList<OcrWord>()
        var carry: OcrWord? = null
        for ((i, line) in ls.withIndex()) {
            for ((j, w0) in line.withIndex()) {
                var w = w0
                if (j == 0 && carry != null) {
                    if (w.text.firstOrNull()?.isLetter() == true) {
                        // рамка — от первой половинки, чтобы слово осталось в конце строки
                        w = OcrWord(carry.text.dropLast(1) + w.text, carry.box, min(carry.conf, w.conf))
                    } else out += carry
                    carry = null
                }
                val isLast = j == line.size - 1
                val t = w.text
                if (isLast && i < ls.size - 1 && t.length >= 2 && t.last() in "-‐‑" && t[t.length - 2].isLetter()) {
                    carry = w
                } else {
                    out += w
                }
            }
        }
        carry?.let { out += it }
        return out
    }

    /** Склеивает слова одной реплики построчно, слева направо. */
    fun joinLines(g: List<OcrWord>): String {
        val avgH = g.map { it.box.height }.average()
        val sorted = g.sortedBy { it.box.centerY }
        val lines = mutableListOf<MutableList<OcrWord>>()
        for (w in sorted) {
            val last = lines.lastOrNull()
            val lastCy = last?.map { it.box.centerY }?.average()
            if (last != null && lastCy != null && abs(w.box.centerY - lastCy) < 0.6 * avgH) last += w
            else lines += mutableListOf(w)
        }
        val sb = StringBuilder()
        for (line in lines) {
            val s = line.sortedBy { it.box.left }.joinToString(" ") { it.text.trim() }
            // Перенос слова через дефис в конце строки: "ПРИВЕ-" + "ТИК" -> "ПРИВЕТИК"
            if (sb.length >= 2 && sb.last() in "-‐‑" && sb[sb.length - 2].isLetter() &&
                s.firstOrNull()?.isLetter() == true
            ) {
                sb.setLength(sb.length - 1)
                sb.append(s)
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
        val text = sb.toString().replace(Regex("\\s+"), " ").trim()
        return fixCase(text)
    }

    /**
     * В переводах манхвы часто пишут ЗАГЛАВНЫМИ. Синтезатор может читать короткие
     * слова капсом как аббревиатуры («ТЫ» -> «тэ-ы»), поэтому переводим в обычный регистр.
     */
    fun fixCase(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return s
        val upper = letters.count { it.isUpperCase() }
        if (upper < letters.length * 0.7) return s
        val lower = s.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (ch in lower) {
            if (capNext && ch.isLetter()) { sb.append(ch.uppercaseChar()); capNext = false }
            else sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…') capNext = true
        }
        return sb.toString()
    }
}

/**
 * Помнит реплики, прочитанные на последних экранах, чтобы после прокрутки не читать их снова.
 * Помнит только несколько последних экранов: повтор той же фразы дальше по главе — это
 * новая реплика, и её нужно прочитать.
 */
class TextMemory(private val screensWindow: Int = 3) {
    private class Entry(val norm: String, val screen: Int)

    private val items = ArrayList<Entry>()
    private var screen = 0

    fun norm(s: String): String = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    /** Начался новый экран (после прокрутки). */
    @Synchronized
    fun nextScreen() {
        screen++
        items.removeAll { it.screen < screen - screensWindow }
    }

    /**
     * Что из реплики ещё не звучало:
     *  - null — уже прочитана (или это кусок прочитанной);
     *  - продолжение — если раньше прочиталось только её начало (пузырь был обрезан краем экрана);
     *  - вся реплика — если она новая.
     */
    @Synchronized
    fun unread(text: String): String? {
        val n = norm(text)
        if (n.length < 2) return null
        // 1) почти та же фраза
        for (e in items) {
            if (e.norm == n) return null
            if (abs(e.norm.length - n.length) <= 3 && ratio(e.norm, n) >= 0.8) return null
        }
        // 2) кусок уже прочитанной фразы (обрезана краем экрана)
        for (e in items) if (n.length >= 6 && e.norm.contains(n)) return null
        // 3) продолжение: раньше прочиталось только начало
        for (e in items.asReversed()) {
            if (e.norm.length >= 4 && n.length >= e.norm.length + 3) {
                tailAfterPrefix(text, e.norm)?.let { return it }
            }
        }
        // 4) в целом похожа на прочитанную
        for (e in items) {
            val short = min(e.norm.length, n.length)
            val long = max(e.norm.length, n.length)
            if (short >= long * 0.6 && ratio(e.norm, n) >= 0.75) return null
        }
        return text
    }

    @Synchronized
    fun seen(s: String): Boolean = unread(s) == null

    @Synchronized
    fun add(s: String) {
        val n = norm(s)
        if (n.isEmpty()) return
        items += Entry(n, screen)
        if (items.size > 300) items.removeAt(0)
    }

    @Synchronized
    fun clear() = items.clear()

    /** Если текст начинается с уже прочитанного начала — вернуть оставшиеся слова. */
    private fun tailAfterPrefix(text: String, oldNorm: String): String? {
        val words = text.split(' ').filter { it.isNotBlank() }
        val acc = StringBuilder()
        for (k in words.indices) {
            acc.append(norm(words[k]))
            if (acc.length >= oldNorm.length) {
                val r = ratio(acc.toString(), oldNorm)
                return if (r >= 0.8 && k + 1 < words.size) words.drop(k + 1).joinToString(" ") else null
            }
        }
        return null
    }

    private fun ratio(a: String, b: String): Double {
        val long = max(a.length, b.length)
        if (long == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / long
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
