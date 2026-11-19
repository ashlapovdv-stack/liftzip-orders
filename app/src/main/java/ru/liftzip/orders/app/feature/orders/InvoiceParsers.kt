package ru.liftzip.orders.app.feature.orders

import kotlin.math.abs

/** Candidate rows are never saved without user review. */
data class InvoiceItem(val name: String, val quantity: String)

internal data class InvoiceWord(val text: String, val x: Int, val y: Int, val width: Int, val height: Int)

private fun InvoiceWord.label() = text.trim('|', '[', ']', ' ').lowercase()
internal fun InvoiceWord.isInvoiceNameHeader() = label().startsWith("наименован") || label() == "товары"
internal fun InvoiceWord.isInvoiceQuantityHeader() = isInvoiceQuantityLabel(label())

internal fun isInvoiceQuantityLabel(text: String): Boolean {
    val label = text.trim('|', '[', ']', ' ', '\n').lowercase()
        .replace('o', 'о').replace(Regex("\\s+"), "").replace('–', '-').replace('—', '-')
    return label.startsWith("кол-во") || label.startsWith("колво") ||
        label.startsWith("количество") || label in setOf("кол", "кол.") || label.startsWith("к-во")
}

/** Parse the table by column position, keeping an unknown quantity editable. */
internal object InvoiceTableParser {
    private val rowNumber = Regex("[1-9][0-9]{0,2}")
    private val quantity = Regex("[1-9][0-9]{0,5}(?:[.,][0-9]+)?")
    private val footer = Regex("""^(?:итого|всего|в том числе|ндс|к оплате|внимание)(?:$|[\s:])""", RegexOption.IGNORE_CASE)
    private val itemCount = Regex("""всего\s+наименований\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val normalizedName = Regex("[^\\p{L}\\p{N}]")

    private val InvoiceWord.right get() = x + width
    private val InvoiceWord.centerX get() = x + width / 2.0
    private val InvoiceWord.centerY get() = y + height / 2.0
    private fun InvoiceWord.isNumberHeader() = label() == "№" || label() == "n"

    private fun rows(words: List<InvoiceWord>): List<List<InvoiceWord>> {
        if (words.isEmpty()) return emptyList()
        val medianHeight = words.map { it.height }.sorted()[words.size / 2]
        val rows = mutableListOf<MutableList<InvoiceWord>>()
        for (word in words.sortedBy { it.centerY }) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.map { it.centerY }.average() - word.centerY) <= medianHeight * 0.65) {
                last.add(word)
            } else {
                rows.add(mutableListOf(word))
            }
        }
        return rows.map { row -> row.sortedBy { it.x } }
    }

    private fun List<InvoiceWord>.isHeader() =
        any { it.isInvoiceNameHeader() } && any { it.isInvoiceQuantityHeader() }

    private fun List<InvoiceWord>.isFooter() =
        footer.containsMatchIn(joinToString(" ") { it.label() }.trimStart())

    fun parse(words: List<InvoiceWord>): List<InvoiceItem> {
        return parseRows(rows(words))
    }

    private fun parseRows(rows: List<List<InvoiceWord>>): List<InvoiceItem> {
        val headerIndex = rows.indexOfFirst { it.isHeader() }
        if (headerIndex < 0) return emptyList()
        val header = rows[headerIndex]
        val nameHeader = header.first { it.isInvoiceNameHeader() }
        val quantityHeader = header.first { it.isInvoiceQuantityHeader() }
        val numberHeader = header.firstOrNull { it.isNumberHeader() }
        val numericHeaders = header.filter { word ->
            word.isInvoiceQuantityHeader() || listOf("цена", "ед.", "сумма", "стоимость", "ндс")
                .any { word.label().startsWith(it) }
        }
        val left = numericHeaders.lastOrNull { it.x < quantityHeader.x }
        val right = numericHeaders.firstOrNull { it.x > quantityHeader.x }
        val quantityLeft = left?.let { (it.right + quantityHeader.x) / 2.0 }
            ?.coerceAtMost(quantityHeader.x.toDouble()) ?: (quantityHeader.x - quantityHeader.height).toDouble()
        val quantityRight = right?.x ?: quantityHeader.right + quantityHeader.height
        val nameRight = numericHeaders.firstOrNull { it.x > nameHeader.x }?.x ?: return emptyList()
        val nameLeft = numberHeader?.let { it.right + it.height / 2.0 } ?: 0.0
        val result = mutableListOf<InvoiceItem>()
        val nameParts = mutableListOf<String>()
        val quantities = mutableSetOf<String>()
        var numbered = false

        fun finish() {
            val name = nameParts.joinToString(" ")
            if (name.any(Char::isLetter) && (numbered || quantities.isNotEmpty())) {
                result += InvoiceItem(name, quantities.singleOrNull().orEmpty())
            }
            nameParts.clear()
            quantities.clear()
            numbered = false
        }

        for (row in rows.drop(headerIndex + 1)) {
            if (row.isFooter()) break
            val marker = numberHeader?.let { label ->
                row.firstOrNull {
                    it.centerX in (label.x - label.width).toDouble()..nameLeft && rowNumber.matches(it.label())
                }
            }
            if (marker != null) {
                finish()
                numbered = true
            }
            val names = row.filter { it != marker && it.centerX > nameLeft && it.centerX < nameRight && it.right <= nameRight + it.height }
                .map { it.text.trim('|', '[', ']', '_', '‚', ' ') }
                .filter { it.any(Char::isLetterOrDigit) }
            val rowQuantities = row.filter { it.centerX > quantityLeft && it.centerX < quantityRight }
                .map { it.label() }
                .filter(quantity::matches)
            if (numberHeader == null && names.isNotEmpty() && rowQuantities.isNotEmpty() && quantities.isNotEmpty()) {
                finish()
            }
            nameParts += names
            quantities += rowQuantities
        }
        finish()
        return result
    }

    fun resolve(text: String, words: List<InvoiceWord>): List<InvoiceItem> {
        val rows = rows(words)
        val positioned = parseRows(rows)
        val textual = InvoiceTextParser.parse(text)
        if (positioned.isEmpty()) return textual
        val hasNumberColumn = rows.firstOrNull { it.isHeader() }?.any { it.isNumberHeader() } == true
        if (!hasNumberColumn && textual.size >= positioned.size && textual.all { it.quantity.isNotEmpty() }) {
            return textual
        }
        if (positioned.size < textual.size &&
            positioned.count { it.quantity.isNotEmpty() } <= textual.count { it.quantity.isNotEmpty() }
        ) return textual

        fun key(item: InvoiceItem) = item.name.lowercase().replace(normalizedName, "")
        val textualByName = textual.groupBy(::key)
        val positionedByName = positioned.groupingBy(::key).eachCount()
        return positioned.map { item ->
            if (item.quantity.isNotEmpty()) return@map item
            val name = key(item)
            val match = textualByName[name]?.singleOrNull()
            if (positionedByName[name] == 1 && match != null) item.copy(quantity = match.quantity) else item
        }
    }

    fun tableHeader(words: List<InvoiceWord>): List<InvoiceWord> {
        val rows = rows(words)
        return rows.firstOrNull { it.isHeader() }
            ?: rows.firstOrNull { row -> row.any { it.isInvoiceNameHeader() } }
            .orEmpty()
    }

    fun expectedCount(text: String): Int? = itemCount.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun tableBand(words: List<InvoiceWord>): IntRange? {
        val rows = rows(words)
        val header = rows.indexOfFirst { it.isHeader() }
        if (header < 0) return null
        val margin = rows[header].maxOf { it.height }
        val top = rows[header].minOf { it.y } - margin
        val footer = rows.drop(header + 1).firstOrNull { it.isFooter() }
        val bottom = footer?.maxOf { it.y + it.height }?.plus(margin)
        return top..(bottom ?: Int.MAX_VALUE)
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

