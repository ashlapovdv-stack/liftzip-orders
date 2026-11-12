package ru.liftzip.orders.app.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.feature.orders.EditorCostDraft
import ru.liftzip.orders.app.ui.CostsEditor
import ru.liftzip.orders.app.ui.GroupDetailViewModel
import ru.liftzip.orders.app.ui.GroupStatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.label
import ru.liftzip.orders.app.ui.formatAmount
import ru.liftzip.orders.app.ui.parseDecimal
import ru.liftzip.orders.domain.model.CostDraft
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.GroupDetails
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.SaveGroupCostsCommand
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.math.BigDecimal
import java.util.UUID

private val costDraftsSaver = listSaver<List<EditorCostDraft>, String>(
    save = { costs -> costs.flatMap { listOf(it.id?.toString().orEmpty(), it.kind.name, it.amount,
        it.currency.name, it.rate, it.isConfirmed.toString(), it.date.toString(), it.comment) } },
    restore = { values -> values.chunked(8).map { EditorCostDraft(
        id = it[0].takeIf(String::isNotEmpty)?.let(UUID::fromString), kind = CostKind.valueOf(it[1]),
        amount = it[2], currency = Currency.valueOf(it[3]), rate = it[4], isConfirmed = it[5].toBoolean(),
        date = java.time.LocalDate.parse(it[6]), comment = it[7],
    ) } },
)

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
    var costs by rememberSaveable(stateSaver = costDraftsSaver) { mutableStateOf<List<EditorCostDraft>>(emptyList()) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var costsDirty by rememberSaveable { mutableStateOf(false) }
    var costsExpanded by rememberSaveable(groupId) { mutableStateOf(false) }
    var loadedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(details?.summary?.group?.revision) {
        details?.let { current ->
            if (loadedRevision == current.summary.group.revision || costsDirty) return@let
            loadedRevision = current.summary.group.revision
            costs = current.costs.map {
                EditorCostDraft(
                    id = it.id, kind = it.kind, amount = it.amount.toPlainString(), currency = it.currency,
                    rate = it.rate.toPlainString(), isConfirmed = it.isConfirmed, date = it.date, comment = it.comment,
                )
            }
            confirmed = current.summary.group.costsConfirmed
        }
    }

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
            Modifier.fillMaxSize().padding(padding),
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
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = Color.White,
                        border = BorderStroke(1.dp, GroupOutline),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clickable { costsExpanded = !costsExpanded }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text("Расходы группы", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold)
                                if (costs.isNotEmpty()) Text(costs.size.toString(), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium)
                                Icon(if (costsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    if (costsExpanded) "Свернуть расходы" else "Развернуть расходы")
                            }
                            if (costsExpanded) {
                                HorizontalDivider(color = GroupOutline)
                                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CostsEditor(
                                        title = "Расходы группы", costs = costs, errors = emptyMap(), errorPrefix = "group_cost",
                                        onAdd = { costsDirty = true; costs = costs + EditorCostDraft() },
                                        onChange = { index, transform -> costsDirty = true; costs = costs.mapIndexed { i, cost -> if (i == index) transform(cost) else cost } },
                                        onRemove = { index -> costsDirty = true; costs = costs.filterIndexed { i, _ -> i != index } },
                                        showHeadingAndAdd = false,
                                    )
                                    Row(Modifier.fillMaxWidth().clickable { costsDirty = true; confirmed = !confirmed }, verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = confirmed, onCheckedChange = { costsDirty = true; confirmed = it })
                                        Text("Все групповые затраты указаны, включая нулевые", style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                            val parsed = costs.mapIndexedNotNull { index, cost ->
                                val amount = cost.amount.parseDecimal()
                                val rate = cost.rate.parseDecimal()
                                if (amount == null || amount < BigDecimal.ZERO || rate == null || rate <= BigDecimal.ZERO ||
                                    (cost.currency == Currency.RUB && rate.compareTo(BigDecimal.ONE) != 0)
                                ) {
                                    localMessage = "Проверьте сумму и курс расхода №${index + 1}"; null
                                } else CostDraft(
                                    id = cost.id, kind = cost.kind, amount = amount, currency = cost.currency,
                                    rate = rate, isConfirmed = cost.isConfirmed, date = cost.date, comment = cost.comment,
                                )
                            }
                            if (parsed.size != costs.size) return@TextButton
                            scope.launch {
                                try {
                                    repository.saveGroupCosts(SaveGroupCostsCommand(groupId, confirmed, parsed))
                                    costsDirty = false
                                    localMessage = "Расходы группы сохранены"
                                } catch (error: Exception) { localMessage = error.message }
                            }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить расходы группы") }
                                }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = GroupOutline)
                            TextButton(
                                onClick = { costsDirty = true; costs = costs + EditorCostDraft(); costsExpanded = true },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                Text("Добавить расход", Modifier.padding(start = 6.dp))
                            }
                        }
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
    (message ?: localMessage)?.let { text ->
        AlertDialog(
            onDismissRequest = { model.clearMessage(); localMessage = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { model.clearMessage(); localMessage = null }) { Text("Понятно") } },
        )
    }
}

private val GroupBackground = Color(0xFFF8FAFC)
private val GroupOutline = Color(0xFFD8E1E5)
private val TableHeading = Color(0xFFEDF3F1)
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
                Triple("Д", summary.sales.format(), Positive),
                Triple("К", summary.knownCosts.format(), MaterialTheme.colorScheme.error),
                Triple("∆", if (summary.isComplete) summary.preliminaryProfit.format() else "—",
                    if (summary.preliminaryProfit < Money.ZERO) MaterialTheme.colorScheme.error else Positive),
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
        val actionWidth = (maxWidth - 4.dp) * 0.28f / 3f
        val orders = details.orders.sortedBy { it.order.number }
        val totals = listOf(details.summary.sales.formatAmount(), details.summary.knownCosts.formatAmount(),
            if (details.summary.isComplete) details.summary.preliminaryProfit.formatAmount() else "—")
        val values = orders.map { summary -> listOf(summary.sales.formatAmount(), summary.calculation.knownCost.formatAmount(),
            if (summary.calculation.isComplete) summary.calculation.preliminaryProfitWithoutGroupCosts.formatAmount() else "—") }
        val amountStyles = (0..2).map { index ->
            fittedTableStyle(values.map { it[index] } + totals[index], amountWidth,
                MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, letterSpacing = 0.sp,
                    fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
        }
        val actionStyle = fittedTableStyle(listOf("Снял", "Передал", "Получено"), actionWidth,
            MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal))
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, GroupOutline)) {
            Column {
                FinancialRow("Заказ", "Д", "К", "∆", amountStyles, heading = true)
                orders.forEachIndexed { index, summary ->
                    HorizontalDivider(color = GroupOutline, thickness = 1.dp)
                    FinancialRow(
                        name = summary.order.counterparty.ifBlank { "Заказ №${summary.order.number}" },
                        income = values[index][0], costs = values[index][1], profit = values[index][2],
                        amountStyles = amountStyles,
                        profitColor = if (summary.calculation.preliminaryProfitWithoutGroupCosts < Money.ZERO) MaterialTheme.colorScheme.error else Positive,
                        onOpen = { onOpenOrder(summary.order.id) },
                        actions = { OrderActions(summary, !busy && details.summary.group.state != GroupState.FORMING, actionStyle, onAction) },
                    )
                }
                if (orders.isEmpty()) Text("В группе пока нет заказов", Modifier.padding(12.dp))
                HorizontalDivider(color = GroupOutline, thickness = 1.dp)
                FinancialRow("Итого", totals[0], totals[1], totals[2], amountStyles,
                    total = true, profitColor = if (details.summary.preliminaryProfit < Money.ZERO) MaterialTheme.colorScheme.error else Positive)
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
        val measured = values.maxOf { measurer.measure(AnnotatedString(it), style, softWrap = false).size.width }.toFloat()
        val scale = minOf(1f, available.coerceAtLeast(1f) / measured.coerceAtLeast(1f))
        style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
    }
}

@Composable
private fun FinancialRow(
    name: String, income: String, costs: String, profit: String, amountStyles: List<TextStyle>,
    heading: Boolean = false, total: Boolean = false, profitColor: Color = Positive,
    onOpen: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .background(if (heading) TableHeading else if (total) TotalBackground else Color.White),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(33f).fillMaxHeight()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 6.dp, vertical = if (heading || total) 12.dp else 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
                fontWeight = if (heading || total) FontWeight.SemiBold else FontWeight.Medium)
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
        Box(Modifier.weight(28f).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            if (heading) Text("Действия", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            else if (actions != null) actions()
        }
    }
}

@Composable
private fun OrderActions(summary: OrderSummary, enabled: Boolean, captionStyle: TextStyle, onAction: (UUID, OrderAction, Boolean) -> Unit) {
    val progress = summary.order.fulfillment
    val entries = listOf(
        Triple(OrderAction.WITHDRAWN, "Снял", progress.withdrawn),
        Triple(OrderAction.TRANSFERRED, "Передал", progress.transferred),
        Triple(OrderAction.RECEIVED, "Получено", progress.received),
    )
    Row(Modifier.fillMaxWidth()) {
        entries.forEach { (step, label, checked) ->
            val description = when (step) {
                OrderAction.WITHDRAWN -> "Получены деньги от Дениса"
                OrderAction.TRANSFERRED -> "Деньги за товар переданы посреднику"
                OrderAction.RECEIVED -> "Товар получен"
            }
            val tag = "order-${summary.order.number}-$step"
            Column(Modifier.weight(1f).heightIn(min = 44.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onAction(summary.order.id, step, it) })
                .semantics { contentDescription = "Заказ №${summary.order.number}: $description" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(16.dp).testTag("$tag-square")
                    .background(if (checked) Positive else Color.Transparent, RoundedCornerShape(3.dp))
                    .border(1.dp, if (checked) Positive else Color(0xFF9AA3AD), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Text(label, Modifier.fillMaxWidth().padding(top = 3.dp).testTag("$tag-caption"), style = captionStyle, textAlign = TextAlign.Center)
            }
        }
    }
}
