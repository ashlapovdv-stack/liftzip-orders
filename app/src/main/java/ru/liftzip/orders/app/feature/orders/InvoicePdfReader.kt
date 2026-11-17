package ru.liftzip.orders.app.feature.orders

import ru.liftzip.orders.app.feature.diagnostics.InvoiceDiagnostics

import android.content.Context
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
        val nameRight = header.filter { word ->
            word.x > nameX && listOf("цена", "кол", "ед.", "сумма", "стоимость")
                .any { word.text.lowercase().startsWith(it) }
        }.minOfOrNull { it.x } ?: Int.MAX_VALUE
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


/** Parse the OCR text conservatively. Uncertain rows are edited before insertion. */
internal object InvoiceTextParser {
    private val stop = Regex("""^(?:итого|всего|ндс|к оплате|в том числе)""", RegexOption.IGNORE_CASE)
    private val header = Regex("""(?:наименован|товары|работы, услуги)""", RegexOption.IGNORE_CASE)
    private val numbered = Regex("""^\\s*\\|?\\s*(\\d{1,3})[.)]?\\s*[|\\[]?\\s+(.+)$""")
    private val money = Regex("""\\d[\\d ]*[.,]\\d{2}""")
    private val unit = Regex("""(?:шт\\.?|штук|компл\\.?|pcs?\\.?)""", RegexOption.IGNORE_CASE)
    private val tail = Regex(
        """\\s+\\d[\\d ]*[.,]\\d{2}(?:\\s+(\\d{1,6}))?\\s*(?:шт\\.?|штук|компл\\.?|pcs?\\.?)[!|]?\\s*\\d[\\d ]*[.,]\\d{2}\\s*$""",
        RegexOption.IGNORE_CASE
    )
    // Some scans lose both quantity and the price column, but keep unit + total.
    private val unitTotal = Regex(
        """\\s+(?:шт\\.?|штук|компл\\.?|pcs?\\.?)[!|]?\\s*\\d[\\d ]*[.,]\\d{2}\\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): List<InvoiceItem> {
        val lines = text.lines().map { it.trim().replace(Regex("""\\s+"""), " ") }
        val headerIndex = lines.indexOfFirst { header.containsMatchIn(it) &&
            (it.contains("кол", true) || it.contains("цена", true) || it.contains("сумма", true)) }
        // A corrupted table header can be skipped by OCR. A numbered product line
        // is accepted only when a plausible price/unit/total line follows it.
        val firstRow = if (headerIndex >= 0) headerIndex + 1 else lines.indexOfFirst {
            numbered.matches(it) && it.any(Char::isLetter) &&
                !it.contains("ИНН", true) && !it.contains("сч.", true)
        }
        if (firstRow < 0) return emptyList()
        val result = mutableListOf<InvoiceItem>()
        val pending = mutableListOf<String>()
        var active = false
        fun finish(quantity: String = "") {
            val name = pending.joinToString(" ").trim().trim('[', '|', ' ', '‚', '_')
                .replace(Regex("""\\s+"""), " ")
            if (name.length >= 4 && name.any(Char::isLetter)) result += InvoiceItem(name, quantity)
            pending.clear()
            active = false
        }
        for (line in lines.drop(firstRow)) {
            if (line.isBlank()) continue
            if (stop.containsMatchIn(line)) break
            val match = numbered.matchEntire(line)
            val stripped = if (match != null && match.groupValues[2].any(Char::isLetter)) {
                if (active) finish()
                active = true
                match.groupValues[2]
            } else line.trimStart('|', ' ', '‚', '_')
            if (!active) continue
            val tailMatch = tail.find(stripped)
            if (tailMatch != null) {
                pending += stripped.substring(0, tailMatch.range.first)
                finish(tailMatch.groupValues[1])
                continue
            }
            val unitMatch = unitTotal.find(stripped)
            if (unitMatch != null) {
                val before = stripped.substring(0, unitMatch.range.first)
                val priceMatch = money.findAll(before).lastOrNull()
                pending += if (priceMatch != null && priceMatch.range.last == before.length - 1)
                    before.substring(0, priceMatch.range.first) else before
                finish()
                continue
            }
            // Do not append table fragments consisting only of OCR noise or numbers.
            if (stripped.any(Char::isLetter) && stripped.length > 2) pending += stripped
        }
        if (active) finish()
        return result
    }
}

/** OCR stays on-device. Language packs are downloaded once, then cached privately. */
class InvoicePdfReader {
    suspend fun read(context: Context, resolver: ContentResolver, uri: Uri): List<InvoiceItem> =
        withContext(Dispatchers.IO) {
            val diagnostics = InvoiceDiagnostics.enabled(context)
            if (diagnostics) InvoiceDiagnostics.record(context, "start", "PDF import started")
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
                                    tess.setImage(bitmap)
                                    val recognized = tess.getUTF8Text() ?: ""
                                    val parsed = InvoiceTextParser.parse(recognized)
                                    if (diagnostics) InvoiceDiagnostics.record(context, "page", "page=${index + 1}; chars=${recognized.length}; items=${parsed.size}\nOCR text:\n$recognized")
                                    if (parsed.isEmpty()) {
                                        // Re-run OCR on the middle table region when full-page
                                        // recognition lost the small product row among page noise.
                                        // No PDF templates or invoice-specific coordinates.
                                        val top = (bitmap.height * 0.27f).toInt()
                                        val height = (bitmap.height * 0.58f).toInt()
                                            .coerceAtMost(bitmap.height - top)
                                        val tableBitmap = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                                        try {
                                            tess.clear()
                                            tess.setImage(tableBitmap)
                                            val tableText = tess.getUTF8Text() ?: ""
                                            val tableItems = InvoiceTextParser.parse(tableText)
                                            if (diagnostics) InvoiceDiagnostics.record(context, "table_retry",
                                                "page=${index + 1}; chars=${tableText.length}; items=${tableItems.size}\\nOCR text:\\n$tableText")
                                            all += tableItems
                                        } finally { tableBitmap.recycle() }
                                    } else {
                                        all += parsed
                                    }
                                    tess.clear()
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
