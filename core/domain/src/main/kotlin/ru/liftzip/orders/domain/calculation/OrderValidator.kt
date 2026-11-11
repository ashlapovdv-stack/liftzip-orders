package ru.liftzip.orders.domain.calculation

import ru.liftzip.orders.domain.model.CostDraft
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.DomainValidationException
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.SaveOrderCommand
import java.math.BigDecimal
import java.text.Normalizer

object OrderValidator {
    fun validate(command: SaveOrderCommand) {
        if (command.counterparty.isBlank()) fail("Укажите клиента или менеджера")
        if (command.lines.isEmpty()) fail("Добавьте хотя бы одну позицию")
        if (command.receivedPayment < BigDecimal.ZERO) fail("Оплата не может быть отрицательной")
        if (command.receivedPayment.stripTrailingZeros().scale() > 2) fail("Оплата задаётся с точностью до копеек")
        Money.fromRubles(command.receivedPayment)
        command.lines.forEachIndexed { index, line ->
            if (line.productName.isBlank()) fail("Позиция ${index + 1}: укажите название товара")
            if (line.quantity <= BigDecimal.ZERO) fail("Позиция ${index + 1}: количество должно быть больше нуля")
            if (line.saleUnitPrice < BigDecimal.ZERO) fail("Позиция ${index + 1}: цена не может быть отрицательной")
            if (line.saleUnitPrice.compareTo(BigDecimal.ZERO) == 0 && !line.zeroSalePriceConfirmed) {
                fail("Позиция ${index + 1}: подтвердите нулевую цену продажи")
            }
            if (line.saleUnitPrice.stripTrailingZeros().scale() > 2) fail("Позиция ${index + 1}: цена продажи задаётся с точностью до копеек")
            Money.fromRubles(line.saleUnitPrice)
            ru.liftzip.orders.domain.delivery.DeliveryCalculator.validatePlan(line.deliveryWeight, line.deliveryTariff, line.deliveryRate, line.deliveryPlanDate)
            validatePurchase(
                index = index,
                price = line.purchaseUnitPrice,
                currency = line.purchaseCurrency,
                rate = line.purchaseRate,
            )
            line.costs.forEach { validateCost(it, "Позиция ${index + 1}") }
        }
        command.costs.forEach { validateCost(it, "Заказ") }
        val sales = command.lines.fold(Money.ZERO) { sum, line ->
            sum + Money.fromRubles(line.quantity.multiply(line.saleUnitPrice))
        }
        val payment = Money.fromRubles(command.receivedPayment)
        if (sales != payment) {
            val difference = payment.toBigDecimal().subtract(sales.toBigDecimal()).abs().setScale(2)
            fail("Полученная оплата отличается от суммы продажи на $difference ₽. Исправьте ввод")
        }
    }

    fun validateCost(cost: CostDraft, owner: String) {
        if (cost.amount < BigDecimal.ZERO) fail("$owner: расход не может быть отрицательным")
        if (cost.rate <= BigDecimal.ZERO) fail("$owner: курс должен быть положительным")
        if (cost.currency == Currency.RUB && cost.rate.compareTo(BigDecimal.ONE) != 0) {
            fail("$owner: для RUB курс равен 1")
        }
        Money.fromRubles(cost.amount.multiply(cost.rate))
    }

    private fun validatePurchase(index: Int, price: BigDecimal?, currency: Currency?, rate: BigDecimal?) {
        val values = listOfNotNull(price, currency, rate)
        if (values.isEmpty()) return
        if (price == null || currency == null || rate == null) {
            fail("Позиция ${index + 1}: заполните закупочную цену, валюту и курс или оставьте все поля пустыми")
        }
        if (price < BigDecimal.ZERO) fail("Позиция ${index + 1}: закупочная цена не может быть отрицательной")
        if (rate <= BigDecimal.ZERO) fail("Позиция ${index + 1}: курс должен быть положительным")
        if (currency == Currency.RUB && rate.compareTo(BigDecimal.ONE) != 0) {
            fail("Позиция ${index + 1}: для RUB курс равен 1")
        }
        Money.fromRubles(price.multiply(rate))
    }

    private fun fail(message: String): Nothing = throw DomainValidationException(message)
}

fun normalizeArticle(article: String?): String? = article
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).uppercase().replace(WHITESPACE, "") }

private val WHITESPACE = Regex("\\s+")
