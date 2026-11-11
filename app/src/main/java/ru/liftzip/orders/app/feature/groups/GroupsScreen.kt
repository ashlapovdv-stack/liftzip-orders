package ru.liftzip.orders.app.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.GroupsViewModel
import ru.liftzip.orders.app.ui.StatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.GroupSummary
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@Composable
fun GroupsScreen(repository: OrdersRepository, onOpenGroup: (UUID) -> Unit) {
    val model = viewModel<GroupsViewModel>(factory = viewModelFactory { initializer { GroupsViewModel(repository) } })
    val groups by model.groups.collectAsState()
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) item { EmptyState("Подготовка первой группы…") }
        items(groups, key = { it.group.id }) { group -> GroupCard(group) { onOpenGroup(group.group.id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCard(summary: GroupSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Группа №${summary.group.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusBadge(if (summary.group.state == GroupState.FORMING) "Формируется" else "На расчёте")
            }
            Text("Заказов: ${summary.orderCount} · Неполных расчётов: ${summary.incompleteCount}")
            Text("Продажи: ${summary.sales.format()}")
            Text("Получено: ${summary.receivedPayment.format()}")
            Text("Известные расходы: ${summary.knownCosts.format()}")
            Text("Предварительная прибыль: ${summary.preliminaryProfit.format()}", fontWeight = FontWeight.SemiBold)
            if (!summary.isComplete) Text("Расчёт неполный; итог не является окончательной прибылью", color = MaterialTheme.colorScheme.error)
        }
    }
}
