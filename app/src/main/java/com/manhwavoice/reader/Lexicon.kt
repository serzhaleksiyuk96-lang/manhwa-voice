package com.manhwavoice.reader

/**
 * Частотный словарь русских словоформ (300 тысяч самых частых, по данным wordfreq).
 * Нужен, чтобы отличать настоящие слова от мусора с рисунка и исправлять слова,
 * где распознавание ошиблось в одной-двух буквах.
 *
 * Хранится компактно (≈8 МБ): все слова одной строкой, отсортированы, поиск — двоичный.
 * Слова в нижнем регистре, «ё» заменена на «е». Ранг: 0 — самое частое слово.
 */
class Lexicon private constructor(
    private val chars: CharArray,
    private val offsets: IntArray,   // начало каждого слова в chars; последний элемент — конец
    private val ranks: IntArray
) {
    val size get() = ranks.size

    fun key(word: String) = word.lowercase().replace('ё', 'е')

    /** Частотный ранг слова или null, если слова нет. */
    fun rankOf(word: String): Int? {
        val k = key(word)
        var lo = 0
        var hi = ranks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(mid, k)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return ranks[mid]
            }
        }
        return null
    }

    private fun compareAt(i: Int, s: String): Int {
        val start = offsets[i]
        val len = offsets[i + 1] - start
        val n = minOf(len, s.length)
        for (j in 0 until n) {
            val d = chars[start + j] - s[j]
            if (d != 0) return d
        }
        return len - s.length
    }

    operator fun contains(word: String): Boolean {
        val k = key(word)
        if (rankOf(k) != null) return true
        // составные через дефис: «кто-то», «во-первых» — каждая часть должна быть словом
        if ('-' in k) return k.split('-').all { it.isNotEmpty() && rankOf(it) != null }
        return false
    }

    /** Ближайшее частое слово: отличие в одну букву (для длинных слов — до двух). */
    fun correct(word: String): String? {
        val k = key(word)
        if (k.length < 4) return null
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        val e1 = edits1(k, inserts = true)
        for (c in e1) {
            val r = rankOf(c) ?: continue
            if (r < bestRank) { bestRank = r; best = c }
        }
        if (best == null && k.length >= 6) {
            for (c in e1) for (c2 in edits1(c, inserts = false)) {
                val r = rankOf(c2) ?: continue
                if (r < bestRank) { bestRank = r; best = c2 }
            }
            // правка в две буквы — только к достаточно частому слову (для коротких слов — к очень частому)
            if (bestRank > (if (k.length >= 8) 100_000 else 40_000)) best = null
        }
        return best
    }

    /** Частое слово (ранг не больше [maxRank]), отличающееся ровно одной буквой, или null. */
    fun correct1(word: String, maxRank: Int): String? {
        val k = key(word)
        var best: String? = null
        var bestRank = maxRank + 1
        for (c in edits1(k, inserts = true)) {
            val r = rankOf(c) ?: continue
            if (r < bestRank) { bestRank = r; best = c }
        }
        return best
    }

    /** Все варианты разбиения слипшегося слова на два слова: «непозволю» → («не», «позволю»). */
    fun splits(word: String): List<Pair<String, String>> {
        val k = key(word)
        if (k.length < 3) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 1 until k.length) {
            val a = k.substring(0, i)
            val b = k.substring(i)
            if (rankOf(a) != null && rankOf(b) != null) out += a to b
        }
        return out
    }

    private fun edits1(w: String, inserts: Boolean): Set<String> {
        val out = HashSet<String>()
        for (i in 0..w.length) {
            val l = w.substring(0, i)
            val r = w.substring(i)
            if (r.isNotEmpty()) out += l + r.substring(1)                                  // удалить
            if (r.length > 1) out += l + r[1] + r[0] + r.substring(2)                      // переставить
            for (c in ALPHABET) {
                if (r.isNotEmpty() && c != r[0]) out += l + c + r.substring(1)             // заменить
                if (inserts) out += l + c + r                                              // вставить
            }
        }
        out.remove(w)
        return out
    }

    companion object {
        private const val ALPHABET = "абвгдежзийклмнопрстуфхцчшщъыьэюя"

        /** Из строк «слово<TAB>ранг», отсортированных по слову (файл ru_words.tsv.gz). */
        fun fromSortedTsv(lines: Sequence<String>): Lexicon {
            val sb = StringBuilder(3_000_000)
            var offs = IntArray(1 shl 16)
            var rks = IntArray(1 shl 16)
            var n = 0
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                if (n + 1 >= offs.size) { offs = offs.copyOf(offs.size * 2); rks = rks.copyOf(rks.size * 2) }
                offs[n] = sb.length
                sb.append(line, 0, tab)
                rks[n] = line.substring(tab + 1).trim().toIntOrNull() ?: Int.MAX_VALUE
                n++
            }
            offs[n] = sb.length
            val chars = CharArray(sb.length)
            sb.getChars(0, sb.length, chars, 0)
            return Lexicon(chars, offs.copyOf(n + 1), rks.copyOf(n))
        }

        /** Из списка слов в порядке частоты (для проверок на компьютере). */
        fun fromFrequencyList(words: Sequence<String>): Lexicon {
            val pairs = words.mapIndexed { i, w -> w.lowercase().replace('ё', 'е') to i }
                .distinctBy { it.first }.sortedBy { it.first }
            return fromSortedTsv(pairs.map { "${it.first}\t${it.second}" })
        }
    }
}
