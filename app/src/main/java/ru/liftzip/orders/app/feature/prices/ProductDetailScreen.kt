package ru.liftzip.orders.app.feature.prices

import android.graphics.BitmapFactory
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.CompactTopBar
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.ProductDetailViewModel
import ru.liftzip.orders.app.ui.formatDateTime
import ru.liftzip.orders.app.ui.formatDecimal
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.PriceRecordView
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ProductDetailScreen(repository: OrdersRepository, productId: UUID, onBack: () -> Unit) {
    val model = viewModel<ProductDetailViewModel>(
        key = "product-$productId",
        factory = viewModelFactory { initializer { ProductDetailViewModel(repository, productId) } },
    )
    val history by model.history.collectAsState()
    val context = LocalContext.current
    var enlarged by remember { mutableStateOf<ByteArray?>(null) }
    var savePhoto by remember { mutableStateOf<ByteArray?>(null) }
    var linkActions by remember { mutableStateOf<String?>(null) }
    enlarged?.let { bytes ->
        Dialog(onDismissRequest = { enlarged = null }) {
            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bitmap != null) Image(bitmap.asImageBitmap(), "Увеличенная фотография",
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = {}, onLongClick = { savePhoto = bytes }),
                contentScale = ContentScale.Fit)
        }
    }
    savePhoto?.let { bytes ->
        AlertDialog(onDismissRequest = { savePhoto = null },
            title = { Text("Сохранить фотографию?") },
            confirmButton = { TextButton(onClick = {
                val saved = saveHistoryPhoto(context, bytes)
                Toast.makeText(context, if (saved) "Фотография сохранена" else "Не удалось сохранить", Toast.LENGTH_SHORT).show()
                savePhoto = null
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { savePhoto = null }) { Text("Отмена") } })
    }
    linkActions?.let { url ->
        AlertDialog(onDismissRequest = { linkActions = null },
            title = { Text("Ссылка товара") }, text = { Text(url) },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
                linkActions = null
            }) { Text("Открыть") } },
            dismissButton = { TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Ссылка товара", url))
                linkActions = null
            }) { Text("Копировать") } })
    }
    Scaffold(topBar = { CompactTopBar("История цен", onBack) }) { padding ->
        val current = history
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current == null) item { Text("Загрузка…") }
            else {
                item {
                    Text(current.product.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    current.product.article?.let { Text("Артикул: $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (current.lines.isEmpty()) item { EmptyState("Записей пока нет") }
                items(current.lines, key = { it.sourceLineId }) { entry ->
                    val source = entry.source
                    Card(Modifier.fillMaxWidth().testTag("history-line-${entry.sourceLineId}"),
                        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Заказ №${source.orderNumber} · ${source.record.eventAt.formatDateTime()}",
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HistoryValue("Денису за единицу", entry.sale?.priceText() ?: "—", Modifier.weight(1f))
                                HistoryValue("Закупка за единицу", entry.purchase?.priceText() ?: "—", Modifier.weight(1f))
                            }
                            Text("Количество: ${source.record.quantity.formatDecimal()}", style = MaterialTheme.typography.bodySmall)
                            Text("Вес единицы: ${source.unitWeight?.let { "${it.formatDecimal()} кг" } ?: "не указан"}", style = MaterialTheme.typography.bodySmall)
                            source.actualWeight?.let { Text("Реальный вес: ${it.formatDecimal()} кг", style = MaterialTheme.typography.bodySmall) }
                            source.productUrl?.let { url ->
                                Text("Ссылка: $url", modifier = Modifier.combinedClickable(
                                    onClick = {}, onLongClick = { linkActions = url }),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            val rate = source.orderRate
                            if (rate != null) Text("Курс заказа: ${rate.formatDecimal()} ₽/¥", style = MaterialTheme.typography.bodySmall)
                            else entry.purchase?.takeIf { it.record.currency == Currency.CNY }?.let {
                                Text("Курс закупки: ${it.record.rate.formatDecimal()} ₽/¥", style = MaterialTheme.typography.bodySmall)
                            }
                            source.deliveryTariff?.let { Text("Тариф доставки: ${it.formatDecimal()} ¥/кг", style = MaterialTheme.typography.bodySmall) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                source.images.forEachIndexed { index, bytes ->
                                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                    if (bitmap != null) Image(bitmap.asImageBitmap(), "Фото ${index + 1}",
                                        modifier = Modifier.size(76.dp).combinedClickable(
                                            onClick = { enlarged = bytes },
                                            onLongClick = { savePhoto = bytes }),
                                        contentScale = ContentScale.Crop)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun PriceRecordView.priceText(): String =
    "${record.price.formatDecimal()} ${if (record.currency == Currency.RUB) "₽" else "¥"}"

private fun saveHistoryPhoto(context: Context, bytes: ByteArray): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "LiftZIP_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LiftZIP")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Cannot create image")
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write image")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
    true
}.getOrDefault(false)
