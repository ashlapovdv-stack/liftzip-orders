package ru.liftzip.orders.app.feature.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.GroupsViewModel
import ru.liftzip.orders.app.ui.GroupStatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.domain.model.GroupSummary
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@Composable
fun GroupsScreen(repository: OrdersRepository, onOpenGroup: (UUID) -> Unit) {
    val model = viewModel<GroupsViewModel>(factory = viewModelFactory { initializer { GroupsViewModel(repository) } })
    val groups by model.groups.collectAsState()
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (groups.isEmpty()) item { EmptyState("Подготовка первой группы…") }
        items(groups, key = { it.group.id }) { group -> GroupCard(group) { onOpenGroup(group.group.id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupCard(summary: GroupSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Группа №${summary.group.number}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                GroupStatusBadge(summary.group.state)
            }
            Text(buildString {
                append("Заказов: ${summary.orderCount}")
                if (summary.incompleteCount > 0) append(" · Неполных расчётов: ${summary.incompleteCount}")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Д · без доставки" to summary.income, "К · закупка" to summary.purchaseCost,
                    "∆ · прибыль" to summary.profit)
                    .forEachIndexed { index, (label, amount) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(amount?.format() ?: "—", style = MaterialTheme.typography.bodySmall.copy(
                                letterSpacing = 0.sp, fontFeatureSettings = "tnum"),
                                fontWeight = if (index == 2) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    }
            }
            if (!summary.isComplete) Text("Расчёт неполный",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
