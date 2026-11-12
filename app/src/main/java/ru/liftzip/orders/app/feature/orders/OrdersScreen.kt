package ru.liftzip.orders.app.feature.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.AppTextField
import ru.liftzip.orders.app.ui.OrdersViewModel
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.GroupStatusBadge
import ru.liftzip.orders.app.ui.OrderFulfillmentActions
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.formatDate
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@Composable
fun OrdersScreen(repository: OrdersRepository, onOpenOrder: (UUID) -> Unit, onNewOrder: () -> Unit, onOpenGroup: (UUID) -> Unit) {
    val model = viewModel<OrdersViewModel>(factory = viewModelFactory { initializer { OrdersViewModel(repository) } })
    val orders by model.orders.collectAsState()
    val query by model.currentQuery.collectAsState()
    val filter by model.currentFilter.collectAsState()
    val limit by model.currentLimit.collectAsState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                OrderCard(summary, onClick = { onOpenOrder(summary.order.id) }, onOpenGroup = { onOpenGroup(summary.order.groupId) })
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
private fun OrderCard(summary: OrderSummary, onClick: () -> Unit, onOpenGroup: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("№${summary.order.number} · ${summary.order.date.formatDate()}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                GroupStatusBadge(summary.groupState)
            }
            Text(summary.order.counterparty, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (summary.composition.isNotBlank()) Text(summary.composition, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Продажа: ${summary.sales.format()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("Оплата: ${summary.order.receivedPayment.format()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            if (!summary.calculation.isComplete) Text("Расчёт неполный", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OrderFulfillmentActions(summary.order.number, summary.order.fulfillment,
                        captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp))
                }
                TextButton(onClick = onOpenGroup, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("Группа №${summary.groupNumber} →", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
