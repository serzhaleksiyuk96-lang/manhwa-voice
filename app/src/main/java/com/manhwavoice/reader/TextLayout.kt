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
    private val GLUE_LAST = setOf("же", "бы", "ли", "ка", "то", "у", "в", "к", "с", "и", "а", "о", "я")
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
    /** Насколько можно доверять слову после проверки по словарю. */
    private enum class Trust { SURE, DOUBT }

    private fun checkWord(w: OcrWord): Pair<OcrWord?, Trust> {
        val lex = lexicon ?: return (if (w.conf >= 15f) w else null) to (if (w.conf >= 70f) Trust.SURE else Trust.DOUBT)
        val c = core(w.text)
        if (c.isEmpty()) return null to Trust.DOUBT
        val k = lex.key(c)
        if (c in lex) {
            // «ИЕ» вместо «НЕ»: в наклонном шрифте «Н» и «И» путаются
            if (k.length == 2 && k !in TWO_OK) {
                swapIN(lex, k, 1_000)?.takeIf { it in TWO_OK }?.let { return w.copy(text = replaceCore(w.text, c, it)) to Trust.DOUBT }
            }
            // В словаре из интернета много коротких обрывков («ра», «ня») — для 2–3 букв строже
            if (k.length == 2 && k !in TWO_OK && w.conf < 75f) return null to Trust.DOUBT
            if (k.length == 3 && (lex.rankOf(k) ?: Int.MAX_VALUE) > 20_000 && w.conf < 60f) return null to Trust.DOUBT
            // «де» вместо «не», редкие «слова» вроде «такои», «выв» (опечатки из интернета) — сомнительно
            val rank = lex.rankOf(k) ?: Int.MAX_VALUE
            // Редкое короткое слово в одной букве от очень частого — почти всегда ошибка
            // распознавания: «ТЕРЕ» → «ТЕБЕ», «РУДУ» → «БУДУ», «МАНЕ» → «МНЕ»
            if (rank > 30_000 && k.length in 3..6 && '-' !in k) {
                lex.correct1(k, 2_000)?.let { return w.copy(text = replaceCore(w.text, c, it)) to Trust.DOUBT }
            }
            val rare = (k.length == 2 && k !in TWO_OK) || (k.length == 3 && rank > 20_000) || rank > 60_000
            return w to (if (rare) Trust.DOUBT else Trust.SURE)
        }
        if (w.conf >= 60f) {
            // Уверенно, но нет в словаре: имя, редкое слово — или слипшиеся «непозволю», «выв»,
            // или одна буква прочитана не так («СОГЛАСЕМ»). Такое слово всегда сомнительно.
            if (k.length in 2..4) swapIN(lex, k, 5_000)?.let { return w.copy(text = replaceCore(w.text, c, it)) to Trust.DOUBT }
            glueSplit(lex, k)?.let { return w.copy(text = replaceCore(w.text, c, it)) to Trust.DOUBT }
            if (k.length >= 5 && '-' !in k) lex.correct1(k, 30_000)?.let { return w.copy(text = replaceCore(w.text, c, it)) to Trust.DOUBT }
            return w to Trust.DOUBT
        }
        val fixed = lex.correct(k) ?: glueSplit(lex, k)
        if (fixed != null) return w.copy(text = replaceCore(w.text, c, fixed)) to Trust.DOUBT
        return (if (w.conf >= 30f && c.length >= 4) w else null) to Trust.DOUBT
    }

    /** Одна «И» ↔ «Н» — и выходит частое слово (не реже [maxRank]): «ИЕ» → «НЕ», «ИЕТ» → «НЕТ». */
    private fun swapIN(lex: Lexicon, k: String, maxRank: Int): String? {
        var best: String? = null
        var bestRank = maxRank + 1
        for (i in k.indices) {
            val alt = when (k[i]) { 'и' -> 'н'; 'н' -> 'и'; else -> continue }
            val cand = k.substring(0, i) + alt + k.substring(i + 1)
            val r = lex.rankOf(cand) ?: continue
            if (r < bestRank) { best = cand; bestRank = r }
        }
        return best
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
    fun blockPhrase(rawWords: List<OcrWord>, box: Box): Phrase? = blockPhraseChecked(rawWords, box).phrase

    private val NUMBER = Regex("^[0-9]{1,4}[.,!?…]*$")

    /** Число (уверенно прочитанное): синтезатор прочитает его словами. */
    private fun isNumber(w: OcrWord) = w.conf >= 70f && NUMBER.matches(w.text)

    /** Есть ли среди распознанного хоть что-то похожее на слова (иначе это обрывок рисунка). */
    fun looksLikeText(rawWords: List<OcrWord>): Boolean {
        val lex = lexicon
        var score = 0
        for (w in rawWords) {
            val c = core(cleanWord(w.text))
            if (c.length < 2 || c.count { isCyr(it) } < c.count { it.isLetter() } * 0.6) continue
            if (c.length >= 3 && (lex == null || c in lex)) score += 2
            else if (w.conf >= 50f) score += 1
        }
        return score >= 2
    }

    /** Реплика и признак «сомнительно»: тогда её стоит перечитать точной моделью. */
    class Checked(val phrase: Phrase?, val doubtful: Boolean)

    fun blockPhraseChecked(rawWords: List<OcrWord>, box: Box): Checked {
        val withLetters = rawWords.count { w -> w.text.any { it.isLetter() } }
        // Числа («из 4 человек») оставляем; прочие значки без букв посреди текста — признак,
        // что слово прочитано неверно («ВЫ» → «86/»): тогда стоит перечитать точной моделью
        val numbers = rawWords.filter { isNumber(it) }.toSet()
        val words = rawWords.map { it.copy(text = cleanWord(it.text)) }
        val cleaned = mergeHyphenated(words.filterIndexed { i, w -> w.text.any { it.isLetter() } || rawWords[i] in numbers })
        if (cleaned.none { w -> w.text.any { it.isLetter() } }) return Checked(null, withLetters > 0)
        var doubtful = rawWords.any { w -> w.text.isNotBlank() && w.text.none { it.isLetter() } && w !in numbers &&
            w.text.any { it.isLetterOrDigit() || it in "/\\|©®@#№%&*<>[]{}=+^_~" } }
        // буквы в словах, которых нет среди частых (редкое слово, имя, исправленное слово)
        var doubtLetters = 0
        val heights = cleaned.map { it.box.height }.sorted()
        val med = heights[heights.size / 2].toDouble()
        val kept = ArrayList<OcrWord>()
        for (w in cleaned) {
            if (w.text.none { it.isLetter() }) { kept += w; continue }   // число
            val t = w.text
            val letters = t.count { it.isLetter() }
            val cyr = t.count { isCyr(it) }
            if (cyr < letters * 0.6) { doubtful = true; continue }
            // слово сильно выше или ниже соседей — скорее кусок рисунка, если только это не настоящее слово
            if (cleaned.size >= 3 && (w.box.height > med * 2.3 || w.box.height < med * 0.35) && wordClass(w) < 2) {
                doubtful = true; continue
            }
            val lc = t.lowercase().filter { it.isLetter() }
            if (lc.length == 1) {
                if (lc in SINGLE_OK && w.conf >= 20f) kept += w else doubtful = true
                if (w.conf < 60f) doubtful = true
                continue
            }
            if (lc.length >= 4 && lc.toSet().size == 1) { doubtful = true; continue }
            val (cw, trust) = checkWord(w)
            if (cw == null) doubtful = true
            else if (trust == Trust.DOUBT) {
                doubtLetters += lc.length
                // короткие слова решают смысл («не» / «ие»): сомнение в них — повод перечитать
                if (lc.length <= 3) doubtful = true
            }
            cw?.let { kept += it }
        }
        if (kept.isEmpty()) return Checked(null, true)
        val letters = kept.sumOf { w -> w.text.count { it.isLetter() } }
        if (letters < 2) return Checked(null, true)
        if (kept.size == 1) {
            val lc = kept[0].text.lowercase().filter { it.isLetter() }
            if (lc.length <= 2 && (lc !in SHORT_OK || kept[0].conf < 70f)) return Checked(null, true)
        }
        // Мусор с рисунка: почти ничего не похоже на слова, и распознано неуверенно
        val lex = lexicon
        if (lex != null) {
            val known = kept.sumOf { w -> val c = core(w.text); if (c in lex) c.length else 0 }
            if (known < letters * 0.35 && kept.map { it.conf }.average() < 55) return Checked(null, true)
        }
        val text = joinLines(kept)
        if (text.isBlank()) return Checked(null, true)
        val conf = kept.map { it.conf }.average()
        // Длинная реплика с одним редким словом — не повод перечитывать её целиком точной моделью
        if (conf < 65 || doubtLetters > letters * 0.12 || (letters < 12 && doubtLetters > 0)) doubtful = true
        return Checked(Phrase(text, box, conf), doubtful)
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
     * Похоже ли на реплику (строже, чем [plausible]): в ней должны быть настоящие слова
     * от трёх букв, и они — основная часть текста. Короткий ответ из служебных слов
     * («Да?», «Ах!») — только если прочитан уверенно. Обрывки рисунка («У у», «Кит вы в хо»)
     * так отсеиваются.
     */
    fun speechLike(text: String, conf: Double): Boolean {
        val lex = lexicon ?: return true
        var total = 0
        var strong = 0
        var weak = 0
        for (raw in text.split(' ')) {
            val c = core(raw)
            if (c.isEmpty()) continue
            val len = c.count { it.isLetter() }
            total += len
            val k = lex.key(c)
            when {
                k.length == 1 -> if (k in SINGLE_OK) weak += len
                k.length == 2 -> if (k in TWO_OK || k in SHORT_OK) weak += len
                k.length == 3 -> if ((lex.rankOf(k) ?: Int.MAX_VALUE) <= 20_000) strong += len
                else -> if (c in lex) strong += len
            }
        }
        if (total == 0) return false
        if (strong >= 3 && strong * 3 >= total && (strong + weak) * 2 > total && (strong >= 6 || weak <= strong)) return true
        return strong == 0 && weak == total && total <= 4 && conf >= 75
    }

    /** Есть ли среди распознанного настоящие слова: одно от 4 букв или два от 3. */
    fun hasRealWords(rawWords: List<OcrWord>): Boolean {
        val lex = lexicon ?: return looksLikeText(rawWords)
        var long = 0
        var three = 0
        for (w in rawWords) {
            val c = core(cleanWord(w.text))
            if (c.count { isCyr(it) } < c.length * 0.8) continue
            val k = lex.key(c)
            if (k.length >= 4 && c in lex) long++
            else if (k.length == 3 && (lex.rankOf(k) ?: Int.MAX_VALUE) <= 20_000) three++
        }
        return long >= 1 || three >= 2
    }

    /** Насколько слово похоже на настоящее: 2 — частое, 1 — есть в словаре, 0 — нет. */
    private fun wordClass(w: OcrWord): Int {
        val lex = lexicon ?: return 1
        val c = core(cleanWord(w.text))
        if (c.isEmpty() || c.count { isCyr(it) } < c.length * 0.8) return 0
        val k = lex.key(c)
        return when {
            k.length == 1 -> if (k in SINGLE_OK) 2 else 0
            k.length == 2 -> if (k in TWO_OK) 2 else 0
            '-' in k -> {
                val parts = k.split('-').filter { it.isNotEmpty() }
                when {
                    parts.isNotEmpty() && parts.all { (lex.rankOf(it) ?: Int.MAX_VALUE) <= 60_000 } -> 2
                    c in lex -> 1
                    else -> 0
                }
            }
            else -> {
                val r = lex.rankOf(k)
                when {
                    r != null && r <= (if (k.length == 3) 20_000 else 60_000) -> 2
                    c in lex -> 1
                    else -> 0
                }
            }
        }
    }

    private fun overlapShare(a: Box, b: Box): Double {
        val ix = min(a.right, b.right) - max(a.left, b.left)
        val iy = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0.0
        val small = min(a.width.toLong() * a.height, b.width.toLong() * b.height).coerceAtLeast(1)
        return ix.toLong() * iy / small.toDouble()
    }

    /**
     * Объединяет слова быстрой ([fast]) и точной ([best]) моделей, прочитанные с одной вырезки:
     * слова сопоставляются по месту, из пары берётся настоящее слово (по словарю), при равенстве —
     * более уверенное (точной модели — небольшое преимущество). Строки, которые нашла только
     * одна модель, тоже попадают в результат.
     */
    fun combine(fast0: List<OcrWord>, best0: List<OcrWord>): List<OcrWord> = combine(fast0, best0, null, null)

    fun combine(
        fast0: List<OcrWord>, best0: List<OcrWord>,
        tailsF: java.util.IdentityHashMap<OcrWord, Box>?, tailsB: java.util.IdentityHashMap<OcrWord, Box>?
    ): List<OcrWord> {
        // значки без букв не участвуют: они не должны «занимать» место настоящего слова (числа — да)
        val fast = fast0.filter { w -> w.text.any { it.isLetter() } || isNumber(w) }
        val best = best0.filter { w -> w.text.any { it.isLetter() } || isNumber(w) }
        val n = fast.size + best.size
        // группы слов, лежащих на одном месте (модели могут по-разному делить на слова)
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        fun boxes(w: OcrWord, t: java.util.IdentityHashMap<OcrWord, Box>?) = listOfNotNull(w.box, t?.get(w))
        for ((i, wa) in fast.withIndex()) for ((j, wb) in best.withIndex()) {
            val same = boxes(wa, tailsF).any { a -> boxes(wb, tailsB).any { b -> overlapShare(a, b) >= 0.5 } }
            if (same) {
                val a = find(i); val b = find(fast.size + j)
                if (a != b) parent[a] = b
            }
        }
        val out = ArrayList<OcrWord>()
        for (g in (0 until n).groupBy { find(it) }.values) {
            val fs = g.filter { it < fast.size }.map { fast[it] }
            val bs = g.filter { it >= fast.size }.map { best[it - fast.size] }
            when {
                fs.isEmpty() -> out += bs
                bs.isEmpty() -> fs.filterTo(out) { it.conf >= 50 && wordClass(it) >= 1 }
                fs.size == 1 && bs.size == 1 && abs(letterCount(fs[0]) - letterCount(bs[0])) < 3 -> {
                    val wa = fs[0]; val wb = bs[0]
                    // настоящее слово важнее уверенности; из двух настоящих — более частое
                    // («БУДУ», а не «РУДУ»); точная модель при прочих равных надёжнее
                    val sa = wordClass(wa) * 100 + freqBonus(wa) + wa.conf
                    val sb = wordClass(wb) * 100 + freqBonus(wb) + wb.conf + 20
                    out += if (sa > sb) wa else wb
                }
                // разная разбивка на слова: берём ту, где больше букв в настоящих словах
                else -> out += if (sideScore(fs) > sideScore(bs) + 1) fs else bs
            }
        }
        return out
    }

    private fun freqBonus(w: OcrWord): Int {
        val lex = lexicon ?: return 0
        val c = core(cleanWord(w.text))
        if (c.isEmpty()) return 0
        val r = lex.rankOf(lex.key(c)) ?: return 0
        return when { r <= 1_000 -> 30; r <= 10_000 -> 15; else -> 0 }
    }

    private fun letterCount(w: OcrWord) = w.text.count { it.isLetter() }

    /** Сколько букв в настоящих словах минус буквы в ненастоящих. */
    private fun sideScore(ws: List<OcrWord>): Int = ws.sumOf { w ->
        val letters = w.text.count { it.isLetter() }
        when (wordClass(w)) { 2 -> letters; 1 -> letters / 2; else -> -letters }
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
        val avgH = medianHeight(g)
        val res = mutableListOf<MutableList<OcrWord>>()
        for (w in g.sortedBy { it.box.centerY }) {
            val last = res.lastOrNull()
            val lastCy = last?.map { it.box.centerY }?.average()
            if (last != null && lastCy != null && abs(w.box.centerY - lastCy) < 0.6 * avgH) last += w
            else res += mutableListOf(w)
        }
        return res.map { l -> l.sortedBy { it.box.left } }
    }

    // Частицы, которые пишутся через дефис: «как-нибудь», «кто-то», «всё-таки»
    private val HYPHEN_PARTICLES = setOf("нибудь", "либо", "то", "таки", "ка", "де")

    /**
     * Склеивает слово, разорванное переносом: «СИЛЬНЕЙ-» + «ШИЙ» → «СИЛЬНЕЙШИЙ», а слова с
     * настоящим дефисом оставляет с ним: «КАК-» + «НИБУДЬ» → «КАК-НИБУДЬ», «ИЗ-» + «ЗА» → «ИЗ-ЗА».
     * Если дефис потерялся («КОТО» + «РЫЙ»), а вместе получается частое слово — тоже склеивает.
     * Делается до проверки по словарю: половинки слов в словаре не найдутся.
     */
    fun mergeHyphenated(words: List<OcrWord>): List<OcrWord> = mergeHyphenated(words, null)

    /**
     * То же, но для каждого склеенного слова запоминает рамку второй половинки ([tails]):
     * по ней слово сопоставляется со словами другой модели, которые склеены не были.
     */
    fun mergeHyphenated(words: List<OcrWord>, tails: java.util.IdentityHashMap<OcrWord, Box>?): List<OcrWord> {
        val ls = lines(words)
        if (ls.size < 2) return words
        val out = ArrayList<OcrWord>()
        var carry: OcrWord? = null
        var soft: OcrWord? = null
        for ((i, line) in ls.withIndex()) {
            for ((j, w0) in line.withIndex()) {
                var w = w0
                if (j == 0 && carry != null) {
                    if (w.text.firstOrNull()?.isLetter() == true) {
                        // рамка — от первой половинки, чтобы слово осталось в конце строки
                        val tail = w.box
                        w = OcrWord(joinParts(carry.text.dropLast(1), w.text), carry.box, min(carry.conf, w.conf))
                        tails?.put(w, tail)
                    } else out += carry
                    carry = null
                }
                if (j == 0 && soft != null) {
                    val joined = softJoin(soft.text, w.text)
                    if (joined != null) {
                        val tail = w.box
                        w = OcrWord(joined, soft.box, min(soft.conf, w.conf))
                        tails?.put(w, tail)
                    } else out += soft
                    soft = null
                }
                val isLast = j == line.size - 1
                val t = w.text
                if (isLast && i < ls.size - 1 && t.length >= 2 && t.last() in "-‐‑" && t[t.length - 2].isLetter()) {
                    carry = w
                } else if (isLast && i < ls.size - 1 && t.length >= 2 && t.all { it.isLetter() }) {
                    soft = w
                } else {
                    out += w
                }
            }
        }
        carry?.let { out += it }
        soft?.let { out += it }
        return out
    }

    /** Две половинки слова при переносе: с дефисом или без. */
    private fun joinParts(a: String, b: String): String {
        val lex = lexicon ?: return a + b
        val ca = a.filter { it.isLetter() || it == '-' }.lowercase()
        val cb = b.filter { it.isLetter() }.lowercase()
        if (ca.isEmpty() || cb.isEmpty()) return a + b
        val joinedRank = lex.rankOf(ca + cb)
        val ra = lex.rankOf(ca) ?: Int.MAX_VALUE
        val rb = lex.rankOf(cb) ?: Int.MAX_VALUE
        return when {
            cb in HYPHEN_PARTICLES && ca in lex && (joinedRank == null || joinedRank > 30_000) -> "$a-$b"
            // «ИЗ-» + «ЗА»: обе половинки — частые слова, а слитно — редкость
            ra <= 5_000 && rb <= 5_000 && (joinedRank == null || (joinedRank > 10_000 && joinedRank > 20 * max(ra, rb))) -> "$a-$b"
            joinedRank != null -> a + b
            ca.length >= 2 && ca in lex && cb.length >= 2 && cb in lex -> "$a-$b"
            else -> a + b
        }
    }

    /** Перенос без распознанного дефиса: склеить, только если вместе — гораздо более частое слово. */
    private fun softJoin(a: String, b: String): String? {
        val lex = lexicon ?: return null
        val cb = b.takeWhile { it.isLetter() }
        if (a.length < 2 || cb.length < 2) return null
        val joinedRank = lex.rankOf(a + cb) ?: return null
        if (joinedRank > 100_000) return null
        val ra = lex.rankOf(a) ?: Int.MAX_VALUE
        val rb = lex.rankOf(cb) ?: Int.MAX_VALUE
        // хотя бы одна половинка — обрывок: как самостоятельное слово она гораздо реже целого
        if (ra.toLong() <= joinedRank.toLong() * 20 && rb.toLong() <= joinedRank.toLong() * 20) return null
        return a + b
    }

    /** Склеивает слова одной реплики построчно, слева направо. */
    /** Типичная высота слова: медиана (одно слово с огромной рамкой не сливает строки). */
    private fun medianHeight(g: List<OcrWord>): Double {
        val hs = g.map { it.box.height }.sorted()
        return hs[hs.size / 2].toDouble()
    }

    fun joinLines(g: List<OcrWord>): String {
        val avgH = medianHeight(g)
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
        if (upper < letters.length * 0.5) {
            // текст в обычном регистре, но отдельные слова капсом («СЕЙЧАС») — их тоже в строчные,
            // иначе синтезатор может прочитать их по буквам
            return s.split(' ').joinToString(" ") { wd ->
                val l = wd.filter { it.isLetter() }
                if (l.length >= 2 && l.all { it.isUpperCase() }) wd.lowercase() else wd
            }
        }
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

    /** Забыть реплику (её не успели прочитать — например, нажали паузу). */
    @Synchronized
    fun remove(s: String) {
        val n = norm(s)
        val i = items.indexOfLast { it.norm == n }
        if (i >= 0) items.removeAt(i)
    }

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
