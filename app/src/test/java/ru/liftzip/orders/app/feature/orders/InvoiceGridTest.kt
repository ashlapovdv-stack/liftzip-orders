package ru.liftzip.orders.app.feature.orders

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceGridTest {
    private fun grid(
        width: Int,
        quantityHeader: InvoiceWord? = InvoiceWord("Кол-во", 174, 94, 32, 12),
        recognizeHeader: (InvoiceGrid.Cell) -> String = { "" },
    ): List<InvoiceGrid.Row> {
        val height = 170
        val pixels = IntArray(width * height) { -1 }
        for (y in 90..150) {
            for (x in 10 until minOf(280, width)) {
                if (y in listOf(90, 110, 130, 150) || x in listOf(10, 40, 170, 209, 279)) {
                    pixels[y * width + x] = 0
                }
            }
        }
        return InvoiceGrid.locate(
            pixels, width, height,
            listOfNotNull(InvoiceWord("Товары", 80, 94, 48, 12), quantityHeader)
                .filter { it.x < width },
            recognizeHeader,
        )
    }

    @Test fun borderedTableReadsQuantityFromSeparateCells() {
        val rows = grid(300)
        assertEquals(
            listOf(
                InvoiceGrid.Row(InvoiceGrid.Cell(41, 111, 170, 130), InvoiceGrid.Cell(171, 111, 209, 130)),
                InvoiceGrid.Row(InvoiceGrid.Cell(41, 131, 170, 150), InvoiceGrid.Cell(171, 131, 209, 150)),
            ),
            rows,
        )
        val items = InvoiceGrid.read(rows, recognizeQuantity = { if (it.top == 111) "5" else "8" }) { "Тестовая деталь" }
        assertEquals(listOf(InvoiceItem("Тестовая деталь", "5"), InvoiceItem("Тестовая деталь", "8")), items)
    }

    @Test fun readsHeaderCellWhenPageOcrLosesOrMergesTheQuantityHeading() {
        val mergedHeader = InvoiceWord("Кол-во|Ед", 165, 94, 50, 12)
        for (heading in listOf(null, mergedHeader)) {
            val rows = grid(300, heading) {
                if (it.left == 171) "Кoл – вo" else "Цена"
            }
            val items = InvoiceGrid.read(rows, recognizeQuantity = { "|5|" }) { "Тестовая деталь" }
            assertEquals(listOf("5", "5"), items.map { it.quantity })
        }
    }

    @Test fun neverReadsUnitOrPriceAsQuantityWhenHeadingIsUnclear() {
        val rows = grid(300, quantityHeader = null) { "Цена" }
        assertEquals(listOf(null, null), rows.map { it.quantity })
        assertEquals("", InvoiceGrid.quantityOrEmpty("5|шт"))
    }

    @Test fun croppedTableCanReturnBothNamesWithoutGuessingQuantities() {
        val rows = grid(150)
        assertEquals(
            listOf(
                InvoiceGrid.Row(InvoiceGrid.Cell(41, 111, 150, 130), null),
                InvoiceGrid.Row(InvoiceGrid.Cell(41, 131, 150, 150), null),
            ),
            rows,
        )
        val items = InvoiceGrid.read(rows, recognizeQuantity = { error("Column outside the screenshot") }) { if (it.top == 111) "Первая деталь A5" else "Вторая деталь V4" }
        assertEquals(listOf(InvoiceItem("Первая деталь A5", ""), InvoiceItem("Вторая деталь V4", "")), items)
    }
}
