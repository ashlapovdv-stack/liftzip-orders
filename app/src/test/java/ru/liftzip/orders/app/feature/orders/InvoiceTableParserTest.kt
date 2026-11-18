package ru.liftzip.orders.app.feature.orders

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceTableParserTest {
    private fun w(text: String, x: Int, y: Int) = InvoiceWord(text, x, y, 28, 14)

    @Test fun quantityAfterName() {
        val words = listOf(
            w("№", 20, 10), w("Товары", 80, 10), w("Кол-во", 400, 10), w("Цена", 470, 10),
            w("1", 20, 40), w("Трансформатор", 80, 40), w("ВКС-630VA", 160, 40),
            w("1", 400, 40), w("29", 470, 40), w("Итого:", 380, 75),
        )
        assertEquals(listOf(InvoiceItem("Трансформатор ВКС-630VA", "1")), InvoiceTableParser.parse(words))
    }

    @Test fun quantityAfterPriceAndMultilineName() {
        val words = listOf(
            w("№", 20, 10), w("Наименование", 80, 10), w("товара", 180, 10),
            w("Цена", 300, 10), w("Кол-во", 400, 10), w("Сумма", 510, 10),
            w("1", 20, 40), w("Датчик", 80, 40), w("индуктивный", 140, 40),
            w("4900", 300, 40), w("4", 400, 40), w("19600", 510, 40),
            w("NDB-S3-M12S", 80, 60), w("PNP", 180, 60),
            w("2", 20, 90), w("Датчик", 80, 90), w("эскалатора", 140, 90),
            w("3200", 300, 90), w("4", 400, 90), w("12800", 510, 90),
            w("Итого:", 400, 130),
        )
        assertEquals(listOf(InvoiceItem("Датчик индуктивный NDB-S3-M12S PNP", "4"),
            InvoiceItem("Датчик эскалатора", "4")), InvoiceTableParser.parse(words))
    }

    @Test fun numericPartOfMultilineDescriptionIsNotAnotherItem() {
        val words = listOf(
            w("№", 20, 10), w("Наименование", 80, 10),
            w("Цена", 300, 10), w("Кол-во", 400, 10), w("Сумма", 510, 10),
            w("1", 20, 40), w("Канат", 80, 40), w("8x19S-FC", 145, 40),
            w("1570/1770", 220, 40), w("163,00", 300, 40),
            w("SAFETY", 80, 60), w("(по", 145, 60),
            w("169", 180, 60), w("м.)", 215, 60), w("1014", 400, 60),
            w("165282,00", 510, 60), w("Итого:", 400, 100)
        )
        assertEquals(
            listOf(InvoiceItem("Канат 8x19S-FC 1570/1770 SAFETY (по 169 м.)", "1014")),
            InvoiceTableParser.parse(words)
        )
    }

    @Test fun missingHeaderDoesNotInventProducts() {
        assertEquals(emptyList<InvoiceItem>(), InvoiceTableParser.parse(listOf(w("Счет", 20, 10), w("4", 400, 40))))
    }
}
