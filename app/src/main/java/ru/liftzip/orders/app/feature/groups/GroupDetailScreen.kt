package ru.liftzip.orders.app.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.feature.orders.EditorCostDraft
import ru.liftzip.orders.app.ui.CostsEditor
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.GroupDetailViewModel
import ru.liftzip.orders.app.ui.SectionTitle
import ru.liftzip.orders.app.ui.StatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.formatDate
import ru.liftzip.orders.app.ui.parseDecimal
import ru.liftzip.orders.domain.model.CostDraft
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
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
    var loadedRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(details?.summary?.group?.revision) {
        details?.let { current ->
            if (loadedRevision == current.summary.group.revision) return@let
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
        topBar = {
            TopAppBar(
                title = { Text("Группа №${details?.summary?.group?.number ?: "…"}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val current = details
            if (current == null) {
                item { Text("Загрузка…") }
            } else {
                item {
                    StatusBadge(if (current.summary.group.state == GroupState.FORMING) "Формируется" else "На расчёте")
                    Text("Заказов: ${current.summary.orderCount}")
                    Text("Продажи: ${current.summary.sales.format()}")
                    Text("Полученная оплата: ${current.summary.receivedPayment.format()}")
                    Text("Известные расходы: ${current.summary.knownCosts.format()}")
                    Text("Предварительная прибыль: ${current.summary.preliminaryProfit.format()}", fontWeight = FontWeight.SemiBold)
                    if (!current.summary.isComplete) {
                        Text("Расчёт неполный; неизвестные затраты не заменены нулём", color = MaterialTheme.colorScheme.error)
                    }
                    Text("Статус «На расчёте» не означает утверждение расчёта или подтверждение выкупа")
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
                item { SectionTitle("Заказы группы") }
                if (current.orders.isEmpty()) item { EmptyState("В группе пока нет заказов") }
                items(current.orders.size) { index ->
                    val summary = current.orders[index]
                    Card(Modifier.fillMaxWidth().clickable { onOpenOrder(summary.order.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Заказ №${summary.order.number} · ${summary.order.counterparty}", fontWeight = FontWeight.SemiBold)
                            Text("${summary.order.date.formatDate()} · ${summary.sales.format()}")
                            Text(if (summary.calculation.isComplete) "Предварительный расчёт" else "Расчёт неполный")
                            Text("Прибыль без групповых расходов: ${summary.calculation.preliminaryProfitWithoutGroupCosts.format()}")
                        }
                    }
                }
                item {
                    CostsEditor(
                        title = "Расходы группы", costs = costs, errors = emptyMap(), errorPrefix = "group_cost",
                        onAdd = { costs = costs + EditorCostDraft() },
                        onChange = { index, transform -> costs = costs.mapIndexed { i, cost -> if (i == index) transform(cost) else cost } },
                        onRemove = { index -> costs = costs.filterIndexed { i, _ -> i != index } },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable { confirmed = !confirmed }) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("Все групповые затраты указаны, включая нулевые", Modifier.align(Alignment.CenterVertically))
                    }
                    OutlinedButton(
                        onClick = {
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
                            if (parsed.size != costs.size) return@OutlinedButton
                            scope.launch {
                                try {
                                    repository.saveGroupCosts(SaveGroupCostsCommand(groupId, confirmed, parsed))
                                    localMessage = "Расходы группы сохранены"
                                } catch (error: Exception) { localMessage = error.message }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить расходы группы") }
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
