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
import androidx.compose.ui.draw.alpha
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderFulfillmentActions(
    orderNumber: Long?,
    progress: OrderFulfillment,
    enabled: Boolean = true,
    compact: Boolean = false,
    mailDraftCreated: Boolean? = null,
    captionStyle: TextStyle = MaterialTheme.typography.labelSmall,
    onAction: ((OrderAction, Boolean) -> Unit)? = null,
) {
    val green = Color(0xFF17784B)
    val entries = listOf(
        Triple(OrderAction.WITHDRAWN, "Снял", progress.withdrawn),
        Triple(OrderAction.TRANSFERRED, "Передал", progress.transferred),
        Triple(OrderAction.RECEIVED, "Получено", progress.received),
    )
    val render: @Composable (Triple<OrderAction, String, Boolean>, Modifier) -> Unit = { (step, label, checked), layout ->
            val description = when (step) {
                OrderAction.WITHDRAWN -> "Получены деньги от Дениса"
                OrderAction.TRANSFERRED -> "Деньги за товар переданы посреднику"
                OrderAction.RECEIVED -> "Товар получен"
            }
            val tag = "order-${orderNumber ?: "new"}-$step"
            val interaction = if (onAction != null) Modifier.toggleable(
                value = checked, enabled = enabled, role = Role.Checkbox,
                onValueChange = { onAction(step, it) },
            ) else Modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            }
            val cell = layout.then(interaction).alpha(if (onAction != null && !enabled) 0.45f else 1f).testTag(tag).semantics { contentDescription = "${orderNumber?.let { "Заказ №$it" } ?: "Новый заказ"}: $description" }
            val square: @Composable () -> Unit = {
                Box(Modifier.size(if (compact) 12.dp else 16.dp).testTag("$tag-square")
                    .background(if (checked) green else Color.Transparent, RoundedCornerShape(3.dp))
                    .border(1.dp, if (checked) green else Color(0xFF9AA3AD), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(if (compact) 10.dp else 14.dp))
                }
            }
            if (compact) {
                Row(cell.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    square()
                    Text(label, Modifier.testTag("$tag-caption"), style = captionStyle, maxLines = 1, softWrap = false)
                }
            } else {
                Column(cell.heightIn(min = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    square()
                    Text(label, Modifier.fillMaxWidth().padding(top = 3.dp).testTag("$tag-caption"),
                        style = captionStyle, textAlign = TextAlign.Center)
                }
            }
    }
    val mailIndicator: @Composable (Modifier) -> Unit = { layout ->
        val indicator = layout.testTag("order-${orderNumber ?: "new"}-MAIL").semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(mailDraftCreated == true)
            contentDescription = "Письмо сформировано"
        }
        val square: @Composable () -> Unit = {
            Box(Modifier.size(if (compact) 12.dp else 16.dp)
                .background(if (mailDraftCreated == true) green else Color.Transparent, RoundedCornerShape(3.dp))
                .border(1.dp, if (mailDraftCreated == true) green else Color(0xFF9AA3AD), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center) {
                if (mailDraftCreated == true) Icon(Icons.Default.Check, null, tint = Color.White,
                    modifier = Modifier.size(if (compact) 10.dp else 14.dp))
            }
        }
        if (compact) {
            Row(indicator.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                square()
                Text("Письмо", style = captionStyle, maxLines = 1, softWrap = false)
            }
        } else {
            Column(indicator.heightIn(min = 44.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                square()
                Text("Письмо", Modifier.fillMaxWidth().padding(top = 3.dp),
                    style = captionStyle, textAlign = TextAlign.Center)
            }
        }
    }
    if (compact) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mailDraftCreated != null) mailIndicator(Modifier)
            entries.forEach { render(it, Modifier) }
        }
    } else Row(Modifier.fillMaxWidth()) {
        if (mailDraftCreated != null) mailIndicator(Modifier.weight(1f))
        entries.forEach { render(it, Modifier.weight(1f)) }
    }
}
