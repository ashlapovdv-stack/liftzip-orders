package ru.liftzip.orders.app.feature.prices

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

@Composable
fun ProductDetailScreen(repository: OrdersRepository, productId: UUID, onBack: () -> Unit) {
    val model = viewModel<ProductDetailViewModel>(
        key = "product-$productId",
        factory = viewModelFactory { initializer { ProductDetailViewModel(repository, productId) } },
    )
    val history by model.history.collectAsState()
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
                            source.productUrl?.let { Text("Ссылка: $it", style = MaterialTheme.typography.bodySmall) }
                            source.images.forEachIndexed { index, bytes ->
                                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                if (bitmap != null) Image(bitmap.asImageBitmap(), "Фото ${index + 1}",
                                    modifier = Modifier.size(112.dp), contentScale = ContentScale.Crop)
                            }
                            val rate = source.orderRate
                            if (rate != null) Text("Курс заказа: ${rate.formatDecimal()} ₽/¥", style = MaterialTheme.typography.bodySmall)
                            else entry.purchase?.takeIf { it.record.currency == Currency.CNY }?.let {
                                Text("Курс закупки: ${it.record.rate.formatDecimal()} ₽/¥", style = MaterialTheme.typography.bodySmall)
                            }
                            source.deliveryTariff?.let { Text("Тариф доставки: ${it.formatDecimal()} ¥/кг", style = MaterialTheme.typography.bodySmall) }
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
