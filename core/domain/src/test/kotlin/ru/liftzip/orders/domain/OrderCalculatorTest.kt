package ru.liftzip.orders.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.liftzip.orders.domain.calculation.OrderCalculator
import ru.liftzip.orders.domain.model.CalculationState
import ru.liftzip.orders.domain.model.CostEntry
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.CostOwnerType
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.Order
import ru.liftzip.orders.domain.model.OrderLine
import ru.liftzip.orders.domain.model.SettlementGroup
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OrderCalculatorTest {
    private val orderId = UUID.randomUUID()
    private val lineId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()

    @Test
    fun `acceptance example with CNY and delivery`() {
        val line = line(
            quantity = "2", saleUnitPrice = "2500", purchasePrice = "100",
            currency = Currency.CNY, rate = "13.25",
        )
        val delivery = cost(CostOwnerType.ORDER, orderId, "3000", CostKind.PURCHASE)
        val result = OrderCalculator.calculate(order("5000", true), listOf(line), listOf(delivery))
        assertEquals("3000.00", result.knownCost.toBigDecimal().toPlainString())
        assertEquals("2000.00", result.preliminaryProfitWithoutGroupCosts.toBigDecimal().toPlainString())
        assertTrue(result.isComplete)
    }

    @Test
    fun `rounds each line half up without premature rounding`() {
        val result = OrderCalculator.linePurchase(
            line(quantity = "3", saleUnitPrice = "1", purchasePrice = "0.335", currency = Currency.CNY, rate = "10"),
        )
        assertEquals(Money.fromRubles(BigDecimal("10.05")), result)
    }

    @Test
    fun `unknown purchase is not known zero and calculation is incomplete`() {
        val result = OrderCalculator.calculate(
            order("100", true),
            listOf(line("1", "100", null, null, null)),
            emptyList(),
        )
        assertFalse(result.isComplete)
        assertEquals(Money.ZERO, result.knownCost)
        assertEquals(Money.ZERO, result.preliminaryProfitWithoutGroupCosts)
    }

    @Test
    fun `group expense is counted once and not allocated to order`() {
        val order = order("1000", true)
        val line = line("1", "1000", "400", Currency.RUB, "1")
        val groupExpense = cost(CostOwnerType.GROUP, groupId, "100")
        val purchase = cost(CostOwnerType.ORDER, orderId, "400", CostKind.PURCHASE)
        val orderCalculation = OrderCalculator.calculate(order, listOf(line), listOf(purchase))
        assertEquals(Money.fromRubles(BigDecimal("600.00")), orderCalculation.preliminaryProfitWithoutGroupCosts)
        val group = SettlementGroup(
            groupId, 1, GroupState.CALCULATING, true, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 1,
        )
        val summary = OrderCalculator.summarizeGroup(group, listOf(Triple(order, listOf(line), listOf(purchase))), listOf(groupExpense))
        assertEquals(Money.fromRubles(BigDecimal("600.00")), summary.preliminaryProfit)
        assertEquals(Money.fromRubles(BigDecimal("400.00")), summary.knownCosts)
    }

    @Test
    fun `negative preliminary profit remains visible`() {
        val result = OrderCalculator.calculate(
            order("100", true), listOf(line("1", "100", "150", Currency.RUB, "1")), listOf(cost(CostOwnerType.ORDER, orderId, "150", CostKind.PURCHASE)),
        )
        assertEquals(Money.fromRubles(BigDecimal("-50.00")), result.preliminaryProfitWithoutGroupCosts)
    }

    private fun order(payment: String, costsConfirmed: Boolean) = Order(
        orderId, 1, LocalDate.parse("2026-11-11"), "Тест", groupId,
        Money.fromRubles(BigDecimal(payment)), "", costsConfirmed, CalculationState.INCOMPLETE,
        Instant.EPOCH, Instant.EPOCH, 1,
    )

    private fun line(
        quantity: String,
        saleUnitPrice: String,
        purchasePrice: String?,
        currency: Currency?,
        rate: String?,
    ) = OrderLine(
        lineId, orderId, UUID.randomUUID(), "Товар", "A-1", BigDecimal(quantity),
        Money.fromRubles(BigDecimal(saleUnitPrice)), purchasePrice?.let(::BigDecimal), currency,
        rate?.let(::BigDecimal), Instant.EPOCH, Instant.EPOCH, 1,
    )

    private fun cost(ownerType: CostOwnerType, ownerId: UUID, amount: String, kind: CostKind = CostKind.DELIVERY) = CostEntry(
        UUID.randomUUID(), ownerType, ownerId, kind, BigDecimal(amount), Currency.RUB,
        BigDecimal.ONE, true, LocalDate.parse("2026-11-11"), "", Instant.EPOCH, Instant.EPOCH, 1,
    )
}
