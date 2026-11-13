package ru.liftzip.orders.app.feature.orders

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    val linkScope = rememberCoroutineScope()
    var resolvingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
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
                                enabled = state.loaded && state.orderId != null && state.groupState != GroupState.FORMING && !state.saving && !state.actionsSaving,
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
                    listOf("Цена для Дениса" to calculation.priceForDenis.format(),
                        "Д · без доставки" to (calculation.income?.format() ?: "—"),
                        "К · закупка" to (calculation.purchaseCost?.format() ?: "—"),
                        "∆ · прибыль" to (calculation.profit?.format() ?: "—"))
                        .forEachIndexed { index, (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(value, modifier = Modifier.testTag("order-total-$index"), style = MaterialTheme.typography.bodySmall, fontWeight = if (index == 3) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (index == 3 && (calculation.profit ?: ru.liftzip.orders.domain.model.Money.ZERO) < ru.liftzip.orders.domain.model.Money.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
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
        ModalBottomSheet(
            onDismissRequest = { editingLine = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFFF6F8F8),
            modifier = Modifier.imePadding(),
        ) {
            val line = state.lines[index]
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().padding(horizontal = 12.dp).testTag("position-editor")) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Позиция ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editingLine = null }) { Text("Готово") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrderSection {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Товар", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { catalogLine = index }, enabled = !state.salesLocked) { Text("Из каталога") }
                        }
                        AppTextField(line.name, { value -> model.updateLine(index) { it.copy(name = value, productId = null) } },
                            "Название товара", error = state.errors["line_${index}_name"], enabled = !state.salesLocked)
                        AppTextField(line.article, { value -> model.updateLine(index) { it.copy(article = value, productId = null) } },
                            "ZIP-код", enabled = !state.salesLocked)
                        AppTextField(line.productUrl, { value ->
                            model.updateLine(index) { it.copy(productUrl = value) }
                            linkError = null
                            if (!state.salesLocked && value.isNotBlank() && (value.contains("taobao.com") || value.contains("tb.cn"))) {
                                linkScope.launch {
                                    resolvingLink = true
                                    try {
                                        val resolved = TaobaoLinkResolver.resolve(value)
                                        if (resolved != null && model.state.value.lines.getOrNull(index)?.productUrl == value) {
                                            model.updateLine(index) { it.copy(productUrl = resolved) }
                                        }
                                    } catch (_: Exception) {
                                        // Preserve the original link when resolution fails.
                                    } finally {
                                        resolvingLink = false
                                    }
                                }
                            }
                        }, "Ссылка Taobao", enabled = !state.salesLocked)
                        if (resolvingLink) Text("Преобразование ссылки…", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTextField(line.quantity, { value -> model.updateLine(index) { it.copy(quantity = value) } },
                                "Количество", Modifier.weight(0.8f), error = state.errors["line_${index}_quantity"], enabled = !state.salesLocked, decimal = true)
                            AppTextField(line.salePrice, { value -> model.updateLine(index) { it.copy(salePrice = value) } },
                                "Денису, ₽/шт", Modifier.weight(1.2f), error = state.errors["line_${index}_sale"], enabled = !state.salesLocked, decimal = true)
                        }
                        Text("Цена для Дениса включает доставку", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.errors["line_${index}_zero"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (line.salePrice.replace(',', '.').toBigDecimalOrNull()?.signum() == 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = line.zeroSalePriceConfirmed,
                                    onCheckedChange = { checked -> model.updateLine(index) { it.copy(zeroSalePriceConfirmed = checked) } }, enabled = !state.salesLocked)
                                Text("Подтверждаю нулевую цену", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OrderSection {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Закупка · справочно", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Currency.entries.forEach { currency ->
                                    FilterChip(selected = line.purchaseCurrency == currency,
                                        onClick = { model.updateLine(index) { it.copy(purchaseCurrency = currency) } },
                                        label = { Text(if (currency == Currency.RUB) "₽" else "¥") })
                                }
                            }
                        }
                        AppTextField(line.purchasePrice, { value -> model.updateLine(index) { it.copy(purchasePrice = value) } },
                            "Цена за единицу, ${if (line.purchaseCurrency == Currency.RUB) "₽" else "¥"}", error = state.errors["line_${index}_purchase"], decimal = true)
                    }
                    OrderSection {
                        Text("Доставка · ориентир", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTextField(line.deliveryWeight, { value -> model.updateLine(index) { it.copy(deliveryWeight = value, deliveryPlanDate = LocalDate.now()) } },
                                "Вес единицы, кг", Modifier.weight(1f), decimal = true)
                            AppTextField(line.deliveryTariff, { value -> model.updateLine(index) { it.copy(deliveryTariff = value, deliveryPlanDate = LocalDate.now()) } },
                                "Тариф, ¥/кг", Modifier.weight(1f), decimal = true)
                        }
                        state.errors["line_${index}_delivery"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { deleteLineIndex = index; editingLine = null }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Удалить позицию", Modifier.padding(start = 6.dp))
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
