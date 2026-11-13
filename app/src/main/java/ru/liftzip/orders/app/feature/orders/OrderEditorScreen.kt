package ru.liftzip.orders.app.feature.orders

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AddPhotoAlternate
import ru.liftzip.orders.app.SettingsStore
import androidx.compose.material3.Surface
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.foundation.layout.Spacer
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
    settings: SettingsStore,
    orderId: UUID?,
    onBack: () -> Unit,
    onOpenDraft: (UUID, UUID) -> Unit = { _, _ -> },
) {
    val model = viewModel<OrderEditorViewModel>(
        key = "order-${orderId ?: "new"}",
        factory = viewModelFactory { initializer { OrderEditorViewModel(repository, orderId) } },
    )
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val galleryButtonFixed by settings.galleryButtonFixed.collectAsState(initial = true)
    var confirmDeletePhoto by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var imageTarget by remember { mutableStateOf<Int?>(null) }
    var enlargedPhoto by remember { mutableStateOf<ByteArray?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val target = imageTarget
        if (target != null && uris.isNotEmpty()) {
            model.addImagesFromGallery(target, uris, context.contentResolver)
        }
    }

    val linkScope = rememberCoroutineScope()
    var resolvingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var editingLine by remember { mutableStateOf<Int?>(null) }
    var catalogLine by remember { mutableStateOf<Int?>(null) }
    var historyExpanded by remember { mutableStateOf(false) }
    var confirmDeleteOrder by remember { mutableStateOf(false) }
    var deleteLineIndex by remember { mutableStateOf<Int?>(null) }

    BackHandler(enabled = enlargedPhoto == null) {
        when {
            confirmDeletePhoto != null -> confirmDeletePhoto = null
            editingLine != null -> editingLine = null
            else -> if (model.requestBack()) onBack()
        }
    }

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
            if (orderId != null) {
                item {
                    Button(
                        onClick = { state.groupId?.let { onOpenDraft(it, orderId) } },
                        enabled = state.loaded && !state.saving && !state.actionsSaving &&
                            !state.dirty && state.groupId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сформировать письмо / Открыть черновик") }
                    if (state.dirty) {
                        Text("Сначала сохраните изменения заказа",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
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
                        if (line.images.isNotEmpty()) {
                            Icon(Icons.Default.Image, "К товару прикреплены фотографии", Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { deleteLineIndex = index }, enabled = !state.saving, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Delete, "Удалить позицию ${index + 1}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (!state.salesLocked) item {
                OutlinedButton(onClick = { model.addLine(); editingLine = 0 }, modifier = Modifier.fillMaxWidth().height(40.dp)) { Text("Добавить позицию") }
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

    confirmDeletePhoto?.let { (lineIndex, photoIndex) ->
        AlertDialog(
            onDismissRequest = { confirmDeletePhoto = null },
            title = { Text("Удалить фотографию?") },
            text = { Text("Фотография будет удалена из позиции после сохранения заказа.") },
            confirmButton = { TextButton(onClick = {
                model.updateLine(lineIndex) { draft ->
                    draft.copy(images = draft.images.filterIndexed { n, _ -> n != photoIndex })
                }
                confirmDeletePhoto = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDeletePhoto = null }) { Text("Отмена") } },
        )
    }

    editingLine?.takeIf { it in state.lines.indices }?.let { index ->
        Dialog(
            onDismissRequest = { editingLine = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter) {
                Surface(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.96f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFFF6F8F8),
                ) {
            val line = state.lines[index]
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("position-editor")) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Позиция ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { editingLine = null }) { Text("Готово") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactItemSection {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Товар", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { catalogLine = index }, enabled = !state.salesLocked) { Text("Из каталога") }
                        }
                        CompactPositionField(line.name, { value -> model.updateLine(index) { it.copy(name = value, productId = null) } },
                            "Название товара", error = state.errors["line_${index}_name"], enabled = !state.salesLocked)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (galleryButtonFixed) {
                                GalleryAddTile { imageTarget = index; gallery.launch("image/*") }
                            }
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (!galleryButtonFixed) {
                                    GalleryAddTile { imageTarget = index; gallery.launch("image/*") }
                                }
                            line.images.forEachIndexed { photoIndex, bytes ->
                                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                Box(Modifier.size(76.dp)) {
                                    if (bitmap != null) Image(
                                        bitmap.asImageBitmap(), contentDescription = "Фото ${photoIndex + 1}",
                                        modifier = Modifier.fillMaxSize().clickable { enlargedPhoto = bytes }, contentScale = ContentScale.Crop,
                                    )
                                    IconButton(
                                        onClick = { confirmDeletePhoto = index to photoIndex },
                                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                                            .background(Color.Transparent),
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, "Удалить фото ${photoIndex + 1}",
                                            modifier = Modifier.size(17.dp), tint = Color(0xFFB71C1C))
                                    }
                                }
                            }
                            }
                        }
                        CompactPositionField(line.article, { value -> model.updateLine(index) { it.copy(article = value, productId = null) } },
                            "ZIP-код", enabled = !state.salesLocked)
                        CompactPositionField(line.productUrl, { value ->
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
                            CompactPositionField(line.quantity, { value -> model.updateLine(index) { it.copy(quantity = value) } },
                                "Количество", Modifier.weight(0.8f), error = state.errors["line_${index}_quantity"], enabled = !state.salesLocked, decimal = true)
                            CompactPositionField(line.salePrice, { value -> model.updateLine(index) { it.copy(salePrice = value) } },
                                "Денису, ₽/шт", Modifier.weight(1.2f), error = state.errors["line_${index}_sale"], enabled = !state.salesLocked, decimal = true)
                        }

                        state.errors["line_${index}_zero"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (line.salePrice.replace(',', '.').toBigDecimalOrNull()?.signum() == 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = line.zeroSalePriceConfirmed,
                                    onCheckedChange = { checked -> model.updateLine(index) { it.copy(zeroSalePriceConfirmed = checked) } }, enabled = !state.salesLocked)
                                Text("Подтверждаю нулевую цену", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    CompactItemSection {
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
                        CompactPositionField(line.purchasePrice, { value -> model.updateLine(index) { it.copy(purchasePrice = value) } },
                            "Цена за единицу, ${if (line.purchaseCurrency == Currency.RUB) "₽" else "¥"}", error = state.errors["line_${index}_purchase"], decimal = true)
                    }
                    CompactItemSection {
                        Text("Доставка · ориентир", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CompactPositionField(line.deliveryWeight, { value -> model.updateLine(index) { it.copy(deliveryWeight = value, deliveryPlanDate = LocalDate.now()) } },
                                "Вес, кг", Modifier.weight(1f), decimal = true)
                            CompactPositionField(line.deliveryTariff, { value -> model.updateLine(index) { it.copy(deliveryTariff = value, deliveryPlanDate = LocalDate.now()) } },
                                "Тариф, ¥/кг", Modifier.weight(1f), decimal = true)
                            CompactPositionField(line.actualWeight, { value -> model.updateLine(index) { it.copy(actualWeight = value) } },
                                "Факт, кг", Modifier.weight(1f), decimal = true)
                        }
                        state.errors["line_${index}_delivery"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }

                }

            }
                }
            }
        }
    }

    enlargedPhoto?.let { bytes ->
        Dialog(onDismissRequest = { enlargedPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xED101010))
                .clickable { enlargedPhoto = null }, contentAlignment = Alignment.Center) {
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Увеличенное фото товара",
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                        contentScale = ContentScale.Fit)
                }
                TextButton(onClick = { enlargedPhoto = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Text("Закрыть", color = Color.White)
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

@Composable
private fun CompactItemSection(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

@Composable
private fun GalleryAddTile(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.size(76.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Добавить фото из галереи",
                modifier = Modifier.size(28.dp))
            Text("Добавить", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CompactPositionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    error: String? = null,
    enabled: Boolean = true,
    decimal: Boolean = false,
    minLines: Int = 1,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(9.dp), color = Color.White,
            border = BorderStroke(1.dp, if (error != null) MaterialTheme.colorScheme.error else Color(0xFF888888))) {
            BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled,
                singleLine = minLines <= 1,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = if (decimal) KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                  else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                })
        }
        if (error != null) Text(error, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
}
