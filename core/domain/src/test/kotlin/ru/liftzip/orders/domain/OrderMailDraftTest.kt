package ru.liftzip.orders.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.liftzip.orders.domain.model.CalculationState
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.Order
import ru.liftzip.orders.domain.model.OrderDetails
import ru.liftzip.orders.domain.model.OrderLine
import java.math.BigDecimal
import ru.liftzip.orders.domain.model.OrderMailDraftFactory
import ru.liftzip.orders.domain.model.SettlementGroup
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OrderMailDraftTest {
    @Test fun orderCommentIsIncludedExactlyOnce() {
        val details = details("Согласовать срок\nУточнить упаковку")

        assertEquals("Комментарий к заказу:\nСогласовать срок\nУточнить упаковку", OrderMailDraftFactory.create(details).body)
    }

    @Test fun onlyTheCommentedPositionGetsItsOwnComment() {
        val details = details("Общий текст")
        val line = OrderLine(
            UUID.randomUUID(), details.order.id, UUID.randomUUID(), "Контактор", null,
            BigDecimal.ONE, null, null, null, null, Instant.EPOCH, Instant.EPOCH, 0,
            comment = "Катушка 110 В\nПроверить маркировку",
        )
        val second = line.copy(id = UUID.randomUUID(), productNameSnapshot = "Трос", comment = " ")
        val body = OrderMailDraftFactory.create(details.copy(lines = listOf(line, second))).body

        assertEquals(
            "1.\nВременная ссылка: \nПостоянная ссылка: \nНаименование: Контактор\nКоличество: 1" +
                "\nКомментарий к позиции:\nКатушка 110 В\nПроверить маркировку" +
                "\n\n2.\nВременная ссылка: \nПостоянная ссылка: \nНаименование: Трос\nКоличество: 1" +
                "\n\nКомментарий к заказу:\nОбщий текст",
            body,
        )
    }

    @Test fun repairsLegacyFooterAndKeepsAnyOtherDraftText() {
        val original = "Правка письма\n\nКомментарий к заказу: Проверить\nУпаковку"
        val footer = "\n\nКомментарий к заказу:\n"
        assertEquals(original, OrderMailDraftFactory.repairLegacyBody(original + footer + "Проверить\nУпаковку"))
        val edited = original + footer + "Изменённый текст"
        assertEquals(edited, OrderMailDraftFactory.repairLegacyBody(edited))
        assertEquals(original, OrderMailDraftFactory.repairLegacyBody(original))
    }

    private fun details(comment: String): OrderDetails {
        val now = Instant.EPOCH
        val group = SettlementGroup(UUID.randomUUID(), 1, GroupState.FORMING, false, now, null, now, 0)
        val order = Order(
            UUID.randomUUID(), 1, LocalDate.of(2026, 11, 21), "Заказ", group.id,
            Money.ZERO, comment, false, CalculationState.INCOMPLETE, now, now, 0,
        )
        return OrderDetails(order, group, emptyList(), emptyList(), emptyList())
    }
}
