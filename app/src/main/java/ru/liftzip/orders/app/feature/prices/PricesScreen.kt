package ru.liftzip.orders.app.feature.prices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.liftzip.orders.app.ui.AppTextField
import ru.liftzip.orders.app.ui.EmptyState
import ru.liftzip.orders.app.ui.PriceSearchViewModel
import ru.liftzip.orders.domain.model.Product
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.util.UUID

@Composable
fun PricesScreen(repository: OrdersRepository, onOpenProduct: (UUID) -> Unit) {
    val model = viewModel<PriceSearchViewModel>(factory = viewModelFactory { initializer { PriceSearchViewModel(repository) } })
    val products by model.products.collectAsState()
    val query by model.currentQuery.collectAsState()
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ru.liftzip.orders.app.ui.CompactSearchField(query, model::search, "Поиск товара", "Поиск по названию, артикулу и вариантам", "prices-search")
        }
        if (products.isEmpty()) item { EmptyState("Товары появятся после сохранения заказа") }
        items(products, key = { it.id }) { product -> ProductCard(product) { onOpenProduct(product.id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductCard(product: Product, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, Color(0xFFD8E1E5))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            product.article?.let { Text("Артикул: $it", style = MaterialTheme.typography.bodySmall) }
            product.brand?.takeIf { it.isNotBlank() }?.let { Text("Бренд: $it", style = MaterialTheme.typography.bodySmall) }
            if (product.aliases.isNotEmpty()) Text("Варианты: ${product.aliases.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
