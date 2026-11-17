package ru.liftzip.orders.app.feature.orders

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceTextParserTest {
    @Test fun firstScannedInvoiceRetainsNameWhenQuantityMissing() {
        val ocr = """
            № Товары (работы, услуги) Кол-во | Ед. Цена Сумма
            1 [Трансформатор BKC-630VA шт 29 000,00 29 000,00
            Итого: 29 000,00
        """.trimIndent()
        assertEquals(listOf(InvoiceItem("Трансформатор BKC-630VA", "")), InvoiceTextParser.parse(ocr))
    }

    @Test fun secondScannedInvoiceJoinsLinesAndExtractsQuantity() {
        val ocr = """
            № Наименование товара Цена Кол-во | Ед. изм. Сумма
            1 Датчик индуктивный ND8-S3-M12S в
            сборе с кабелем коннектором V1-W-5M-
            РУС PEPPERL+FUCHS PNP. 4900,00 4 шт 19600,00
            ю
            Датчик наличия ступени эскалатора
            X0508 PNP NO M30 15 mm ND15-S3-
            M30S 3200,00 4 шт 12800,00
            Итого: 32400,00
        """.trimIndent()
        assertEquals(
            listOf(
                InvoiceItem("Датчик индуктивный ND8-S3-M12S в сборе с кабелем коннектором V1-W-5M- РУС PEPPERL+FUCHS PNP.", "4"),
                InvoiceItem("Датчик наличия ступени эскалатора X0508 PNP NO M30 15 mm ND15-S3- M30S", "4")
            ),
            InvoiceTextParser.parse(ocr)
        )
    }


    @Test fun damagedHeaderAndMissingQuantityStillYieldTwoEditableRows() {
        val ocr = """
            Счет № 123
            Покупатель Тест
            В ее
            1 Датчик индуктивный X1 в
            сборе с кабелем K2-
            ‚ _ |PNP 4900,00 шт! 19600,00
            2 | Датчик ступени эскалатора
            X0508 PNP M30
            M30S 3200,00 шт 12800,00
            Итого: 32400,00
        """.trimIndent()
        assertEquals(listOf(
            InvoiceItem("Датчик индуктивный X1 в сборе с кабелем K2- PNP", ""),
            InvoiceItem("Датчик ступени эскалатора X0508 PNP M30 M30S", "")
        ), InvoiceTextParser.parse(ocr))
    }

    @Test fun doesNotReadBankingInformationAsProducts() {
        assertEquals(emptyList<InvoiceItem>(), InvoiceTextParser.parse("Счёт № 123\nБанк 123456\nИтого: 1"))
    }
}
