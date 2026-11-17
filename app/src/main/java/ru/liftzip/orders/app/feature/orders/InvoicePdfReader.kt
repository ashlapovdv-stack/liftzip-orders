package ru.liftzip.orders.app.feature.orders

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
    private val number = Regex("^\\d{1,3}[.)]?$")
    private val qty = Regex("^\\d{1,6}(?:[.,]0+)?$")
    fun parse(text: String): List<InvoiceItem> {
        val result = mutableListOf<InvoiceItem>()
        var inTable = false
        for (line in text.lines()) {
            val cleaned = line.trim().replace(Regex("\\s+"), " ")
            val lower = cleaned.lowercase()
            if (!inTable) {
                if (lower.contains("наименован") || lower.contains("товар")) inTable = true
                continue
            }
            if (listOf("итого", "всего", "всего к оплате", "ндс").any { lower.startsWith(it) }) break
            val tokens = cleaned.split(' ').filter { it.isNotBlank() }
            if (tokens.size < 3) continue
            val start = if (number.matches(tokens.first())) 1 else 0
            val end = tokens.indexOfLast { qty.matches(it) }
            if (end <= start) continue
            // Quantity may precede price; only accept a numeric token followed by
            // a recognizable unit or another numeric price column.
            val index = (start until end).firstOrNull { i ->
                qty.matches(tokens[i]) && (tokens.getOrNull(i + 1)?.lowercase() in
                    setOf("шт", "шт.", "штук", "компл", "компл.", "pcs", "pc") ||
                    (i + 2 < tokens.size && qty.matches(tokens[i + 1])))
            } ?: continue
            val name = tokens.subList(start, index).joinToString(" ").trim()
            val quantity = tokens[index].replace(Regex("[.,]0+$"), "")
            if (name.length > 3 && quantity.matches(Regex("[1-9]\\d{0,5}"))) {
                result += InvoiceItem(name, quantity)
            }
        }
        return result
    }
}

/** OCR stays on-device. Language packs are downloaded once, then cached privately. */
class InvoicePdfReader {
    suspend fun read(context: Context, resolver: ContentResolver, uri: Uri): List<InvoiceItem> =
        withContext(Dispatchers.IO) {
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
            val tess = TessBaseAPI()
            try {
                if (!tess.init(base.absolutePath, "rus+eng")) error("Не удалось запустить распознавание")
                val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("Не удалось открыть PDF")
                descriptor.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val all = mutableListOf<InvoiceItem>()
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
                                    all += InvoiceTextParser.parse(tess.getUTF8Text() ?: "")
                                    tess.clear()
                                } finally { bitmap.recycle() }
                            }
                        }
                        all
                    }
                }
            } finally { tess.recycle() }
        }
}
