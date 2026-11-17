package ru.liftzip.orders.app.feature.orders

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Candidate rows are never saved without user review. */
data class InvoiceItem(val name: String, val quantity: String)

internal data class InvoiceWord(val text: String, val x: Int, val y: Int, val width: Int, val height: Int)

/** OCR coordinates, not a fixed invoice template: supports different column order. */
internal object InvoiceTableParser {
    fun parse(words: List<InvoiceWord>): List<InvoiceItem> {
        if (words.isEmpty()) return emptyList()
        val ordered = words.sortedWith(compareBy<InvoiceWord> { it.y }.thenBy { it.x })
        val medianHeight = ordered.map { it.height }.sorted().let { it[it.size / 2] }.coerceAtLeast(8)
        val rows = mutableListOf<MutableList<InvoiceWord>>()
        ordered.forEach { word ->
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row.map { it.y }.average() - word.y) <= medianHeight * 0.6) row.add(word)
            else rows.add(mutableListOf(word))
        }
        val headerIndex = rows.indexOfFirst { row ->
            val text = row.joinToString(" ") { it.text }.lowercase()
            ("наименован" in text || "товары" in text) && ("кол" in text || "количество" in text)
        }
        if (headerIndex < 0) return emptyList()
        val header = rows[headerIndex]
        val nameHeader = header.firstOrNull { "наименован" in it.text.lowercase() || "товары" in it.text.lowercase() } ?: return emptyList()
        val qtyHeader = header.firstOrNull { it.text.lowercase().startsWith("кол") }
            ?: return emptyList()
        val numberHeader = header.firstOrNull { it.text == "№" || it.text == "N" }
        val qtyX = qtyHeader.x + qtyHeader.width / 2
        val nameX = nameHeader.x
        val nameRight = if (qtyX > nameX) qtyHeader.x else
            header.filter { it.x > nameX && it.x < qtyHeader.x }.minOfOrNull { it.x } ?: Int.MAX_VALUE
        val results = mutableListOf<InvoiceItem>()
        var currentName = StringBuilder()
        var currentQuantity = ""
        fun finish() {
            val name = currentName.toString().trim().replace(Regex("\\s+"), " ")
            if (name.isNotEmpty() && currentQuantity.matches(Regex("[1-9][0-9]{0,5}"))) {
                results.add(InvoiceItem(name, currentQuantity))
            }
            currentName = StringBuilder()
            currentQuantity = ""
        }
        rows.drop(headerIndex + 1).forEach { row ->
            val full = row.sortedBy { it.x }.joinToString(" ") { it.text }.lowercase()
            if (listOf("итого", "всего", "ндс", "оплате", "внимание").any { full.contains(it) }) {
                finish()
                return results
            }
            val rowNumber = numberHeader?.let { h ->
                row.filter { it.x < nameX && it.x >= h.x - 20 }.firstOrNull {
                    it.text.matches(Regex("[0-9]{1,3}"))
                }
            }
            if (rowNumber != null && currentName.isNotEmpty()) finish()
            val names = row.filter { it.x >= nameX - 10 && it.x < nameRight - 5 }
                .filterNot { it == rowNumber }.sortedBy { it.x }
                .joinToString(" ") { it.text }
            val quantities = row.filter { kotlin.math.abs(it.x + it.width / 2 - qtyX) < qtyHeader.width.coerceAtLeast(25) * 1.6 }
                .filter { it.text.matches(Regex("[1-9][0-9]{0,5}")) }
            if (names.isNotBlank()) {
                if (currentName.isNotEmpty()) currentName.append(' ')
                currentName.append(names)
            }
            if (quantities.isNotEmpty()) currentQuantity = quantities.first().text
        }
        finish()
        return results
    }
}

class InvoicePdfReader {
    suspend fun read(resolver: ContentResolver, uri: Uri): List<InvoiceItem> = withContext(Dispatchers.IO) {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("Не удалось открыть PDF")
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val all = mutableListOf<InvoiceItem>()
                    for (pageIndex in 0 until renderer.pageCount.coerceAtMost(8)) {
                        renderer.openPage(pageIndex).use { page ->
                            val scale = (1800f / page.width).coerceAtMost(3f)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val recognized = suspendCancellableCoroutine<Text> { continuation ->
                                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                        .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                                        .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                                }
                                val words = recognized.textBlocks.flatMap { block ->
                                    block.lines.flatMap { line -> line.elements.mapNotNull { element ->
                                        element.boundingBox?.let { box ->
                                            InvoiceWord(element.text, box.left, box.top, box.width(), box.height())
                                        }
                                    } }
                                }
                                all += InvoiceTableParser.parse(words)
                            } finally { bitmap.recycle() }
                        }
                    }
                    all
                } finally { recognizer.close() }
            }
        }
    }
}
