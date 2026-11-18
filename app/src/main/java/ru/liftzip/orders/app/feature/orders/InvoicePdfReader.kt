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
    private val header = Regex("""(?:наименован|товары\s*\(|товары\b)""", RegexOption.IGNORE_CASE)
    private val stop = Regex("""^(?:итого|всего|ндс|к оплате|в том числе)""", RegexOption.IGNORE_CASE)
    private val numbered = Regex("""^\s*\|?\s*(\d{1,3})[.)]?\s*[|\[]?\s*(.+)$""")
    private val money = Regex("""\d[\d ]*[.,]\d{2}""")
    private val pricedEnd = Regex(
        """\s+\d[\d ]*[.,]\d{2}(?:\s+(\d{1,6}))?\s*(?:шт\.?|штук|компл\.?|pcs?\.?)[!|]?\s*\d[\d ]*[.,]\d{2}\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val unitPrices = Regex(
        """\s+(?:шт\.?|штук|компл\.?|pcs?\.?)[!|]?\s*\d[\d ]*[.,]\d{2}(?:\s+\d[\d ]*[.,]\d{2})?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val twoPrices = Regex("""\s+\d[\d ]*[.,]\d{2}\s+\d[\d ]*[.,]\d{2}\s*$""")
    // Screenshot tables often place the quantity between the price and unit,
    // while PDF OCR can omit the quantity entirely.
    private val priceQuantityUnit = Regex(
        """\s+\d[\d ]*[.,]\d{2}\s*\|?\s*([1-9]\d{0,5})\s*(?:шт\.?|штук|компл\.?|pcs?\.?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val rowMarker = Regex("""^\s*[^\p{L}\d]{0,3}\s*(\d{1,3})\s+(.+)$""")
    private val numberedProduct = Regex("""^\s*\|?\s*\d{1,3}[.)]?\s*[|\[]?\s*[^\d\s].*""")

    fun parse(text: String): List<InvoiceItem> {
        val lines = text.lines().map { it.trim().replace(Regex("""\s+"""), " ") }
        val headerIndex = lines.indexOfFirst {
            header.containsMatchIn(it) &&
                (it.contains("кол", true) || it.contains("цена", true) || it.contains("сумма", true))
        }
        val end = lines.indexOfFirst { stop.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        // Without a legible header, start only at a numbered row whose following
        // lines contain a price and total. Never start at a postal index or bank account.
        val start = if (headerIndex >= 0) headerIndex + 1 else (0 until end).firstOrNull { i ->
            numberedProduct.matches(lines[i]) &&
                lines.subList(i, minOf(i + 4, end)).any {
                    pricedEnd.containsMatchIn(it) || unitPrices.containsMatchIn(it) || twoPrices.containsMatchIn(it)
                }
        } ?: return emptyList()
        if (start >= end) return emptyList()

        val results = mutableListOf<InvoiceItem>()
        val pending = mutableListOf<String>()
        var active = false
        fun finish(quantity: String = "") {
            val name = pending.joinToString(" ").trim().trim('[', '|', ' ', '‚', '_')
                .replace(Regex("""\s+"""), " ")
            if (name.length >= 4 && name.any(Char::isLetter)) results += InvoiceItem(name, quantity)
            pending.clear()
            active = false
        }
        for (line in lines.subList(start, end)) {
            if (line.isBlank()) continue
            val numberedMatch = numbered.matchEntire(line) ?: rowMarker.matchEntire(line)
            val isNew = numberedMatch != null &&
                numberedMatch.groupValues[2].any(Char::isLetter) &&
                !money.containsMatchIn(numberedMatch.groupValues[2].take(15))
            val stripped = if (isNew) {
                if (active && pending.isNotEmpty()) finish()
                active = true
                numberedMatch!!.groupValues[2]
            } else line.trimStart('|', ' ', '‚', '_')
            // A missing row number is common in scanned invoices. Require a
            // recognized table header or an already verified product row.
            if (!active && (headerIndex >= 0 || results.isNotEmpty()) &&
                stripped.any(Char::isLetter) && stripped.length >= 4) active = true
            if (!active) continue
            val quantityMatch = priceQuantityUnit.find(stripped)
            if (quantityMatch != null) {
                pending += stripped.substring(0, quantityMatch.range.first)
                finish(quantityMatch.groupValues[1])
                continue
            }
            val priced = pricedEnd.find(stripped)
            if (priced != null) {
                pending += stripped.substring(0, priced.range.first)
                finish(priced.groupValues[1])
                continue
            }
            val unitMatch = unitPrices.find(stripped)
            if (unitMatch != null) {
                val before = stripped.substring(0, unitMatch.range.first)
                val trailingPrice = money.findAll(before).lastOrNull()
                pending += if (trailingPrice != null && trailingPrice.range.last == before.length - 1)
                    before.substring(0, trailingPrice.range.first) else before
                finish()
                continue
            }
            val priceMatch = twoPrices.find(stripped)
            if (priceMatch != null) {
                pending += stripped.substring(0, priceMatch.range.first)
                finish()
                continue
            }
            // OCR may keep the product description and its price but drop the
            // unit or total. Keep only the description, never the price.
            val trailingPrice = money.findAll(stripped).lastOrNull()
            val description = if (trailingPrice != null &&
                trailingPrice.range.last == stripped.length - 1)
                stripped.substring(0, trailingPrice.range.first).trimEnd(' ', '|')
            else stripped
            if (description.any(Char::isLetter) && description.length > 2) pending += description
        }
        // Incomplete OCR rows without a recognized price are not imported.
        return results
    }
}

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
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val sample = generateSequence(1) { it * 2 }.takeWhile {
                        bounds.outWidth / it > 2400 || bounds.outHeight / it > 3200
                    }.lastOrNull()?.times(2) ?: 1
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("Не удалось открыть изображение")
                try {
                    tess.setImage(bitmap)
                    val recognized = tess.getUTF8Text() ?: ""
                    val parsed = InvoiceTextParser.parse(recognized)
                    if (diagnostics) InvoiceDiagnostics.record(context, "image",
                        "chars=${recognized.length}; items=${parsed.size}\\nOCR text:\\n$recognized")
                    if (diagnostics) InvoiceDiagnostics.record(context, "complete", "items=${parsed.size}")
                    parsed
                } finally { bitmap.recycle() }
            } finally { tess.recycle() }
        }

    private fun extractWords(tess: TessBaseAPI): List<InvoiceWord> {
        val iterator = tess.resultIterator ?: return emptyList()
        val words = mutableListOf<InvoiceWord>()
        val level = com.googlecode.tesseract.android.PageIteratorLevel.RIL_WORD
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
        } finally { iterator.recycle() }
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
                                    tess.setImage(bitmap)
                                    val recognized = tess.getUTF8Text() ?: ""
                                    val parsed = InvoiceTextParser.parse(recognized)
                                    val positioned = InvoiceTableParser.parse(extractWords(tess))
                                    val merged = if (positioned.size == parsed.size && parsed.isNotEmpty()) {
                                        parsed.zip(positioned) { textItem, tableItem ->
                                            if (textItem.quantity.isBlank()) textItem.copy(quantity = tableItem.quantity)
                                            else textItem
                                        }
                                    } else parsed
                                    if (diagnostics) InvoiceDiagnostics.record(context, "coordinates",
                                        "page=${index + 1}; textItems=${parsed.size}; positionedItems=${positioned.size}; quantities=${positioned.map { it.quantity }}")
                                    if (diagnostics) InvoiceDiagnostics.record(context, "page", "page=${index + 1}; chars=${recognized.length}; items=${parsed.size}\nOCR text:\n$recognized")
                                    if (merged.isEmpty()) {
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
                                        all += merged
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
