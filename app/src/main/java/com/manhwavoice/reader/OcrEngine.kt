package com.manhwavoice.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.roundToInt

/**
 * Обёртка над Tesseract. Вызывать только из одного потока (TessBaseAPI не потокобезопасен).
 */
class OcrEngine(private val context: Context) {

    private var tess: TessBaseAPI? = null

    /** Копирует модель русского языка из APK и запускает Tesseract. */
    fun ensureReady(): Boolean {
        if (tess != null) return true
        return try {
            val baseDir = File(context.filesDir, "tesseract")
            val dataDir = File(baseDir, "tessdata").apply { mkdirs() }
            val model = File(dataDir, "rus.traineddata")
            val marker = File(dataDir, "rus.version")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
            if (!model.exists() || model.length() < 1000 || !marker.exists() || marker.readText() != version) {
                val tmp = File(dataDir, "rus.traineddata.tmp")
                context.assets.open("tessdata/rus.traineddata").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                model.delete()
                tmp.renameTo(model)
                marker.writeText(version)
            }
            val t = TessBaseAPI()
            if (!t.init(baseDir.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY)) {
                t.recycle()
                return false
            }
            // Режим «разреженного текста»: ищет надписи по всей картинке — то, что нужно для пузырей
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            t.setVariable("user_defined_dpi", "300")
            tess = t
            true
        } catch (e: Exception) {
            Log.e(TAG, "OCR init failed", e)
            false
        }
    }

    /**
     * Распознаёт текст на снимке экрана.
     * @param exclude область плавающей кнопки (закрашивается, чтобы не мешала)
     * @param statusBarPx высота строки состояния сверху (тоже закрашивается)
     */
    fun recognize(
        src: Bitmap,
        exclude: Rect?,
        statusBarPx: Int,
        skipTopCut: Boolean,
        skipBottomCut: Boolean
    ): List<Phrase> {
        val t = tess ?: return emptyList()

        // На экранах шире 1200px уменьшаем картинку — так быстрее, текст всё равно крупный
        val scale = if (src.width > 1200) 1080f / src.width else 1f
        val w = (src.width * scale).roundToInt()
        val h = (src.height * scale).roundToInt()
        val work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(work)
        canvas.drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val white = Paint().apply { color = Color.WHITE }
        if (statusBarPx > 0) canvas.drawRect(0f, 0f, w.toFloat(), statusBarPx * scale, white)
        exclude?.let {
            canvas.drawRect(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale, white)
        }

        val topLimit = if (skipTopCut) (statusBarPx * scale + h * 0.015f).roundToInt() else null
        val bottomLimit = if (skipBottomCut) (h * 0.94f).roundToInt() else null

        // Проход 1: картинка как есть (обычные пузыри, белый текст на тёмном)
        val wordsA = runTess(t, work)
        val phrasesA = TextLayout.buildPhrases(wordsA, h, topLimit, bottomLimit)

        val argb = IntArray(w * h)
        work.getPixels(argb, 0, w, 0, 0, w, h)
        val gray = IntArray(argb.size) { i ->
            val p = argb[i]
            (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
        }

        // Проход 2: контурный шрифт (тёмные буквы с белой обводкой на тёмном фоне)
        var phrasesB = emptyList<Phrase>()
        try {
            val fixed = OutlineFix.process(gray, w, h).pixels
            if (fixed != null) {
                val px = IntArray(fixed.size) { val v = fixed[it]; Color.rgb(v, v, v) }
                val bmpB = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                val wordsB = runTess(t, bmpB)
                bmpB.recycle()
                phrasesB = TextLayout.buildPhrases(wordsB, h, topLimit, bottomLimit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "outline pass failed", e)
        }

        // Проход 3: каждый речевой пузырь отдельно — яркая картинка вокруг не мешает
        var phrasesC = emptyList<Phrase>()
        try {
            val bubbles = BubbleFinder.find(gray, w, h)
            if (bubbles.isNotEmpty()) {
                val wordsC = ArrayList<OcrWord>()
                t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                for (b in bubbles) {
                    val m = 8
                    val x0 = (b.left - m).coerceAtLeast(0)
                    val y0 = (b.top - m).coerceAtLeast(0)
                    val x1 = (b.right + m).coerceAtMost(w)
                    val y1 = (b.bottom + m).coerceAtMost(h)
                    val crop = Bitmap.createBitmap(work, x0, y0, x1 - x0, y1 - y0)
                    for (wd in runTess(t, crop)) {
                        val bx = wd.box
                        wordsC += wd.copy(box = Box(bx.left + x0, bx.top + y0, bx.right + x0, bx.bottom + y0))
                    }
                    if (crop != work) crop.recycle()
                }
                phrasesC = TextLayout.buildPhrases(wordsC, h, topLimit, bottomLimit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bubble pass failed", e)
        } finally {
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        }
        work.recycle()

        val phrases = TextLayout.mergePhrases(TextLayout.mergePhrases(phrasesA, phrasesB), phrasesC)
        Log.d(TAG, "A=${phrasesA.map { it.text }} B=${phrasesB.map { it.text }} C=${phrasesC.map { it.text }} -> ${phrases.map { it.text }}")
        return phrases
    }

    private fun runTess(t: TessBaseAPI, bmp: Bitmap): List<OcrWord> {
        val words = ArrayList<OcrWord>()
        try {
            t.setImage(bmp)
            t.getUTF8Text()   // запускает распознавание
            val it = t.getResultIterator()
            if (it != null) {
                it.begin()
                do {
                    val txt = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (txt != null && txt.isNotBlank()) {
                        val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        words += OcrWord(txt, Box(r.left, r.top, r.right, r.bottom), conf)
                    }
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                it.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
        } finally {
            t.clear()
        }
        return words
    }

    fun release() {
        tess?.recycle()
        tess = null
    }

    companion object {
        private const val TAG = "ManhwaOCR"
    }
}
