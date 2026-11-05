package com.manhwavoice.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Process
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Распознавание текста на телефоне. Вся логика — в [ScreenText] и [TextFinder] (чистый Kotlin),
 * здесь только Tesseract и подготовка снимка. Три экземпляра Tesseract, каждый в своём потоке:
 * две быстрые модели читают надписи по очереди (параллельно), точная — перечитывает сомнительное.
 */
class OcrEngine(private val context: Context) {

    private fun executor(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            // Распознавание — то, чего ждёт пользователь: повышенный приоритет
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            r.run()
        }, name)
    }

    private val fastExec = listOf(executor("ocr-fast-1"), executor("ocr-fast-2"))
    private val bestExec = executor("ocr-best")
    val fastDispatchers: List<CoroutineDispatcher> = fastExec.map { it.asCoroutineDispatcher() }
    val bestDispatcher = bestExec.asCoroutineDispatcher()

    @Volatile private var fast = arrayOfNulls<TessBaseAPI>(2)     // каждый — только в своём потоке
    @Volatile private var best: TessBaseAPI? = null               // только в bestExec

    private val fastFn = List(2) { k -> OcrFn { img -> fast[k]?.let { runTess(it, img) } ?: emptyList() } }
    private val bestFn = OcrFn { img -> best?.let { runTess(it, img) } ?: emptyList() }

    /** Уже распознанные надписи (после прокрутки не распознаются заново). */
    val cache = ItemCache()

    /** Загружает модели и словарь (параллельно). */
    suspend fun ensureReady(): Boolean = coroutineScope {
        val f = (0 until 2).map { k ->
            async(fastDispatchers[k]) {
                if (fast[k] == null) fast[k] = open("tessdata_fast/rus.traineddata", "fast")
                fast[k] != null
            }
        }
        val b = async(bestDispatcher) {
            if (best == null) best = open("tessdata/rus.traineddata", "best")
            best != null
        }
        val lex = async(Dispatchers.Default) { loadLexicon() }
        lex.await()
        f.awaitAll().all { it } && b.await()
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

    /** Копирует модель из APK в файлы приложения (один раз; для нескольких экземпляров — без гонки). */
    @Synchronized
    private fun ensureModel(asset: String, name: String): File {
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
        return baseDir
    }

    private fun open(asset: String, name: String): TessBaseAPI? = try {
        val baseDir = ensureModel(asset, name)
        val t = TessBaseAPI()
        if (!t.init(baseDir.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY)) {
            t.recycle()
            null
        } else {
            // Каждая надпись распознаётся отдельно, на чистой вырезке — как один блок текста
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            t.setVariable("user_defined_dpi", "300")
            // Иначе Tesseract выбрасывает строки с многоточием («ЛАДНО...») как «шум»
            t.setVariable("textord_noise_rejrows", "0")
            t
        }
    } catch (e: Exception) {
        Log.e(TAG, "OCR init failed ($name)", e)
        null
    }

    /**
     * Готовит снимок: рабочая копия шириной 1080 (для поиска надписей), исходный снимок —
     * для вырезок в полном разрешении. Строка состояния, кнопка (если не спряталась) и
     * панели сайта сверху и снизу закрашиваются белым и в поиск не попадают.
     * [topInset] и [bottomInset] — панели в пикселях экрана.
     */
    fun prepare(
        src: Bitmap,
        exclude: Rect?,
        statusBarPx: Int,
        topInset: Int,
        bottomInset: Int,
        skipTopCut: Boolean,
        skipBottomCut: Boolean,
        rtl: Boolean
    ): Prepared {
        val scale = if (src.width > 1200) 1080f / src.width else 1f
        val w = (src.width * scale).roundToInt()
        val h = (src.height * scale).roundToInt()
        val work = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(work).drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val argb = IntArray(w * h)
        work.getPixels(argb, 0, w, 0, 0, w, h)
        work.recycle()
        val gray = IntArray(argb.size)
        for (i in argb.indices) gray[i] = luma(argb[i])

        val regionTop = min(h - 1, max((statusBarPx * scale).roundToInt() + 2, (topInset * scale).roundToInt()))
        val regionBottom = max(regionTop + 1, h - (bottomInset * scale).roundToInt())
        java.util.Arrays.fill(gray, 0, regionTop * w, 255)
        java.util.Arrays.fill(gray, regionBottom * w, w * h, 255)
        exclude?.let { r ->
            val l = max(0, (r.left * scale).toInt()); val rr = min(w, (r.right * scale).toInt() + 1)
            for (y in max(0, (r.top * scale).toInt()) until min(h, (r.bottom * scale).toInt() + 1)) {
                java.util.Arrays.fill(gray, y * w + l, y * w + rr, 255)
            }
        }
        // Исходный снимок в полном разрешении — только яркость, по байту на точку
        val source = if (scale < 1f) {
            val sw = src.width
            val sh = src.height
            val px = ByteArray(sw * sh)
            val band = IntArray(sw * 64)
            var y = 0
            while (y < sh) {
                val rows = min(64, sh - y)
                src.getPixels(band, 0, sw, 0, y, sw, rows)
                for (i in 0 until sw * rows) px[y * sw + i] = luma(band[i]).toByte()
                y += rows
            }
            exclude?.let { r ->
                for (yy in max(0, r.top) until min(sh, r.bottom + 1)) {
                    java.util.Arrays.fill(px, yy * sw + max(0, r.left), yy * sw + min(sw, r.right + 1), -1)
                }
            }
            GraySrc.hiRes(px, sw, sh, 1f / scale)
        } else null
        return ScreenText.prepare(gray, w, h, regionTop, regionBottom, skipTopCut, skipBottomCut, rtl, source)
    }

    private fun luma(p: Int) = (((p shr 16) and 0xff) * 30 + ((p shr 8) and 0xff) * 59 + (p and 0xff) * 11) / 100

    /** Первый проход быстрой моделью [worker]. Вызывать в fastDispatchers[worker]. */
    fun firstPass(p: Prepared, item: TextItem, worker: Int): ScreenText.ItemRead =
        ScreenText.firstPass(p, item, fastFn[worker])

    /** Перечитать сомнительное точной моделью. Вызывать в bestDispatcher. */
    fun secondPass(r: ScreenText.ItemRead): Phrase? = ScreenText.secondPass(r, bestFn)

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
        for (k in 0 until 2) fastExec[k].execute { fast[k]?.recycle(); fast[k] = null }
        bestExec.execute { best?.recycle(); best = null }
        fastExec.forEach { it.shutdown() }
        bestExec.shutdown()
    }

    companion object {
        private const val TAG = "ManhwaOCR"
    }
}
