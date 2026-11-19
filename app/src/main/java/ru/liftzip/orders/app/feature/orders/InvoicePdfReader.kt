package ru.liftzip.orders.app.feature.orders

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

    /** Share the OCR and table retry across PDF pages and gallery images. */
    private fun recognizePage(
        tess: TessBaseAPI,
        bitmap: Bitmap,
        report: ((String) -> Unit)?
    ): List<InvoiceItem> {
        val page = recognize(tess, bitmap, TessBaseAPI.PageSegMode.PSM_AUTO)
        report?.invoke("page words=${page.words.size}; rows=${page.items.size}; withQuantity=${page.completeItems}")
        val expected = InvoiceTableParser.expectedCount(page.text)
        if (page.items.isNotEmpty() && page.completeItems == page.items.size &&
            (expected == null || page.items.size >= expected)
        ) return page.items

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
        return if (retry.items.size >= page.items.size &&
            (retry.completeItems > page.completeItems ||
                retry.items.size > page.items.size && retry.completeItems == page.completeItems)
        ) retry.items else page.items
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
