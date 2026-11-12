package ru.liftzip.orders.app.feature.groups

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.feature.orders.EditorCostDraft
import ru.liftzip.orders.app.ui.CostsEditor
import ru.liftzip.orders.app.ui.GroupDetailViewModel
import ru.liftzip.orders.app.ui.StatusBadge
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

    Scaffold(
        containerColor = GroupBackground,
        topBar = {
            TopAppBar(
                title = { Text("Группа №${details?.summary?.group?.number ?: "…"}", fontWeight = FontWeight.SemiBold) },
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
            val current = details
            if (current == null) {
                item { Text("Загрузка…") }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(current.summary.group.state.label())
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
        // A separate action row on very narrow screens preserves readable money and names.
        val inlineActions = maxWidth >= 350.dp
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, GroupOutline)) {
            Column {
                FinancialRow("Заказ", "Д", "К", "∆", inlineActions, heading = true)
                details.orders.sortedBy { it.order.number }.forEach { summary ->
                    HorizontalDivider(color = GroupOutline, thickness = 1.dp)
                    FinancialRow(
                        name = summary.order.counterparty.ifBlank { "Заказ №${summary.order.number}" },
                        income = summary.sales.formatAmount(), costs = summary.calculation.knownCost.formatAmount(),
                        profit = if (summary.calculation.isComplete) summary.calculation.preliminaryProfitWithoutGroupCosts.formatAmount() else "—",
                        inlineActions = inlineActions,
                        profitColor = if (summary.calculation.preliminaryProfitWithoutGroupCosts < Money.ZERO) MaterialTheme.colorScheme.error else Positive,
                        onOpen = { onOpenOrder(summary.order.id) },
                        actions = { OrderActions(summary, !busy && details.summary.group.state != GroupState.FORMING, inlineActions, onAction) },
                    )
                    if (!inlineActions) OrderActions(summary, !busy && details.summary.group.state != GroupState.FORMING, false, onAction)
                }
                if (details.orders.isEmpty()) Text("В группе пока нет заказов", Modifier.padding(12.dp))
                HorizontalDivider(color = GroupOutline, thickness = 1.dp)
                FinancialRow("Итого", details.summary.sales.formatAmount(), details.summary.knownCosts.formatAmount(),
                    if (details.summary.isComplete) details.summary.preliminaryProfit.formatAmount() else "—", inlineActions,
                    total = true, profitColor = if (details.summary.preliminaryProfit < Money.ZERO) MaterialTheme.colorScheme.error else Positive)
            }
        }
    }
}

@Composable
private fun FinancialRow(
    name: String, income: String, costs: String, profit: String, inlineActions: Boolean,
    heading: Boolean = false, total: Boolean = false, profitColor: Color = Positive,
    onOpen: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null,
) {
    val nameWeight = if (inlineActions) 33f else 1.6f
    val amountWeight = if (inlineActions) 15f else 1f
    val valueStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = if (inlineActions && !heading) 10.5.sp else 12.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .background(if (heading) TableHeading else if (total) TotalBackground else Color.White),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(nameWeight).fillMaxHeight()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 6.dp, vertical = if (heading || total) 12.dp else 10.dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
                    fontWeight = if (heading || total) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
        listOf("Доход" to income, "Расход" to costs, "Прибыль" to profit).forEachIndexed { index, (description, value) ->
            if (!heading && !total) VerticalDivider(color = GroupOutline, thickness = 1.dp)
            Box(Modifier.weight(amountWeight).padding(horizontal = 1.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(value, style = valueStyle, fontWeight = if (heading || total || index == 2) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == 2 && !heading) profitColor else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { contentDescription = "$description: $value" })
            }
        }
        if (inlineActions) {
            if (!heading && !total) VerticalDivider(color = GroupOutline, thickness = 1.dp)
            Box(Modifier.weight(22f).padding(horizontal = 1.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                if (heading) Text("Действия", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                else if (actions != null) actions()
            }
        }
    }
}

@Composable
private fun OrderActions(summary: OrderSummary, enabled: Boolean, stacked: Boolean, onAction: (UUID, OrderAction, Boolean) -> Unit) {
    val progress = summary.order.fulfillment
    val entries = listOf(
        Triple(OrderAction.WITHDRAWN, "Снял", progress.withdrawn),
        Triple(OrderAction.TRANSFERRED, "Передал", progress.transferred),
        Triple(OrderAction.RECEIVED, "Получено", progress.received),
    )
    val action: @Composable (Triple<OrderAction, String, Boolean>, Modifier) -> Unit = { (step, label, checked), modifier ->
        val description = when (step) {
            OrderAction.WITHDRAWN -> "Получены деньги от Дениса"
            OrderAction.TRANSFERRED -> "Деньги за товар переданы посреднику"
            OrderAction.RECEIVED -> "Товар получен"
        }
        Row(modifier.heightIn(min = if (stacked) 28.dp else 44.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onAction(summary.order.id, step, it) })
            .semantics { contentDescription = "Заказ №${summary.order.number}: $description" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (stacked) Arrangement.Start else Arrangement.Center) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(if (stacked) 16.dp else 18.dp))
            Text(label, Modifier.padding(start = if (stacked) 2.dp else 4.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = if (stacked) 9.5.sp else 11.sp, letterSpacing = 0.sp),
                textAlign = TextAlign.Center)
        }
    }
    if (stacked) Column(Modifier.fillMaxWidth()) { entries.forEach { action(it, Modifier.fillMaxWidth()) } }
    else Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { entries.forEach { action(it, Modifier.weight(1f)) } }
}
