package ru.liftzip.orders.app.feature.prices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import ru.liftzip.orders.app.ui.CompactTopBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.ProductDetailViewModel
import ru.liftzip.orders.app.ui.SectionTitle
import ru.liftzip.orders.app.ui.StatusBadge
import ru.liftzip.orders.app.ui.formatDateTime
import ru.liftzip.orders.app.ui.formatDecimal
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.PriceEventType
import ru.liftzip.orders.domain.model.PriceRecordView
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    repository: OrdersRepository,
    productId: UUID,
    onBack: () -> Unit,
    onOpenOrder: (UUID) -> Unit,
) {
    val model = viewModel<ProductDetailViewModel>(
        key = "product-$productId",
        factory = viewModelFactory { initializer { ProductDetailViewModel(repository, productId) } },
    )
    val history by model.history.collectAsState()
    Scaffold(
        topBar = {
            CompactTopBar("История цен", onBack)
        },
    ) { padding ->
        val current = history
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (current == null) item { Text("Загрузка…") }
            else {
                item {
                    Text(current.product.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    current.product.article?.let { Text("Артикул: $it") }
                    SectionTitle("Последние известные цены")
                    Text("Продажа: ${current.latestSale?.priceText() ?: "нет данных"}")
                    Text("Закупка: ${current.latestPurchase?.priceText() ?: "нет данных"}")
                    current.latestPurchase?.let {
                        Text("Дата: ${it.record.eventAt.formatDateTime()} · ${it.record.currency}")
                    }
                }
                item { SectionTitle("Записи из заказов") }
                if (current.records.isEmpty()) item { EmptyState("Записей пока нет") }
                items(current.records.size) { index ->
                    val item = current.records[index]
                    Card(Modifier.fillMaxWidth().clickable { onOpenOrder(item.record.orderId) }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            StatusBadge(
                                if (item.record.type == PriceEventType.SALE) "Продажа"
                                else "Закупка",
                                isWarning = item.record.preliminary,
                            )
                            Text(item.priceText(), fontWeight = FontWeight.SemiBold)
                            Text("Количество: ${item.record.quantity.formatDecimal()}", style = MaterialTheme.typography.bodySmall)
                            Text("Заказ №${item.orderNumber} · ${item.record.eventAt.formatDateTime()}", style = MaterialTheme.typography.bodySmall)
                            if (item.record.type == PriceEventType.PURCHASE && item.record.currency == Currency.CNY) {
                                Text("Сохранённый курс: ${item.record.rate.formatDecimal()} RUB/CNY", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PriceRecordView.priceText(): String =
    "${record.price.formatDecimal()} ${if (record.currency == Currency.RUB) "₽" else "¥"}"
