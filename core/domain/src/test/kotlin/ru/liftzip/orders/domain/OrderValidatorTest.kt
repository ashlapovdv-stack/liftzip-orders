package ru.liftzip.orders.domain

import org.junit.Assert.assertThrows
import org.junit.Test
import ru.liftzip.orders.domain.calculation.OrderValidator
import ru.liftzip.orders.domain.model.DomainValidationException
import ru.liftzip.orders.domain.model.OrderLineDraft
import ru.liftzip.orders.domain.model.SaveOrderCommand
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class OrderValidatorTest {
    @Test
    fun `partial payment is allowed and stays independent of sale`() {
        val command = command(payment = "99.99", sale = "100")
        OrderValidator.validate(command)
    }

    @Test
    fun `zero sale requires conscious confirmation`() {
        val zero = command(payment = "0", sale = "0").copy(
            lines = listOf(line("0").copy(zeroSalePriceConfirmed = false)),
        )
        assertThrows(DomainValidationException::class.java) { OrderValidator.validate(zero) }
        OrderValidator.validate(zero.copy(lines = listOf(line("0").copy(zeroSalePriceConfirmed = true))))
    }

    @Test
    fun `empty purchase is allowed and remains unknown`() {
        val command = command("100", "100")
        OrderValidator.validate(command)
        org.junit.Assert.assertNull(command.lines.single().purchaseUnitPrice)
    }

    @Test
    fun `subkopeck sale price cannot change after storage rounding`() {
        val command = command("1.01", "0.335").copy(lines = listOf(line("0.335").copy(quantity = BigDecimal("3"))))
        assertThrows(DomainValidationException::class.java) { OrderValidator.validate(command) }
    }

    @Test
    fun `subkopeck payment is not silently rounded`() {
        assertThrows(DomainValidationException::class.java) { OrderValidator.validate(command("100.001", "100")) }
    }

    private fun command(payment: String, sale: String) = SaveOrderCommand(
        commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = "Тест",
        receivedPayment = BigDecimal(payment), costsConfirmed = false, lines = listOf(line(sale)), costs = emptyList(),
    )

    private fun line(sale: String) = OrderLineDraft(
        productName = "Товар", quantity = BigDecimal.ONE, saleUnitPrice = BigDecimal(sale),
        zeroSalePriceConfirmed = true,
    )
}
