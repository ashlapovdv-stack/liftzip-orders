package ru.liftzip.orders.app.feature.orders

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceGridTest {
    private fun grid(width: Int): List<InvoiceGrid.Row> {
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
            listOf(
                InvoiceWord("Товары", 80, 94, 48, 12),
                InvoiceWord("Кол-во", 174, 94, 32, 12),
            ).filter { it.x < width },
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
        val items = InvoiceGrid.read(rows) { if (it.left == 41) "Тестовая деталь" else if (it.top == 111) "5" else "8" }
        assertEquals(listOf(InvoiceItem("Тестовая деталь", "5"), InvoiceItem("Тестовая деталь", "8")), items)
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
        val items = InvoiceGrid.read(rows) { if (it.top == 111) "Первая деталь A5" else "Вторая деталь V4" }
        assertEquals(listOf(InvoiceItem("Первая деталь A5", ""), InvoiceItem("Вторая деталь V4", "")), items)
    }
}
