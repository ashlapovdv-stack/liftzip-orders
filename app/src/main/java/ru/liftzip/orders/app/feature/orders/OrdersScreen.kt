package ru.liftzip.orders.app.feature.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import ru.liftzip.orders.app.feature.mail.GroupDraftStore
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.OrdersViewModel
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.OrderStatusBadge
import ru.liftzip.orders.app.ui.OrderFulfillmentActions
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.formatDate
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@Composable
fun OrdersScreen(repository: OrdersRepository, onOpenOrder: (UUID) -> Unit, onNewOrder: () -> Unit, onOpenGroup: (UUID) -> Unit) {
    val context = LocalContext.current
    val draftStore = remember { GroupDraftStore(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var draftRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) draftRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val model = viewModel<OrdersViewModel>(factory = viewModelFactory { initializer { OrdersViewModel(repository) } })
    val orders by model.orders.collectAsState()
    val query by model.currentQuery.collectAsState()
    val filter by model.currentFilter.collectAsState()
    val limit by model.currentLimit.collectAsState()
    val deliveryOnly = filter == OrdersFilter.IN_DELIVERY
    val groupIds = remember(orders) { orders.mapTo(mutableSetOf()) { it.order.groupId } }
    var draftsByGroup by remember(draftStore) { mutableStateOf(emptyMap<UUID, Set<UUID>>()) }
    LaunchedEffect(draftStore, groupIds, draftRevision) {
        draftsByGroup = withContext(Dispatchers.IO) { draftStore.draftOrderIdsByGroup(groupIds) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ru.liftzip.orders.app.ui.CompactSearchField(query, model::search, "Поиск заказов", "Поиск: номер, клиент, товар, артикул", "orders-search")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(modifier = Modifier.height(32.dp), selected = !deliveryOnly && filter == OrdersFilter.ALL, onClick = { model.filter(OrdersFilter.ALL) }, label = { Text("Все", style = MaterialTheme.typography.labelMedium) })
                    FilterChip(
                        modifier = Modifier.height(32.dp),
                        selected = !deliveryOnly && filter == OrdersFilter.NEEDS_CALCULATION,
                        onClick = { model.filter(OrdersFilter.NEEDS_CALCULATION) },
                        label = { Text("Нужен расчёт", style = MaterialTheme.typography.labelMedium) },
                    )
                    FilterChip(
                        modifier = Modifier.height(32.dp),
                        selected = deliveryOnly,
                        onClick = { model.filter(if (deliveryOnly) OrdersFilter.ALL else OrdersFilter.IN_DELIVERY) },
                        label = { Text("Доставка", style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            if (orders.isEmpty()) item { EmptyState(if (deliveryOnly) "Заказов на доставке нет" else "Заказов нет") }
            items(orders, key = { it.order.id }, contentType = { "order" }) { summary ->
                val order = summary.order
                val hasDraft = draftsByGroup[order.groupId]?.contains(order.id) == true
                OrderCard(summary, onClick = { onOpenOrder(order.id) }, onOpenGroup = { onOpenGroup(order.groupId) },
                    hasDraft = hasDraft)
            }
            if (orders.size >= limit) {
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
private fun OrderCard(summary: OrderSummary, onClick: () -> Unit, onOpenGroup: () -> Unit, hasDraft: Boolean) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("order-card-${summary.order.number}"), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("№${summary.order.number} · ${summary.order.date.formatDate()}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!summary.calculation.isComplete) {
                        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                            Text("Расчёт неполный", Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    OrderStatusBadge(summary.order.fulfillment.stage)
                }
            }
            Text(summary.order.counterparty, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Продажа: ${(summary.calculation.priceForDenis?.format() ?: "—").replace(' ', '\u00a0')}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("Оплата: ${summary.order.receivedPayment.format().replace(' ', '\u00a0')}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OrderFulfillmentActions(summary.order.number, summary.order.fulfillment, compact = true,
                        mailDraftCreated = hasDraft,
                        captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp))
                }
                TextButton(onClick = onOpenGroup, modifier = Modifier.height(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("Группа №${summary.groupNumber} →", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
