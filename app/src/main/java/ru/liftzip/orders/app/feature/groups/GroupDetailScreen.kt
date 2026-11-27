package ru.liftzip.orders.app.feature.groups

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.GroupDetailViewModel
import ru.liftzip.orders.app.ui.GroupStageHighlight
import ru.liftzip.orders.app.ui.OrderFulfillmentActions
import ru.liftzip.orders.app.ui.GroupStatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.formatAmount
import ru.liftzip.orders.app.ui.formatDate
import ru.liftzip.orders.app.ui.formatDecimal
import ru.liftzip.orders.app.ui.groupStageHighlight
import ru.liftzip.orders.app.ui.russian
import ru.liftzip.orders.domain.model.CostEntry
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.GroupDetails
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    repository: OrdersRepository,
    groupId: UUID,
    onBack: () -> Unit,
    onOpenOrder: (UUID) -> Unit,
) {
    val model = viewModel<GroupDetailViewModel>(
        key = "group-$groupId",
        factory = viewModelFactory { initializer { GroupDetailViewModel(repository, groupId) } },
    )
    val details by model.details.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    var confirmFinish by remember { mutableStateOf(false) }

    // Read the snapshot during composition, rather than only inside the lazy item builder.
    val current = details
    Scaffold(
        containerColor = GroupBackground,
        topBar = {
            TopAppBar(
                title = { Text("Группа №${current?.summary?.group?.number ?: "…"}", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GroupBackground),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current == null) {
                item { Text("Загрузка…") }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        GroupStatusBadge(current.summary.group.state)
                        Text("${current.orders.count { it.order.fulfillment.isComplete }} / ${current.summary.orderCount} выполнено", style = MaterialTheme.typography.bodySmall)
                    }
                    GroupTotals(current)
                    GroupOrdersTable(current, busy, onOpenOrder, model::setAction)
                    if (!current.summary.isComplete) {
                        Text("Расчёт неполный; неизвестные затраты не заменены нулём", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (current.summary.group.state == GroupState.FORMING) {
                    item {
                        Button(
                            onClick = { confirmFinish = true },
                            enabled = current.summary.orderCount > 0 && !busy,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) { Text("Завершить формирование") }
                    }
                }
                if (current.costs.isNotEmpty()) {
                    item(key = "saved-group-costs") {
                        SavedGroupCosts(groupId, current.costs)
                    }
                }
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Завершить формирование?") },
            text = { Text("Состав группы и выручка будут заблокированы. Сразу создаётся следующая формируемая группа. Это не финансовое закрытие.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    model.finish(groupId) { }
                }) { Text("Завершить") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Отмена") } },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = model::clearMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = model::clearMessage) { Text("Понятно") } },
        )
    }
}

@Composable
private fun SavedGroupCosts(groupId: UUID, costs: List<CostEntry>) {
    var expanded by rememberSaveable(groupId) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp), color = Color.White,
        border = BorderStroke(1.dp, GroupOutline),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ReceiptLong, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text("Ранее внесённые расходы", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold)
                Text(costs.size.toString(), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Свернуть расходы" else "Развернуть расходы")
            }
            if (expanded) {
                costs.forEach { cost ->
                    HorizontalDivider(color = GroupOutline)
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val suffix = if (cost.currency == Currency.RUB) "₽" else "¥"
                        Text("${cost.kind.russian()} · ${cost.amount.formatDecimal()} $suffix",
                            style = MaterialTheme.typography.titleSmall)
                        val status = if (cost.isConfirmed) "Подтверждён" else "Не подтверждён"
                        Text("${cost.date.formatDate()} · $status", style = MaterialTheme.typography.bodySmall)
                        if (cost.currency == Currency.CNY) {
                            Text("Курс: ${cost.rate.formatDecimal()} ₽/¥", style = MaterialTheme.typography.bodySmall)
                        }
                        if (cost.comment.isNotBlank()) Text(cost.comment, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val GroupBackground = Color(0xFFF8FAFC)
private val GroupOutline = Color(0xFFD8E1E5)
private val TableHeading = Color(0xFFEDF3F1)
private val AlternateOrderRow = Color(0xFFF5F8FA)
private val TotalBackground = Color(0xFFEAF4EF)
private val Positive = Color(0xFF17784B)

@Composable
private fun GroupTotals(details: GroupDetails) {
    val summary = details.summary
    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
        color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, GroupOutline),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 10.dp)) {
            listOf(
                Triple("Д", summary.income?.format() ?: "—", Positive),
                Triple("К", summary.purchaseCost?.format() ?: "—", MaterialTheme.colorScheme.error),
                Triple("∆", summary.profit?.format() ?: "—",
                    if ((summary.profit ?: Money.ZERO) < Money.ZERO) MaterialTheme.colorScheme.error else Positive),
            ).forEachIndexed { index, (label, value, color) ->
                if (index > 0) VerticalDivider(color = GroupOutline)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color,
                        textAlign = TextAlign.Center, modifier = Modifier.testTag("group-total-$label"))
                }
            }
        }
    }
}

@Composable
private fun GroupOrdersTable(
    details: GroupDetails,
    busy: Boolean,
    onOpenOrder: (UUID) -> Unit,
    onAction: (UUID, OrderAction, Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Five columns at every phone width. Fit the text to its measured cell;
        // never move actions outside the order's row.
        val amountWidth = (maxWidth - 4.dp) * 0.13f - 2.dp
        val actionWidth = ((maxWidth - 4.dp) * 0.28f - 1.dp) / 2f
        val groupFormed = details.summary.group.state != GroupState.FORMING
        val orders = remember(details.orders) { details.orders.sortedBy { it.order.number } }
        val totals = remember(details.summary.income, details.summary.purchaseCost, details.summary.profit) {
            listOf(details.summary.income?.formatAmount() ?: "—", details.summary.purchaseCost?.formatAmount() ?: "—",
                details.summary.profit?.formatAmount() ?: "—")
        }
        val values = remember(orders) {
            orders.map { summary ->
                listOf(summary.calculation.income?.formatAmount() ?: "—",
                    summary.calculation.purchaseCost?.formatAmount() ?: "—",
                    summary.calculation.profit?.formatAmount() ?: "—")
            }
        }
        val columnValues = remember(values, totals) { totals.indices.map { column -> values.map { it[column] } + totals[column] } }
        val amountStyles = (0..2).map { index ->
            fittedTableStyle(columnValues[index], amountWidth,
                MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, letterSpacing = 0.sp,
                    fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
        }
        val actionStyle = fittedTableStyle(listOf("Снял", "Передал", "Оплата", "Получено"), actionWidth,
            MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal))
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, GroupOutline)) {
            Column {
                FinancialRow("Заказ", "Д", "К", "∆", amountStyles, heading = true)
                orders.forEachIndexed { index, summary ->
                    val highlight = if (groupFormed) summary.order.fulfillment.stage.groupStageHighlight() else null
                    HorizontalDivider(color = GroupOutline, thickness = 1.dp)
                    FinancialRow(
                        name = summary.order.counterparty.ifBlank { "Заказ №${summary.order.number}" },
                        income = values[index][0], costs = values[index][1], profit = values[index][2],
                        amountStyles = amountStyles,
                        profitColor = if ((summary.calculation.profit ?: Money.ZERO) < Money.ZERO) MaterialTheme.colorScheme.error else Positive,
                        rowColor = if (index % 2 == 0) Color.White else AlternateOrderRow,
                        stageStripe = highlight?.stripe ?: Color.Transparent,
                        onOpen = { onOpenOrder(summary.order.id) },
                        actions = { OrderActions(summary, groupFormed && !busy, actionStyle, highlight, onAction) },
                    )
                }
                if (orders.isEmpty()) Text("В группе пока нет заказов", Modifier.padding(12.dp))
                HorizontalDivider(color = GroupOutline, thickness = 1.dp)
                FinancialRow("Итого", totals[0], totals[1], totals[2], amountStyles,
                    total = true, profitColor = if ((details.summary.profit ?: Money.ZERO) < Money.ZERO) MaterialTheme.colorScheme.error else Positive)
            }
        }
    }
}

/** Use one measured font size per column, including its bold total. */
@Composable
private fun fittedTableStyle(values: List<String>, width: Dp, style: TextStyle): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(values, width, style, measurer, density) {
        val available = with(density) { width.toPx() } - 2f
        val measured = values.distinct().maxOf { measurer.measure(AnnotatedString(it), style, softWrap = false).size.width }.toFloat()
        val scale = minOf(1f, available.coerceAtLeast(1f) / measured.coerceAtLeast(1f))
        style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
    }
}

@Composable
private fun FinancialRow(
    name: String, income: String, costs: String, profit: String, amountStyles: List<TextStyle>,
    heading: Boolean = false, total: Boolean = false, profitColor: Color = Positive,
    rowColor: Color = Color.White, stageStripe: Color = Color.Transparent,
    onOpen: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .background(if (heading) TableHeading else if (total) TotalBackground else rowColor),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(33f).fillMaxHeight()
            .drawBehind { drawRect(stageStripe, size = Size(4.dp.toPx(), size.height)) }
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = if (onOpen != null) 8.dp else 6.dp, end = 4.dp)
            .padding(vertical = if (heading || total) 12.dp else 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold)
        }
        listOf("Доход" to income, "Расход" to costs, "Прибыль" to profit).forEachIndexed { index, (description, value) ->
            VerticalDivider(color = if (heading || total) Color.Transparent else GroupOutline, thickness = 1.dp)
            Box(Modifier.weight(13f).padding(horizontal = 1.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(value, style = if (heading) MaterialTheme.typography.labelSmall else amountStyles[index],
                    fontWeight = if (heading || total || index == 2) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == 2 && !heading) profitColor else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$description: $value" })
            }
        }
        VerticalDivider(color = if (heading || total) Color.Transparent else GroupOutline, thickness = 1.dp)
        Box(Modifier.weight(28f).padding(vertical = if (heading || total) 4.dp else 0.dp), contentAlignment = Alignment.Center) {
            if (heading) Text("Действия", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            else if (actions != null) actions()
        }
    }
}

@Composable
private fun OrderActions(
    summary: OrderSummary,
    enabled: Boolean,
    captionStyle: TextStyle,
    highlight: GroupStageHighlight?,
    onAction: (UUID, OrderAction, Boolean) -> Unit,
) {
    OrderFulfillmentActions(
        summary.order.number, summary.order.fulfillment, enabled = enabled, twoRows = true,
        captionStyle = captionStyle, groupHighlight = highlight,
    ) { action, checked ->
        onAction(summary.order.id, action, checked)
    }
}
