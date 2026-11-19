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
        val name = headers.firstOrNull { it.isInvoiceNameHeader() } ?: return emptyList()
        val quantity = headers.firstOrNull { it.isInvoiceQuantityHeader() }
        val headerCenter = name.y + name.height / 2
        val maxRowHeight = name.height * 5
        fun dark(x: Int, y: Int): Boolean {
            val rgb = pixels[y * width + x]
            return (rgb shr 16 and 255) < 145 && (rgb shr 8 and 255) < 145 && (rgb and 255) < 145
        }

        val horizontal = rules(height) { y ->
            var count = 0
            for (x in 0 until width) if (dark(x, y)) count++
            count * 100 >= width * 55
        }.filter { it.last - it.first < name.height / 2 }
        val top = horizontal.lastOrNull { it.last < headerCenter } ?: return emptyList()
        val headerBottomIndex = horizontal.indexOfFirst { it.first > headerCenter }
        if (headerBottomIndex < 0) return emptyList()
        val headerBottom = horizontal[headerBottomIndex]
        if (headerBottom.first - top.last > maxRowHeight) return emptyList()
        val rowRules = mutableListOf(headerBottom)
        for (rule in horizontal.drop(headerBottomIndex + 1)) {
            if (rule.first - rowRules.last().last !in (name.height / 2)..maxRowHeight) break
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
        // The title can be centered in a wide cell. The last open cell may be cut off by the screenshot.
        val nameColumn = vertical.indexOfLast { it.last < name.x + name.width / 2 }
        if (nameColumn < 1) return emptyList()
        fun cell(column: Int, top: Int, bottom: Int): Cell = Cell(
            vertical[column].last + 1, top, vertical.getOrNull(column + 1)?.first ?: width, bottom,
        )
        val closedColumns = (0 until vertical.lastIndex).filter { it != nameColumn }
        val quantityColumn = closedColumns.singleOrNull { column ->
            val header = cell(column, scanTop, headerBottom.first)
            quantity != null && quantity.x >= header.left && quantity.x + quantity.width <= header.right
        } ?: closedColumns.singleOrNull { column ->
            isInvoiceQuantityLabel(recognizeHeader(cell(column, scanTop, headerBottom.first)))
        }

        return rowRules.zipWithNext().map { (above, below) ->
            Row(
                cell(nameColumn, above.last + 1, below.first),
                quantityColumn?.let { cell(it, above.last + 1, below.first) },
            )
        }
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
