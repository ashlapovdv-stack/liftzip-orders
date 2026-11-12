package ru.liftzip.orders.app.feature.orders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.CardDefaults
import ru.liftzip.orders.app.ui.CompactTopBar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.AppTextField
import ru.liftzip.orders.app.ui.CostsEditor
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.PriceSearchViewModel
import ru.liftzip.orders.app.ui.OrderFulfillmentActions
import ru.liftzip.orders.app.ui.GroupStatusBadge
import ru.liftzip.orders.app.ui.SectionTitle
import ru.liftzip.orders.app.ui.StatusBadge
import ru.liftzip.orders.app.ui.format
import ru.liftzip.orders.app.ui.label
import ru.liftzip.orders.app.ui.formatDate
import ru.liftzip.orders.app.ui.formatDateTime
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderEditorScreen(
    repository: OrdersRepository,
    orderId: UUID?,
    onBack: () -> Unit,
) {
    val model = viewModel<OrderEditorViewModel>(
        key = "order-${orderId ?: "new"}",
        factory = viewModelFactory { initializer { OrderEditorViewModel(repository, orderId) } },
    )
    val state by model.state.collectAsState()
    var editingLine by remember { mutableStateOf<Int?>(null) }
    var catalogLine by remember { mutableStateOf<Int?>(null) }
    var historyExpanded by remember { mutableStateOf(false) }
    var confirmDeleteOrder by remember { mutableStateOf(false) }
    var deleteLineIndex by remember { mutableStateOf<Int?>(null) }

    BackHandler { if (model.requestBack()) onBack() }

    Scaffold(
        topBar = {
            CompactTopBar(state.orderNumber?.let { "Заказ №$it" } ?: "Новый заказ",
                onBack = { if (model.requestBack()) onBack() }) {
                TextButton(onClick = { model.save { onBack() } }, enabled = state.loaded && !state.saving && !state.actionsSaving) {
                    Text(if (state.saving) "Сохраняю…" else "Сохранить", fontWeight = FontWeight.SemiBold)
                }

            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.loaded) item { Text("Загрузка…") }
            state.generalError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item {
                AppTextField(state.counterparty, { value -> model.update { it.copy(counterparty = value) } },
                    "Название заказа", error = state.errors["counterparty"], enabled = !state.salesLocked)
            }
            item {
                OrderSection {
                    Row(Modifier.fillMaxWidth().testTag("order-actions"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OrderFulfillmentActions(state.orderNumber, state.fulfillment,
                                enabled = state.loaded && !state.saving && !state.actionsSaving,
                                captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                                onAction = model::setAction)
                        }
                        AppTextField(state.orderRate, { value -> model.update { it.copy(orderRate = value) } },
                            "Курс, ₽/¥", modifier = Modifier.width(96.dp), error = state.errors["orderRate"], decimal = true)
                    }
                    state.groupNumber?.let { number ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Группа №$number", style = MaterialTheme.typography.bodySmall)
                            GroupStatusBadge(state.groupState)
                        }
                    }
                }
            }
            item { SectionTitle("Товары · ${state.lines.size}") }
            items(state.lines.size) { index ->
                val line = state.lines[index]
                Card(Modifier.fillMaxWidth().clickable { editingLine = index }, shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
                    Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(line.name.ifBlank { "Новая позиция" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (line.article.isNotBlank()) Text(line.article, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${line.quantity} × ${line.salePrice.ifBlank { "—" }} ₽", style = MaterialTheme.typography.bodySmall)
                            if (state.errors.keys.any { it.startsWith("line_${index}_") }) Text("Проверьте поля позиции", color = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { deleteLineIndex = index }, enabled = !state.saving, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Delete, "Удалить позицию ${index + 1}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (!state.salesLocked) item {
                OutlinedButton(onClick = { model.addLine(); editingLine = state.lines.size }, modifier = Modifier.fillMaxWidth().height(40.dp)) { Text("Добавить позицию") }
            }
            item {
                OrderSection {
                    Text("Доставка", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1.2f)) {
                            AppTextField(state.manualDeliveryPrice, { value -> model.update { it.copy(manualDeliveryPrice = value) } },
                                "Цена доставки, ₽", error = state.errors["manualDeliveryPrice"], decimal = true)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Расчётная", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(model.deliveryPreview()?.format() ?: "Нет данных", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            item { AppTextField(state.purchaseTotal, { value -> model.update { it.copy(purchaseTotal = value) } },
                "Закупка заказа, ₽", error = state.errors["purchaseTotal"], decimal = true) }
            item { AppTextField(state.comment, { value -> model.update { it.copy(comment = value) } }, "Комментарий", minLines = 2) }
            model.preview()?.let { calculation -> item {
                OrderSection {
                    Text("Итого", style = MaterialTheme.typography.titleSmall)
                    listOf("Цена для Дениса" to calculation.sales.format(),
                        "Закупка и доставка" to (if (calculation.isComplete) calculation.knownCost.format() else "Неполные данные"),
                        "Прибыль" to (if (calculation.isComplete) calculation.preliminaryProfitWithoutGroupCosts.format() else "Не рассчитана"))
                        .forEachIndexed { index, (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = if (index == 2) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (index == 2 && calculation.preliminaryProfitWithoutGroupCosts < ru.liftzip.orders.domain.model.Money.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    if (!calculation.isComplete) Text("Расчёт неполный", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            } }
            if (state.createdAt != null) item {
                Text("Создан: ${state.createdAt!!.formatDateTime()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.history.isNotEmpty()) item {
                TextButton(onClick = { historyExpanded = !historyExpanded }) { Text(if (historyExpanded) "Скрыть изменения" else "История изменений (${state.history.size})") }
                if (historyExpanded) state.history.forEach { event ->
                    Text("${event.eventAt.formatDateTime()} · ${event.changeType}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.orderId != null) item {
                Button(onClick = { confirmDeleteOrder = true }, enabled = state.loaded && !state.saving && !state.actionsSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "Удалить заказ" },
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Icon(Icons.Default.Delete, null, Modifier.size(20.dp))
                    Text("Удалить заказ", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (confirmDeleteOrder) AlertDialog(onDismissRequest = { confirmDeleteOrder = false },
        title = { Text("Удалить заказ?") }, text = { Text("Заказ, его позиции и цены исчезнут из списков и расчётов. Запись об удалении сохранится в истории.") },
        confirmButton = { TextButton(onClick = { confirmDeleteOrder = false; model.delete(onBack) }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDeleteOrder = false }) { Text("Отмена") } })
    deleteLineIndex?.takeIf { it in state.lines.indices }?.let { index ->
        val lastSavedLine = state.lines.size == 1 && state.orderId != null
        AlertDialog(onDismissRequest = { deleteLineIndex = null },
            title = { Text(if (lastSavedLine) "Удалить последнюю позицию и заказ?" else "Удалить позицию?") },
            text = { Text(state.lines[index].name.ifBlank { "Новая позиция" }) },
            confirmButton = { TextButton(onClick = { deleteLineIndex = null; if (lastSavedLine) model.delete(onBack) else model.removeLine(index) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteLineIndex = null }) { Text("Отмена") } })
    }

    editingLine?.takeIf { it in state.lines.indices }?.let { index ->
        ModalBottomSheet(onDismissRequest={ editingLine=null }, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true), modifier=Modifier.imePadding()) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().padding(horizontal=16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Text("Позиция ${index+1}",style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={editingLine=null}) { Text("Готово") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val line = state.lines[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Позиция ${index + 1}")
                            Box(Modifier.weight(1f))
                            if (state.lines.isNotEmpty()) {
                                TextButton(onClick = { deleteLineIndex = index; editingLine = null }) { Text("Удалить") }
                            }
                        }
                        OutlinedButton(
                            onClick = { catalogLine = index }, enabled = !state.salesLocked,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Выбрать товар из каталога") }
                        AppTextField(
                            line.name, { value -> model.updateLine(index) { it.copy(name = value, productId = null) } },
                            "Название товара", error = state.errors["line_${index}_name"], enabled = !state.salesLocked,
                        )
                        AppTextField(
                            line.article, { value -> model.updateLine(index) { it.copy(article = value, productId = null) } },
                            "Артикул (необязательно)", enabled = !state.salesLocked,
                        )
                        AppTextField(
                            line.quantity, { value -> model.updateLine(index) { it.copy(quantity = value) } },
                            "Количество", error = state.errors["line_${index}_quantity"],
                            enabled = !state.salesLocked, decimal = true,
                        )
                        AppTextField(
                            line.salePrice, { value -> model.updateLine(index) { it.copy(salePrice = value) } },
                            "Цена для Дениса, ₽/шт (включая доставку)", error = state.errors["line_${index}_sale"],
                            enabled = !state.salesLocked, decimal = true,
                        )
                        state.errors["line_${index}_zero"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (line.salePrice.replace(',', '.').toBigDecimalOrNull()?.signum() == 0) {
                            Row(Modifier.clickable { model.updateLine(index) { it.copy(zeroSalePriceConfirmed = !it.zeroSalePriceConfirmed) } }) {
                                Checkbox(
                                    checked = line.zeroSalePriceConfirmed,
                                    onCheckedChange = { checked -> model.updateLine(index) { it.copy(zeroSalePriceConfirmed = checked) } },
                                    enabled = !state.salesLocked,
                                )
                                Text("Подтверждаю нулевую цену", Modifier.align(Alignment.CenterVertically))
                            }
                        }
                        SectionTitle("Закупочный расчёт")
                        AppTextField(
                            line.purchasePrice, { value -> model.updateLine(index) { it.copy(purchasePrice = value) } },
                            "Закупочная цена за единицу, ${if (line.purchaseCurrency == Currency.RUB) "₽" else "¥"} (справочно)", error = state.errors["line_${index}_purchase"], decimal = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Currency.entries.forEach { currency ->
                                FilterChip(
                                    selected = line.purchaseCurrency == currency,
                                    onClick = {
                                        model.updateLine(index) {
                                            it.copy(purchaseCurrency = currency)
                                        }
                                    },
                                    label = { Text(currency.name) },
                                )
                            }
                        }
                        SectionTitle("Ориентир доставки · отдельно от товара")
                        AppTextField(line.deliveryWeight, { value -> model.updateLine(index) { it.copy(deliveryWeight=value,deliveryPlanDate=LocalDate.now()) } }, "Вес одной единицы, кг", decimal=true)
                        AppTextField(line.deliveryTariff, { value -> model.updateLine(index) { it.copy(deliveryTariff=value,deliveryPlanDate=LocalDate.now()) } }, "Тариф, CNY/кг", decimal=true)
                        Text("Расчёт по курсу заказа.",style=MaterialTheme.typography.bodySmall)
                        state.errors["line_${index}_delivery"]?.let { Text(it,color=MaterialTheme.colorScheme.error) }

                    }
                }
                }
            }
        }
    }

    if (state.showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = model::keepEditing,
            title = { Text("Есть несохранённые изменения") },
            text = { Text("Сохранить заказ перед выходом?") },
            confirmButton = { TextButton(onClick = { model.save { onBack() } }) { Text("Сохранить") } },
            dismissButton = {
                Row {
                    TextButton(onClick = model::keepEditing) { Text("Остаться") }
                    TextButton(onClick = { model.discardChanges(); onBack() }) { Text("Отменить изменения") }
                }
            },
        )
    }

    catalogLine?.let { lineIndex ->
        ProductCatalogDialog(repository, onDismiss = { catalogLine = null }, onSelect = {
            model.selectProduct(lineIndex, it); catalogLine = null
        })
    }


}

@Composable
private fun ProductCatalogDialog(
    repository: OrdersRepository,
    onDismiss: () -> Unit,
    onSelect: (ru.liftzip.orders.domain.model.Product) -> Unit,
) {
    val model = viewModel<PriceSearchViewModel>(factory = viewModelFactory { initializer { PriceSearchViewModel(repository) } })
    val products by model.products.collectAsState()
    val query by model.currentQuery.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Каталог товаров") },
        text = {
            Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(query, model::search, "Название или артикул")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (products.isEmpty()) item { EmptyState("Товар не найден") }
                    items(products, key = { it.id }) { product ->
                        Card(Modifier.fillMaxWidth().clickable { onSelect(product) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(product.name, fontWeight = FontWeight.SemiBold)
                                product.article?.let { Text("Артикул: $it") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun OrderSection(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
