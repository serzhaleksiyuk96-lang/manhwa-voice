package com.manhwavoice.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Распознавание текста на телефоне. Вся логика — в [ScreenText] (чистый Kotlin),
 * здесь только Tesseract и подготовка снимка. Два экземпляра Tesseract, каждый в своём потоке:
 *  - точная модель (tessdata_best) — пузыри и уточнение найденного текста;
 *  - быстрая модель (tessdata_fast) — поиск текста на остальном экране.
 */
class OcrEngine(private val context: Context) {

    private val bestExecutor = Executors.newSingleThreadExecutor()
    private val fastExecutor = Executors.newSingleThreadExecutor()
    val bestDispatcher = bestExecutor.asCoroutineDispatcher()
    val fastDispatcher = fastExecutor.asCoroutineDispatcher()

    @Volatile private var best: TessBaseAPI? = null   // трогать только в bestExecutor
    @Volatile private var fast: TessBaseAPI? = null   // трогать только в fastExecutor

    private val bestFn = OcrFn { img -> best?.let { runTess(it, img) } ?: emptyList() }
    private val fastFn = OcrFn { img -> fast?.let { runTess(it, img) } ?: emptyList() }

    /** Загружает обе модели и словарь (параллельно). */
    suspend fun ensureReady(): Boolean = coroutineScope {
        val a = async(bestDispatcher) {
            if (best == null) best = open("tessdata/rus.traineddata", "best", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            best != null
        }
        val b = async(fastDispatcher) {
            if (fast == null) fast = open("tessdata_fast/rus.traineddata", "fast", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            fast != null
        }
        val lex = async(Dispatchers.Default) { loadLexicon() }
        lex.await()
        a.await() && b.await()
    }

    private fun loadLexicon() {
        if (TextLayout.lexicon != null) return
        try {
            val t0 = System.currentTimeMillis()
            // Сборщик Android сам распаковывает .gz в ресурсах (ru_words.tsv.gz → ru_words.tsv),
            // поэтому пробуем оба варианта
            val input = runCatching { context.assets.open("ru_words.tsv") }.getOrNull()
                ?: GZIPInputStream(context.assets.open("ru_words.tsv.gz"), 1 shl 16)
            TextLayout.lexicon = input.bufferedReader().useLines { Lexicon.fromSortedTsv(it) }
            Log.d(TAG, "словарь: ${TextLayout.lexicon?.size} слов за ${System.currentTimeMillis() - t0} мс")
        } catch (e: Exception) {
            Log.e(TAG, "dictionary load failed", e)   // без словаря тоже работает, только хуже
        }
    }

    private fun open(asset: String, name: String, psm: Int): TessBaseAPI? = try {
        // Модель из старых версий приложения больше не нужна
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
     * переводит в оттенки серого, находит пузыри.
     */
    fun prepare(
        src: Bitmap,
        exclude: Rect?,
        statusBarPx: Int,
        skipTopCut: Boolean,
        skipBottomCut: Boolean
    ): Prepared {
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
        val argb = IntArray(w * h)
        work.getPixels(argb, 0, w, 0, 0, w, h)
        work.recycle()
        val gray = IntArray(argb.size) { i ->
            val p = argb[i]
            (((p shr 16) and 0xff) * 30 + ((p shr 8) and 0xff) * 59 + (p and 0xff) * 11) / 100
        }
        val topLimit = if (skipTopCut) (statusBarPx * scale + h * 0.015f).roundToInt() else null
        val bottomLimit = if (skipBottomCut) (h * 0.94f).roundToInt() else null
        return ScreenText.prepare(gray, w, h, topLimit, bottomLimit)
    }

    /** Пузыри — точной моделью. Вызывать в bestDispatcher. */
    fun readBubbles(p: Prepared): List<Phrase> = ScreenText.readBubbles(p, bestFn)

    /** Поиск текста вне пузырей — быстрой моделью. Вызывать в fastDispatcher. */
    fun detectRest(p: Prepared): List<Candidate> = try {
        ScreenText.detectRest(p, fastFn)
    } catch (e: Exception) {
        Log.e(TAG, "detect failed", e)
        emptyList()
    }

    /** Уточнение найденного текста точной моделью. Вызывать в bestDispatcher. */
    fun refine(c: List<Candidate>): List<Phrase> = try {
        ScreenText.refine(c, bestFn)
    } catch (e: Exception) {
        Log.e(TAG, "refine failed", e)
        emptyList()
    }

    private fun runTess(t: TessBaseAPI, img: GrayImage): List<OcrWord> {
        val words = ArrayList<OcrWord>()
        try {
            t.setImage(img.px, img.w, img.h, 1, img.w)
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
