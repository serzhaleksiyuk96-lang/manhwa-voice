package com.manhwavoice.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Распознавание текста. Два экземпляра Tesseract, каждый в своём потоке:
 *  - точная модель (tessdata_best) — для речевых пузырей: их мало и они маленькие, поэтому быстро;
 *  - быстрая модель (tessdata_fast) — для всего остального экрана и контурного шрифта.
 * Пузыри распознаются и озвучиваются сразу, а остальной экран обрабатывается параллельно.
 */
class OcrEngine(private val context: Context) {

    /** Подготовленный снимок экрана. */
    class Frame(
        val work: Bitmap,
        val gray: IntArray,
        val w: Int,
        val h: Int,
        val topLimit: Int?,
        val bottomLimit: Int?,
        /** На экране нет ничего, кроме фона (промежуток между кадрами) */
        val blank: Boolean,
        /** Доля тёмных пикселей — для решения, нужен ли проход контурного шрифта */
        val darkShare: Float,
        val bubbles: List<Box>,
        /** Вырезанные заранее пузыри (копии), с координатами левого верхнего угла */
        val crops: List<Triple<Int, Int, Bitmap>>
    )

    private val bestExecutor = Executors.newSingleThreadExecutor()
    private val fastExecutor = Executors.newSingleThreadExecutor()
    val bestDispatcher = bestExecutor.asCoroutineDispatcher()
    val fastDispatcher = fastExecutor.asCoroutineDispatcher()

    @Volatile private var best: TessBaseAPI? = null   // трогать только в bestExecutor
    @Volatile private var fast: TessBaseAPI? = null   // трогать только в fastExecutor

    /** Загружает обе модели (параллельно). */
    suspend fun ensureReady(): Boolean = coroutineScope {
        val a = async(bestDispatcher) {
            if (best == null) best = open("tessdata/rus.traineddata", "best", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            best != null
        }
        val b = async(fastDispatcher) {
            if (fast == null) fast = open("tessdata_fast/rus.traineddata", "fast", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            fast != null
        }
        a.await() && b.await()
    }

    private fun open(asset: String, name: String, psm: Int): TessBaseAPI? = try {
        // Модель из прошлых версий приложения больше не нужна
        File(context.filesDir, "tesseract").takeIf { it.exists() }?.deleteRecursively()

        val baseDir = File(context.filesDir, "tess_$name")
        val dataDir = File(baseDir, "tessdata").apply { mkdirs() }
        val model = File(dataDir, "rus.traineddata")
        val assetSize = context.assets.openFd(asset).use { it.length }
        if (!model.exists() || model.length() != assetSize) {
            val tmp = File(dataDir, "rus.traineddata.tmp")
            context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            model.delete()
            tmp.renameTo(model)
        }
        val t = TessBaseAPI()
        if (!t.init(baseDir.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY)) {
            t.recycle()
            null
        } else {
            t.setPageSegMode(psm)
            t.setVariable("user_defined_dpi", "300")
            t
        }
    } catch (e: Exception) {
        Log.e(TAG, "OCR init failed ($name)", e)
        null
    }

    /**
     * Готовит снимок: уменьшает до ширины 1080, закрашивает кнопку и строку состояния,
     * считает яркость, проверяет, не пустой ли экран, и находит пузыри.
     */
    fun prepare(
        src: Bitmap,
        exclude: Rect?,
        statusBarPx: Int,
        skipTopCut: Boolean,
        skipBottomCut: Boolean
    ): Frame {
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

        val n = w * h
        val argb = IntArray(n)
        work.getPixels(argb, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        var dark = 0
        val gray = IntArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            val g = (((p shr 16) and 0xff) * 30 + ((p shr 8) and 0xff) * 59 + (p and 0xff) * 11) / 100
            gray[i] = g
            hist[g]++
            if (g <= 128) dark++
        }
        // Пустой экран: почти все пиксели одного цвета (белый или чёрный промежуток)
        var mode = 0
        for (i in 1 until 256) if (hist[i] > hist[mode]) mode = i
        var different = 0
        for (i in 0 until 256) if (abs(i - mode) > 40) different += hist[i]
        val blank = different < 250

        val topLimit = if (skipTopCut) (statusBarPx * scale + h * 0.015f).roundToInt() else null
        val bottomLimit = if (skipBottomCut) (h * 0.94f).roundToInt() else null

        val bubbles = if (blank) emptyList() else BubbleFinder.find(gray, w, h)
        val crops = bubbles.map { b ->
            val m = 8
            val x0 = (b.left - m).coerceAtLeast(0)
            val y0 = (b.top - m).coerceAtLeast(0)
            val x1 = (b.right + m).coerceAtMost(w)
            val y1 = (b.bottom + m).coerceAtMost(h)
            var crop = Bitmap.createBitmap(work, x0, y0, x1 - x0, y1 - y0)
            if (crop === work) crop = work.copy(Bitmap.Config.ARGB_8888, false)
            Triple(x0, y0, crop)
        }
        return Frame(work, gray, w, h, topLimit, bottomLimit, blank, dark / n.toFloat(), bubbles, crops)
    }

    /** Пузыри — точной моделью. Вызывать в bestDispatcher. */
    fun recognizeBubbles(frame: Frame): List<Phrase> {
        val t = best
        val words = ArrayList<OcrWord>()
        for ((x0, y0, crop) in frame.crops) {
            if (t != null) {
                for (wd in runTess(t, crop)) {
                    val b = wd.box
                    words += wd.copy(box = Box(b.left + x0, b.top + y0, b.right + x0, b.bottom + y0))
                }
            }
            crop.recycle()
        }
        return TextLayout.buildPhrases(words, frame.h, frame.topLimit, frame.bottomLimit)
    }

    /**
     * Всё, что вне пузырей, — быстрой моделью; на тёмных экранах ещё и контурный шрифт.
     * Вызывать в fastDispatcher. Освобождает картинку кадра.
     */
    fun recognizeRest(frame: Frame): List<Phrase> {
        val t = fast ?: run { frame.work.recycle(); return emptyList() }
        try {
            // Проход 1: экран без пузырей (их уже читает точная модель)
            if (frame.bubbles.isNotEmpty()) {
                val canvas = Canvas(frame.work)
                val white = Paint().apply { color = Color.WHITE }
                for (b in frame.bubbles) {
                    canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), white)
                }
            }
            val phrasesA = TextLayout.buildPhrases(runTess(t, frame.work), frame.h, frame.topLimit, frame.bottomLimit)

            // Проход 2: тёмные буквы с белой обводкой на тёмном фоне — только на тёмных экранах
            var phrasesB = emptyList<Phrase>()
            if (frame.darkShare >= OutlineFix.MIN_BACKGROUND) {
                val fixed = OutlineFix.process(frame.gray, frame.w, frame.h).pixels
                if (fixed != null) {
                    val px = IntArray(fixed.size) { val v = fixed[it]; Color.rgb(v, v, v) }
                    val bmpB = Bitmap.createBitmap(px, frame.w, frame.h, Bitmap.Config.ARGB_8888)
                    phrasesB = TextLayout.buildPhrases(runTess(t, bmpB), frame.h, frame.topLimit, frame.bottomLimit)
                    bmpB.recycle()
                }
            }
            val merged = TextLayout.mergePhrases(phrasesA, phrasesB)
            Log.d(TAG, "rest: A=${phrasesA.map { it.text }} B=${phrasesB.map { it.text }}")
            return merged
        } catch (e: Exception) {
            Log.e(TAG, "rest pass failed", e)
            return emptyList()
        } finally {
            frame.work.recycle()
        }
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
        bestExecutor.execute { best?.recycle(); best = null }
        fastExecutor.execute { fast?.recycle(); fast = null }
        bestExecutor.shutdown()
        fastExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ManhwaOCR"
    }
}
