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

        val words = ArrayList<OcrWord>()
        try {
            t.setImage(work)
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
            work.recycle()
        }

        val topLimit = if (skipTopCut) (statusBarPx * scale + h * 0.015f).roundToInt() else null
        val bottomLimit = if (skipBottomCut) (h * 0.94f).roundToInt() else null
        val phrases = TextLayout.buildPhrases(words, h, topLimit, bottomLimit)
        Log.d(TAG, "words=${words.size} phrases=${phrases.map { it.text }}")
        return phrases
    }

    fun release() {
        tess?.recycle()
        tess = null
    }

    companion object {
        private const val TAG = "ManhwaOCR"
    }
}
