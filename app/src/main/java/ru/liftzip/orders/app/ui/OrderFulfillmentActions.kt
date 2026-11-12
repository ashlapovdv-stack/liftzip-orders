package ru.liftzip.orders.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderFulfillment

/** All three screens render the same persisted order flags. Null callback means an indicator. */
@Composable
fun OrderFulfillmentActions(
    orderNumber: Long,
    progress: OrderFulfillment,
    enabled: Boolean = true,
    captionStyle: TextStyle = MaterialTheme.typography.labelSmall,
    onAction: ((OrderAction, Boolean) -> Unit)? = null,
) {
    val green = Color(0xFF17784B)
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
            val tag = "order-$orderNumber-$step"
            val interaction = if (onAction != null) Modifier.toggleable(
                value = checked, enabled = enabled, role = Role.Checkbox,
                onValueChange = { onAction(step, it) },
            ) else Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            }
            Column(Modifier.weight(1f).heightIn(min = 44.dp).then(interaction)
                .testTag(tag).semantics { contentDescription = "Заказ №$orderNumber: $description" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(16.dp).testTag("$tag-square")
                    .background(if (checked) green else Color.Transparent, RoundedCornerShape(3.dp))
                    .border(1.dp, if (checked) green else Color(0xFF9AA3AD), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Text(label, Modifier.fillMaxWidth().padding(top = 3.dp).testTag("$tag-caption"),
                    style = captionStyle, textAlign = TextAlign.Center)
            }
        }
    }
}
