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
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}
private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

fun Money.format(): String = "${moneyFormat.format(toBigDecimal())} ₽"
fun BigDecimal.formatDecimal(): String = stripTrailingZeros().toPlainString()
fun LocalDate.formatDate(): String = format(dateFormatter)
fun Instant.formatDateTime(): String = atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
fun String.parseDecimal(): BigDecimal? = trim().replace(',', '.').toBigDecimalOrNull()
