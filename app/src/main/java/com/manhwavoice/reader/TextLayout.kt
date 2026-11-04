package com.manhwavoice.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Чистая логика без зависимостей от Android: фильтрация слов из OCR,
 * группировка их в реплики (пузыри), порядок чтения и защита от повторов.
 */

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2
    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

data class OcrWord(val text: String, val box: Box, val conf: Float)

data class Phrase(val text: String, val box: Box)

object TextLayout {

    private val CYR = Regex("[А-Яа-яЁё]")
    // Однобуквенные русские слова, которые стоит оставлять
    private val SINGLE_OK = setOf("и", "а", "в", "я", "с", "к", "у", "о", "ж", "б")

    /** Оставляем только похожее на русский текст, мусор с рисунка отбрасываем. */
    fun keepWord(w: OcrWord, screenH: Int): Boolean {
        val t = w.text.trim()
        if (t.isEmpty()) return false
        val h = w.box.height
        if (h < 10 || h > screenH * 0.12) return false
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

    /** Группирует слова в реплики и возвращает их в порядке чтения (сверху вниз). */
    fun buildPhrases(
        rawWords: List<OcrWord>,
        screenH: Int,
        topCutLimit: Int?,      // если не null — пропускаем реплики, обрезанные сверху
        bottomCutLimit: Int?    // если не null — пропускаем реплики, обрезанные снизу
    ): List<Phrase> {
        val words = rawWords.filter { keepWord(it, screenH) }
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
            if (topCutLimit != null && box.top < topCutLimit) continue
            if (bottomCutLimit != null && box.bottom > bottomCutLimit) continue
            val text = joinLines(g)
            if (text.isNotBlank()) phrases += Phrase(text, box)
        }
        return phrases.sortedWith(compareBy({ it.box.top }, { it.box.left }))
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

/** Помнит недавно прочитанные реплики, чтобы не читать одно и то же после прокрутки. */
class TextMemory(private val capacity: Int = 40) {
    private val items = ArrayDeque<String>()

    fun norm(s: String): String = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    @Synchronized
    fun seen(s: String): Boolean {
        val n = norm(s)
        if (n.length < 2) return true
        return items.any { similar(n, it) }
    }

    @Synchronized
    fun add(s: String) {
        val n = norm(s)
        if (n.isEmpty()) return
        items.addLast(n)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun clear() = items.clear()

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        // Кусок реплики, обрезанной краем экрана
        if (short.length >= 6 && long.contains(short)) return true
        if (short.length < long.length * 0.6) return false
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / long.length >= 0.75
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
