package ru.liftzip.orders.app.feature.orders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import ru.liftzip.orders.app.ui.SectionTitle
import ru.liftzip.orders.app.ui.StatusBadge
import ru.liftzip.orders.app.ui.format
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
    onDelivery: (UUID) -> Unit = {},
) {
    val model = viewModel<OrderEditorViewModel>(
        key = "order-${orderId ?: "new"}",
        factory = viewModelFactory { initializer { OrderEditorViewModel(repository, orderId) } },
    )
    val state by model.state.collectAsState()
    var editingLine by remember { mutableStateOf<Int?>(null) }
    var showExtras by remember { mutableStateOf(false) }
    var catalogLine by remember { mutableStateOf<Int?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }

    BackHandler { if (model.requestBack()) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.orderNumber?.let { "Заказ №$it" } ?: "Новый заказ") },
                navigationIcon = {
                    IconButton(onClick = { if (model.requestBack()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    TextButton(onClick={ model.save { onBack() } }, enabled=state.loaded && !state.saving) {
                        Text(if(state.saving) "Сохраняю…" else "Сохранить", fontWeight=FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.loaded) item { Text("Загрузка…") }
            state.generalError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (state.salesLocked) {
                item {
                    StatusBadge("Набор завершён: состав и выручка заблокированы", isWarning = true)
                }
            }
            item {
                SectionTitle("Основное")
                AppTextField(
                    state.counterparty, { value -> model.update { it.copy(counterparty = value) } },
                    "Клиент или менеджер", error = state.errors["counterparty"], enabled = !state.salesLocked,
                )
                OutlinedButton(
                    onClick = { showDatePicker = true }, enabled = !state.salesLocked,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Дата заказа: ${state.date.formatDate()}") }
                AppTextField(state.orderRate, { value -> model.update { it.copy(orderRate = value) } },
                    "Курс, ₽/¥", error = state.errors["orderRate"], decimal = true)
                state.groupNumber?.let {
                    Text("Группа №$it · ${if (state.groupState == GroupState.FORMING) "Формируется" else "На расчёте"}")
                } ?: Text("При сохранении заказ попадёт в текущую формируемую группу")
            }
            item { SectionTitle("Товары · ${state.lines.size}") }
            items(state.lines.size) { index ->
                val line=state.lines[index]
                Card(Modifier.fillMaxWidth().clickable { editingLine=index }) {
                    Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(line.name.ifBlank { "Новая позиция · нажмите для заполнения" }, fontWeight=FontWeight.SemiBold)
                        if(line.article.isNotBlank()) Text(line.article, style=MaterialTheme.typography.bodySmall)
                        Text("${line.quantity} × ${line.salePrice.ifBlank { "—" }} ₽ · цена для Дениса")
                        Text(if(line.purchasePrice.isBlank()) "Указать закупку и доставку →" else "Закупка ${line.purchasePrice} ${line.purchaseCurrency.name} · изменить →",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                        if(state.errors.keys.any { it.startsWith("line_${index}_") }) Text("Проверьте поля позиции",color=MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!state.salesLocked) {
                item { OutlinedButton(onClick = { model.addLine(); editingLine=state.lines.size }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Добавить позицию") } }
            }
            item {
                SectionTitle("Стоимость заказа для Дениса")
                Text("Общая цена для Дениса: ${model.preview()?.sales?.format() ?: "не рассчитана"}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        AppTextField(state.manualDeliveryPrice, { value -> model.update { it.copy(manualDeliveryPrice = value) } },
                            "Цена доставки заказа, ₽", error = state.errors["manualDeliveryPrice"], decimal = true)
                    }
                    Box(Modifier.weight(1f)) {
                        Text("Расчётная доставка:\n${model.deliveryPreview()?.format() ?: "нет данных"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Расчётная доставка только для ориентира. Цена доставки вводится вручную.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                SectionTitle("Закупка всего заказа")
                AppTextField(state.purchaseTotal, { value -> model.update { it.copy(purchaseTotal = value) } },
                    "Общая закупочная стоимость заказа, ₽", error = state.errors["purchaseTotal"], decimal = true)
                Text("Закупочные цены отдельных позиций справочные. Дополнительные расходы учитываются только в комментарии.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                AppTextField(state.comment, { value -> model.update { it.copy(comment = value) } }, "Комментарий", minLines = 2)
            }
            model.preview()?.let { calculation ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle("Расчёт товара")
                            Text("Общая цена для Дениса: ${calculation.sales.format()}")
                            val delivery=model.deliveryPreview()
                            Text("Ориентир доставки: ${delivery?.format() ?: "не заполнен"}")
                            Text("Цена доставки заказа: ${state.manualDeliveryPrice.ifBlank { "не указана" }} ₽")
                            Text("Закупка и доставка: ${if (calculation.isComplete) calculation.knownCost.format() else "неполные данные"}")
                            Text(
                                "Предварительная прибыль: " +
                                    (if (calculation.isComplete) calculation.preliminaryProfitWithoutGroupCosts.format() else "не рассчитана"),
                                fontWeight = FontWeight.SemiBold,
                            )
                            StatusBadge(
                                if (calculation.isComplete) "Все затраты указаны; расчёт предварительный"
                                else "Расчёт неполный — неизвестное не считается нулём",
                                isWarning = !calculation.isComplete,
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick={ state.orderId?.let(onDelivery) }, enabled=state.orderId!=null && !state.dirty, modifier=Modifier.fillMaxWidth()) { Text("Доставка · счёт и оплаты") }
                if(state.orderId==null || state.dirty) Text("Сохраните заказ перед учётом фактической доставки",style=MaterialTheme.typography.bodySmall)
            }
            if (state.history.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = { historyExpanded = !historyExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (historyExpanded) "Скрыть историю изменений" else "История изменений (${state.history.size})")
                    }
                    if (historyExpanded) {
                        state.history.forEach { event ->
                            Text("${event.eventAt.formatDateTime()} · ${event.changeType}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
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
                            if (!state.salesLocked && state.lines.size > 1) {
                                TextButton(onClick = { model.removeLine(index); editingLine=null }) { Text("Удалить") }
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
                                            it.copy(purchaseCurrency = currency, purchaseRate = if (currency == Currency.RUB) "1" else "")
                                        }
                                    },
                                    label = { Text(currency.name) },
                                )
                            }
                        }
                        SectionTitle("Ориентир доставки · отдельно от товара")
                        AppTextField(line.deliveryWeight, { value -> model.updateLine(index) { it.copy(deliveryWeight=value,deliveryPlanDate=LocalDate.now()) } }, "Вес одной единицы, кг", decimal=true)
                        AppTextField(line.deliveryTariff, { value -> model.updateLine(index) { it.copy(deliveryTariff=value,deliveryPlanDate=LocalDate.now()) } }, "Тариф, CNY/кг", decimal=true)
                        Text("Для доставки используется курс заказа.",style=MaterialTheme.typography.bodySmall)
                        state.errors["line_${index}_delivery"]?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        Text("Счёт логиста и оплаты — в отдельной карточке доставки после сохранения заказа.",style=MaterialTheme.typography.bodySmall)

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

    if (showDatePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        model.update { it.copy(date = date) }
                    }
                    showDatePicker = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Отмена") } },
        ) { DatePicker(state = picker) }
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
