package ru.liftzip.orders.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.liftzip.orders.app.feature.orders.EditorCostDraft
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.Currency

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    error: String? = null,
    enabled: Boolean = true,
    decimal: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = modifier,
        enabled = enabled, isError = error != null, minLines = minLines,
        supportingText = { if (error != null) Text(error) },
        keyboardOptions = if (decimal) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
    )
}

@Composable
fun StatusBadge(text: String, isWarning: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun EmptyState(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CostsEditor(
    title: String,
    costs: List<EditorCostDraft>,
    errors: Map<String, String>,
    errorPrefix: String,
    onAdd: () -> Unit,
    onChange: (Int, (EditorCostDraft) -> EditorCostDraft) -> Unit,
    onRemove: (Int) -> Unit,
    showHeadingAndAdd: Boolean = true,
) {
    if (showHeadingAndAdd) SectionTitle(title)
    costs.forEachIndexed { index, cost ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var kindExpanded by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { kindExpanded = true }, modifier = Modifier.weight(1f)) {
                        Text(cost.kind.russian())
                    }
                    DropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                        CostKind.entries.filter { it != CostKind.DELIVERY }.forEach { kind ->
                            DropdownMenuItem(text = { Text(kind.russian()) }, onClick = {
                                onChange(index) { it.copy(kind = kind) }; kindExpanded = false
                            })
                        }
                    }
                    TextButton(onClick = { onRemove(index) }) { Text("Удалить") }
                }
                AppTextField(
                    cost.amount, { value -> onChange(index) { it.copy(amount = value) } },
                    "Сумма расхода", error = errors["${errorPrefix}_${index}_amount"], decimal = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Currency.entries.forEach { currency ->
                        FilterChip(selected = cost.currency == currency, onClick = {
                            onChange(index) { it.copy(currency = currency, rate = if (currency == Currency.RUB) "1" else "") }
                        }, label = { Text(currency.name) })
                    }
                }
                AppTextField(
                    cost.rate, { value -> onChange(index) { it.copy(rate = value) } },
                    "Курс RUB за 1 CNY", error = errors["${errorPrefix}_${index}_rate"],
                    enabled = cost.currency == Currency.CNY, decimal = true,
                )
                Row(Modifier.fillMaxWidth().clickable { onChange(index) { it.copy(isConfirmed = !it.isConfirmed) } }) {
                    Checkbox(checked = cost.isConfirmed, onCheckedChange = { checked ->
                        onChange(index) { it.copy(isConfirmed = checked) }
                    })
                    Text("Сумма подтверждена", Modifier.align(Alignment.CenterVertically))
                }
                AppTextField(cost.comment, { value -> onChange(index) { it.copy(comment = value) } }, "Комментарий")
                Text("Дата: ${cost.date.formatDate()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showHeadingAndAdd) OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Добавить расход") }
}

fun CostKind.russian(): String = when (this) {
    CostKind.PURCHASE -> "Закупка"
    CostKind.DELIVERY -> "Доставка · старая запись"
    CostKind.COMMISSION -> "Комиссия"
    CostKind.OTHER -> "Прочее"
}
