package ru.liftzip.orders.app.ui

import ru.liftzip.orders.domain.model.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val moneyFormat = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}
private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val monthFormatter = DateTimeFormatter.ofPattern("LLLL", Locale.forLanguageTag("ru-RU"))

fun Money.formatAmount(): String = moneyFormat.format(toBigDecimal())
fun Money.format(): String = "${formatAmount()} ₽"
fun BigDecimal.formatDecimal(): String = stripTrailingZeros().toPlainString()
fun LocalDate.formatDate(): String = format(dateFormatter)
fun Instant.formatDateTime(): String = atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
fun Instant.formatMonth(): String = atZone(ZoneId.systemDefault()).format(monthFormatter)
    .replaceFirstChar { it.titlecase() }
fun String.parseDecimal(): BigDecimal? = trim().replace(',', '.').toBigDecimalOrNull()

fun ru.liftzip.orders.domain.model.GroupState.label(): String = when (this) {
    ru.liftzip.orders.domain.model.GroupState.FORMING -> "Формируется"
    ru.liftzip.orders.domain.model.GroupState.CALCULATING -> "На расчёте"
    ru.liftzip.orders.domain.model.GroupState.COMPLETED -> "Выполнена"
    ru.liftzip.orders.domain.model.GroupState.ACCEPTED -> "Принята"
    ru.liftzip.orders.domain.model.GroupState.AWAITING_PAYMENT -> "На оплате"
    ru.liftzip.orders.domain.model.GroupState.IN_DELIVERY -> "Доставка"
}
