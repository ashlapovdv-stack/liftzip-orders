package ru.liftzip.orders.app.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.AppTextField
import ru.liftzip.orders.app.ui.OrdersViewModel
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.StatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.formatDate
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@Composable
fun OrdersScreen(repository: OrdersRepository, onOpenOrder: (UUID) -> Unit, onNewOrder: () -> Unit) {
    val model = viewModel<OrdersViewModel>(factory = viewModelFactory { initializer { OrdersViewModel(repository) } })
    val orders by model.orders.collectAsState()
    val query by model.currentQuery.collectAsState()
    val filter by model.currentFilter.collectAsState()
    val limit by model.currentLimit.collectAsState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppTextField(query, model::search, "Поиск: номер, клиент, товар, артикул")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == OrdersFilter.ALL, onClick = { model.filter(OrdersFilter.ALL) }, label = { Text("Все") })
                    FilterChip(
                        selected = filter == OrdersFilter.NEEDS_CALCULATION,
                        onClick = { model.filter(OrdersFilter.NEEDS_CALCULATION) },
                        label = { Text("Нужен расчёт") },
                    )
                }
            }
            if (orders.isEmpty()) item { EmptyState("Заказов нет. Создайте первый оплаченный заказ") }
            items(orders, key = { it.order.id }) { summary ->
                OrderCard(summary, onClick = { onOpenOrder(summary.order.id) })
            }
            if (orders.size >= limit && limit < 500) {
                item { OutlinedButton(onClick = model::showMore, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") } }
            }
        }
        FloatingActionButton(
            onClick = onNewOrder,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Новый заказ") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderCard(summary: OrderSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "№${summary.order.number} · ${summary.order.date.formatDate()}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(
                    if (summary.calculation.isComplete) "Предварительный расчёт" else "Расчёт неполный",
                    isWarning = !summary.calculation.isComplete,
                )
            }
            Text(summary.order.counterparty, style = MaterialTheme.typography.bodyLarge)
            if (summary.composition.isNotBlank()) {
                Text(summary.composition, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Продажа: ${summary.sales.format()} · Оплата: ${summary.order.receivedPayment.format()}")
            Text(
                "Группа №${summary.groupNumber} · ${if (summary.groupState.name == "FORMING") "Формируется" else "На расчёте"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
