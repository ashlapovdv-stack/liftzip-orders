package ru.liftzip.orders.app.feature.orders

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.liftzip.orders.app.feature.diagnostics.InvoiceDiagnostics
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** OCR stays on-device. Language packs are downloaded once, then cached privately. */
class InvoicePdfReader {
    private fun prepareModels(context: Context, diagnostics: Boolean): File {
        val base = File(context.filesDir, "invoice-ocr")
        val models = File(base, "tessdata").apply { mkdirs() }
        for (language in listOf("rus", "eng")) {
            val target = File(models, "$language.traineddata")
            if (!target.exists() || target.length() < 100_000) {
                val temp = File(models, "$language.download")
                try {
                    val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$language.traineddata")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 45000
                    try {
                        if (connection.responseCode != 200) error("Не удалось загрузить языковую модель $language")
                        connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    } finally { connection.disconnect() }
                    if (temp.length() < 100_000 || !temp.renameTo(target)) error("Не удалось сохранить языковую модель")
                } finally { temp.delete() }
            }
        }
        if (diagnostics) InvoiceDiagnostics.record(context, "models", "rus and eng models available; rus=${File(models, "rus.traineddata").length()} bytes, eng=${File(models, "eng.traineddata").length()} bytes")
        return base
    }

    private data class OcrPass(val text: String, val words: List<InvoiceWord>) {
        val items = InvoiceTableParser.resolve(text, words)
        val completeItems = items.count { it.quantity.isNotEmpty() }
    }

    private fun recognize(tess: TessBaseAPI, bitmap: Bitmap, mode: Int): OcrPass {
        tess.clear()
        tess.setPageSegMode(mode)
        tess.setImage(bitmap)
        val text = tess.getUTF8Text().orEmpty()
        return OcrPass(text, extractWords(tess))
    }

    private fun recognizeGrid(tess: TessBaseAPI, bitmap: Bitmap, words: List<InvoiceWord>): List<InvoiceItem> {
        val header = InvoiceTableParser.tableHeader(words)
        if (header.isEmpty()) return emptyList()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rows = InvoiceGrid.locate(pixels, bitmap.width, bitmap.height, header)
        return InvoiceGrid.read(rows) { cell ->
            val source = Rect(cell.left, cell.top, cell.right, cell.bottom)
            // Leave the antialiased fringe of the ruling lines outside the OCR image.
            if (source.width() > 2 && source.height() > 2) source.inset(1, 1)
            val padding = (source.height() / 3).coerceAtLeast(12)
            val crop = Bitmap.createBitmap(source.width() + padding * 2, source.height() + padding * 2, Bitmap.Config.ARGB_8888)
            try {
                crop.eraseColor(Color.WHITE)
                val target = Rect(padding, padding, padding + source.width(), padding + source.height())
                Canvas(crop).drawBitmap(bitmap, source, target, null)
                tess.clear()
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                tess.setImage(crop)
                tess.getUTF8Text().orEmpty()
            } finally {
                crop.recycle()
            }
        }
    }

    /** Share the OCR and table retry across PDF pages and gallery images. */
    private fun recognizePage(
        tess: TessBaseAPI,
        bitmap: Bitmap,
        report: ((String) -> Unit)?
    ): List<InvoiceItem> {
        val page = recognize(tess, bitmap, TessBaseAPI.PageSegMode.PSM_AUTO)
        report?.invoke("page words=${page.words.size}; rows=${page.items.size}; withQuantity=${page.completeItems}")
        val expected = InvoiceTableParser.expectedCount(page.text)
        fun complete(items: List<InvoiceItem>): Boolean = items.isNotEmpty() &&
            items.all { it.quantity.isNotEmpty() } && (expected == null || items.size >= expected)

        fun atLeastAsComplete(candidates: List<InvoiceItem>, previous: List<InvoiceItem>): Boolean =
            candidates.size >= previous.size &&
                candidates.count { it.quantity.isNotEmpty() } >= previous.count { it.quantity.isNotEmpty() }

        if (complete(page.items)) return page.items
        val grid = recognizeGrid(tess, bitmap, page.words)
        report?.invoke("cells rows=${grid.size}; withQuantity=${grid.count { it.quantity.isNotEmpty() }}")
        val gridPreferred = grid.isNotEmpty() && atLeastAsComplete(grid, page.items)
        val best = if (gridPreferred) grid else page.items
        if (complete(best)) return best

        val band = InvoiceTableParser.tableBand(page.words)
        val top = band?.first?.coerceIn(0, bitmap.height - 1) ?: 0
        val bottom = band?.last?.coerceIn(top + 1, bitmap.height) ?: bitmap.height
        val canCrop = top > 0 || bottom < bitmap.height
        val retry = if (canCrop) {
            val cropped = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
            try {
                recognize(tess, cropped, TessBaseAPI.PageSegMode.PSM_AUTO)
            } finally {
                cropped.recycle()
            }
        } else {
            recognize(tess, bitmap, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        }
        report?.invoke("retry words=${retry.words.size}; rows=${retry.items.size}; withQuantity=${retry.completeItems}")
        // Keep a coherent pass so continued descriptions and their quantities stay together.
        return if (atLeastAsComplete(retry.items, best) &&
            (retry.items.size > best.size || retry.completeItems > best.count { it.quantity.isNotEmpty() })
        ) retry.items else best
    }

    suspend fun readImage(context: Context, resolver: ContentResolver, uri: Uri): List<InvoiceItem> =
        withContext(Dispatchers.IO) {
            val diagnostics = InvoiceDiagnostics.enabled(context)
            if (diagnostics) InvoiceDiagnostics.record(context, "start", "Gallery image import started")
            val base = prepareModels(context, diagnostics)
            val tess = TessBaseAPI()
            try {
                if (!tess.init(base.absolutePath, "rus+eng")) error("Не удалось запустить распознавание")
                val bitmap = resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val sample = generateSequence(1) { it * 2 }.takeWhile {
                        bounds.outWidth / it > 2400 || bounds.outHeight / it > 3200
                    }.lastOrNull()?.times(2) ?: 1
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("Не удалось открыть изображение")
                try {
                    val report: ((String) -> Unit)? = if (diagnostics) {
                        { message -> InvoiceDiagnostics.record(context, "image", message) }
                    } else null
                    recognizePage(tess, bitmap, report)
                } finally { bitmap.recycle() }
            } finally { tess.recycle() }
        }

    private fun extractWords(tess: TessBaseAPI): List<InvoiceWord> {
        val iterator = tess.resultIterator ?: return emptyList()
        val words = mutableListOf<InvoiceWord>()
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        try {
            do {
                val value = iterator.getUTF8Text(level)?.trim().orEmpty()
                val box = iterator.getBoundingBox(level)
                if (value.isNotEmpty() && box != null && box.size >= 4) {
                    words += InvoiceWord(value, box[0], box[1],
                        (box[2] - box[0]).coerceAtLeast(1),
                        (box[3] - box[1]).coerceAtLeast(1))
                }
            } while (iterator.next(level))
        } finally { iterator.delete() }
        return words
    }

    suspend fun read(context: Context, resolver: ContentResolver, uri: Uri): List<InvoiceItem> =
        withContext(Dispatchers.IO) {
            val diagnostics = InvoiceDiagnostics.enabled(context)
            if (diagnostics) InvoiceDiagnostics.record(context, "start", "PDF import started")
            val base = prepareModels(context, diagnostics)
            val tess = TessBaseAPI()
            try {
                if (!tess.init(base.absolutePath, "rus+eng")) error("Не удалось запустить распознавание")
                val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("Не удалось открыть PDF")
                descriptor.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val all = mutableListOf<InvoiceItem>()
                        if (diagnostics) InvoiceDiagnostics.record(context, "pages", "pages=${renderer.pageCount}")
                        for (index in 0 until renderer.pageCount.coerceAtMost(8)) {
                            renderer.openPage(index).use { page ->
                                val scale = (2200f / page.width).coerceAtMost(3f)
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * scale).toInt().coerceAtLeast(1),
                                    (page.height * scale).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888
                                )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val report: ((String) -> Unit)? = if (diagnostics) {
                                        { message -> InvoiceDiagnostics.record(context, "page", "page=${index + 1}; $message") }
                                    } else null
                                    all += recognizePage(tess, bitmap, report)
                                } finally { bitmap.recycle() }
                            }
                        }
                        if (diagnostics) InvoiceDiagnostics.record(context, "complete", "items=${all.size}")
                        all
                    }
                }
            } finally { tess.recycle() }
        }
}
