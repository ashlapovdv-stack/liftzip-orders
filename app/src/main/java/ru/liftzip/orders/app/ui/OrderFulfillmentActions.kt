package ru.liftzip.orders.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CurrencyRuble
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.liftzip.orders.domain.model.OrderAction
import ru.liftzip.orders.domain.model.OrderFulfillment
import ru.liftzip.orders.domain.model.OrderStage

internal data class GroupStageHighlight(
    val action: OrderAction,
    val stripe: Color,
    val background: Color,
    val foreground: Color,
)

internal fun OrderStage.groupStageHighlight(): GroupStageHighlight = when (this) {
    OrderStage.ACCEPTED -> GroupStageHighlight(
        OrderAction.WITHDRAWN, Color(0xFF8A53AE), Color(0xFFEEE0FA), Color(0xFF583479),
    )
    OrderStage.AWAITING_TRANSFER -> GroupStageHighlight(
        OrderAction.TRANSFERRED, Color(0xFF247F72), Color(0xFFD6EEE9), Color(0xFF174E44),
    )
    OrderStage.AWAITING_PAYMENT -> GroupStageHighlight(
        OrderAction.PAID, Color(0xFFBC7200), Color(0xFFFFDF92), Color(0xFF613A00),
    )
    OrderStage.IN_DELIVERY -> GroupStageHighlight(
        OrderAction.RECEIVED, Color(0xFF306DC1), Color(0xFFD2E7FF), Color(0xFF18437F),
    )
    OrderStage.COMPLETED -> GroupStageHighlight(
        OrderAction.RECEIVED, Color(0xFF358456), Color(0xFFD9EFDF), Color(0xFF245E3A),
    )
}

/** All three screens render the same persisted order flags. Null callback means an indicator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrderFulfillmentActions(
    orderNumber: Long?,
    progress: OrderFulfillment,
    enabled: Boolean = true,
    compact: Boolean = false,
    twoRows: Boolean = false,
    mailDraftCreated: Boolean? = null,
    captionStyle: TextStyle = MaterialTheme.typography.labelSmall,
    groupHighlight: GroupStageHighlight? = null,
    onAction: ((OrderAction, Boolean) -> Unit)? = null,
) {
    val green = Color(0xFF17784B)
    val entries = listOf(
        Triple(OrderAction.WITHDRAWN, "Снял", progress.withdrawn),
        Triple(OrderAction.TRANSFERRED, "Передал", progress.transferred),
        Triple(OrderAction.PAID, "Оплата", progress.paid),
        Triple(OrderAction.RECEIVED, "Получено", progress.received),
    )
    val render: @Composable (Triple<OrderAction, String, Boolean>, Modifier) -> Unit = { (step, label, checked), layout ->
            val description = when (step) {
                OrderAction.WITHDRAWN -> "Получены деньги от Дениса"
                OrderAction.TRANSFERRED -> "Деньги за товар переданы посреднику"
                OrderAction.PAID -> "Оплата товара подтверждена"
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
            val highlight = groupHighlight?.takeIf { it.action == step }
            val checkColor = highlight?.foreground ?: if (twoRows) Color(0xFF76817F) else green
            val cell = layout.then(interaction)
                .alpha(if (onAction != null && !enabled) 0.45f else 1f)
                .background(highlight?.background ?: Color.Transparent)
                .testTag(tag)
                .semantics {
                    contentDescription = "${orderNumber?.let { "Заказ №$it" } ?: "Новый заказ"}: $description"
                    if (highlight != null && !checked) stateDescription = "Следующий этап, не отмечено"
                }
            val square: @Composable () -> Unit = {
                val size = if (compact) 12.dp else if (twoRows) 14.dp else 16.dp
                Box(Modifier.size(size)
                    .then(if (twoRows) Modifier else Modifier.testTag("$tag-square"))
                    .background(if (checked) checkColor else Color.Transparent, RoundedCornerShape(3.dp))
                    .border(1.dp, if (checked) checkColor else Color(0xFF9AA3AD), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(size - 2.dp))
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
                    if (twoRows) {
                        Row(
                            Modifier.size(width = 38.dp, height = 20.dp).testTag("$tag-square"),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            square()
                            if (highlight != null) GroupActionIcon(step, highlight.foreground)
                        }
                    } else {
                        square()
                    }
                    Text(label, Modifier.fillMaxWidth().padding(top = 3.dp).testTag("$tag-caption"),
                        style = captionStyle, textAlign = TextAlign.Center,
                        color = highlight?.foreground ?: if (twoRows) Color(0xFF4B5756) else Color.Unspecified)
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
    } else if (twoRows) {
        Column(Modifier.fillMaxWidth()) {
            entries.chunked(2).forEachIndexed { index, pair ->
                if (index > 0) HorizontalDivider(color = GroupActionDivider)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    render(pair[0], Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = GroupActionDivider)
                    render(pair[1], Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    } else Row(Modifier.fillMaxWidth()) {
        if (mailDraftCreated != null) mailIndicator(Modifier.weight(1f))
        entries.forEach { render(it, Modifier.weight(1f)) }
    }
}

private val GroupActionDivider = Color(0xFFD8E1E5)

@Composable
private fun GroupActionIcon(action: OrderAction, color: Color) {
    val icon = when (action) {
        OrderAction.WITHDRAWN -> Icons.Outlined.AccountBalanceWallet
        OrderAction.TRANSFERRED -> Icons.AutoMirrored.Outlined.ArrowRightAlt
        OrderAction.PAID -> Icons.Outlined.CurrencyRuble
        OrderAction.RECEIVED -> Icons.Outlined.Inventory2
    }
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
}
