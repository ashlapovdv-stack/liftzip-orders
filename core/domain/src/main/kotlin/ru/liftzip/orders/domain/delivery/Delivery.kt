package ru.liftzip.orders.domain.delivery

import ru.liftzip.orders.domain.model.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Logistics is a pass-through settlement, never a goods cost or a mediator transfer. */
enum class DeliveryParty { CLIENT, LOGISTICS }
data class DeliveryInvoice(
    val orderId: UUID,
    val amount: Money? = null,
    val date: LocalDate? = null,
    val actualWeight: BigDecimal? = null,
    val actualTariff: BigDecimal? = null,
    val actualRate: BigDecimal? = null,
    val note: String = "",
    val revision: Long = 0,
)
data class DeliveryPayment(
    val id: UUID, val orderId: UUID, val party: DeliveryParty,
    val amount: Money, val date: LocalDate, val note: String,
    val reversalOf: UUID? = null,
)
data class DeliveryDetails(val invoice: DeliveryInvoice, val payments: List<DeliveryPayment>, val lines: List<OrderLine>) {
    val planned: Money? get() = DeliveryCalculator.totalPlan(lines)
    val clientPaid: Money get() = DeliveryCalculator.sumPayments(payments.filter { it.party == DeliveryParty.CLIENT }.map { it.amount })
    val logisticsPaid: Money get() = DeliveryCalculator.sumPayments(payments.filter { it.party == DeliveryParty.LOGISTICS }.map { it.amount })
    val clientBalance: Money? get() = invoice.amount?.minus(clientPaid)
    val logisticsBalance: Money? get() = invoice.amount?.minus(logisticsPaid)
    val difference: Money? get() = invoice.amount?.let { actual -> planned?.let { actual-it } }
}
object DeliveryCalculator {
    fun sumPayments(values:List<Money>):Money = Money(values.fold(java.math.BigInteger.ZERO) { sum,p -> sum+java.math.BigInteger.valueOf(p.minorUnits) }.longValueExact())
    fun validatePlan(weight: BigDecimal?, tariff: BigDecimal?, rate: BigDecimal?, date: LocalDate?) {
        val values = listOfNotNull(weight,tariff,rate)
        if(values.isEmpty() && date == null) return
        require(values.size == 3 && date != null) { "Для прогноза доставки нужны вес всей позиции, тариф, курс и дата" }
        require(weight!! >= BigDecimal.ZERO && tariff!! >= BigDecimal.ZERO && rate!! > BigDecimal.ZERO) { "Вес и тариф неотрицательные, курс больше нуля" }
        amount(weight,tariff,rate)
    }
    fun amount(weight: BigDecimal, tariff: BigDecimal, rate: BigDecimal): Money {
        require(weight >= BigDecimal.ZERO && tariff >= BigDecimal.ZERO && rate > BigDecimal.ZERO)
        return Money.fromRubles(weight.multiply(tariff).multiply(rate))
    }
    fun totalPlan(lines: List<OrderLine>): Money? {
        if(lines.isEmpty()) return null
        var total = Money.ZERO
        for(line in lines) {
            val w=line.deliveryWeight ?: return null
            val t=line.deliveryTariff ?: return null
            val r=line.deliveryRate ?: return null
            total += amount(w,t,r)
        }
        return total
    }
    fun validateInvoice(invoice: DeliveryInvoice) {
        require(invoice.amount == null || invoice.amount >= Money.ZERO) { "Начисление не может быть отрицательным" }
        require((invoice.amount == null) == (invoice.date == null)) { "Для начисления укажите сумму и дату счёта" }
        val parts=listOfNotNull(invoice.actualWeight,invoice.actualTariff,invoice.actualRate)
        require(parts.isEmpty() || parts.size==3) { "Фактический вес, тариф и курс заполняются вместе либо остаются пустыми" }
        if(parts.size==3) amount(parts[0],parts[1],parts[2])
    }
}
