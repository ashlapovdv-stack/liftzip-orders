package ru.liftzip.orders.app.feature.orders

/** Locate cells when ruling lines make the OCR engine merge neighboring columns. */
internal object InvoiceGrid {
    data class Cell(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Row(val name: Cell, val quantity: Cell?)

    private fun rules(length: Int, isRule: (Int) -> Boolean): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (index in 0..length) {
            if (index < length && isRule(index)) {
                if (start < 0) start = index
            } else if (start >= 0) {
                ranges += start until index
                start = -1
            }
        }
        return ranges
    }

    fun locate(
        pixels: IntArray,
        width: Int,
        height: Int,
        headers: List<InvoiceWord>,
        recognizeHeader: (Cell) -> String = { "" },
    ): List<Row> {
        val name = headers.firstOrNull { it.isInvoiceNameHeader() }
        val quantity = headers.firstOrNull { it.isInvoiceQuantityHeader() }
        fun dark(x: Int, y: Int): Boolean {
            val rgb = pixels[y * width + x]
            return (rgb shr 16 and 255) < 145 && (rgb shr 8 and 255) < 145 && (rgb and 255) < 145
        }

        val maxThickness = maxOf((name?.height ?: 0) / 2, width / 80, 4)
        val horizontal = rules(height) { y ->
            var count = 0
            for (x in 0 until width) if (dark(x, y)) count++
            count * 100 >= width * 55
        }.filter { it.last - it.first < maxThickness }

        fun locateTable(index: Int, nameInHeader: InvoiceWord?): List<Row> {
            val top = horizontal[index]
            val headerBottom = horizontal[index + 1]
            val headerHeight = headerBottom.first - top.last
            val maxRowHeight = nameInHeader?.height?.times(5) ?: (headerHeight * 3)
            if (headerHeight !in 6..minOf(maxRowHeight, height / 8)) return emptyList()
            val rowRules = mutableListOf(headerBottom)
            for (rule in horizontal.drop(index + 2)) {
                if (rule.first - rowRules.last().last !in maxOf(headerHeight / 4, 4)..maxRowHeight) break
                rowRules += rule
            }
            if (rowRules.size < 2) return emptyList()

            val scanTop = top.last + 1
            val scanBottom = rowRules.last().first
            val vertical = rules(width) { x ->
                var count = 0
                for (y in scanTop until scanBottom) if (dark(x, y)) count++
                count * 100 >= (scanBottom - scanTop) * 80
            }
            if (vertical.size < 2) return emptyList()
            fun cell(column: Int, cellTop: Int, bottom: Int): Cell = Cell(
                vertical[column].last + 1, cellTop, vertical.getOrNull(column + 1)?.first ?: width, bottom,
            )

            val headerCells = vertical.indices.associateWith { cell(it, scanTop, headerBottom.first) }
                .filterValues { it.right - it.left >= 6 }
            val recognizedHeaders = mutableMapOf<Int, String>()
            fun headerText(column: Int) = recognizedHeaders.getOrPut(column) { recognizeHeader(headerCells.getValue(column)) }

            // Read a header directly from the grid if whole-page OCR missed the table title.
            val nameColumn = nameInHeader?.let { word ->
                val center = word.x + word.width / 2
                headerCells.entries.firstOrNull { (_, cell) -> center in cell.left until cell.right }?.key
            } ?: headerCells.keys.firstOrNull { column -> column > 0 && isInvoiceNameLabel(headerText(column)) }
            if (nameColumn == null || nameColumn < 1) return emptyList()
            val closedColumns = headerCells.keys.filter { it != nameColumn && it < vertical.lastIndex }
            val quantityColumn = closedColumns.singleOrNull { column ->
                val header = headerCells.getValue(column)
                nameInHeader != null && quantity != null && quantity.x >= header.left &&
                    quantity.x + quantity.width <= header.right
            } ?: closedColumns.singleOrNull { column -> isInvoiceQuantityLabel(headerText(column)) }

            return rowRules.zipWithNext().map { (above, below) ->
                Row(
                    cell(nameColumn, above.last + 1, below.first),
                    quantityColumn?.let { cell(it, above.last + 1, below.first) },
                )
            }
        }

        val headerCenter = name?.let { it.y + it.height / 2 }
        val anchoredIndex = if (headerCenter != null) horizontal.indexOfLast { it.last < headerCenter } else -1
        if (anchoredIndex in 0 until horizontal.lastIndex) {
            val anchored = locateTable(anchoredIndex, name)
            if (anchored.isNotEmpty()) return anchored
        }
        for (index in 0 until horizontal.size - 2) {
            val table = locateTable(index, null)
            if (table.isNotEmpty()) return table
        }
        return emptyList()
    }

    private val positiveQuantity = Regex("(?:[1-9][0-9]{0,5}(?:[.,][0-9]+)?|0[.,][0-9]*[1-9][0-9]*)")

    fun quantityOrEmpty(text: String): String =
        text.trim().trim('|', '[', ']', ' ').takeIf(positiveQuantity::matches).orEmpty()

    fun read(
        rows: List<Row>,
        recognizeQuantity: (Cell) -> String,
        recognizeName: (Cell) -> String,
    ): List<InvoiceItem> = rows.mapNotNull { row ->
        val name = recognizeName(row.name).trim().replace(Regex("\\s+"), " ")
        if (!name.any(Char::isLetter)) return@mapNotNull null
        val quantity = row.quantity?.let { quantityOrEmpty(recognizeQuantity(it)) }.orEmpty()
        InvoiceItem(name, quantity)
    }
}
