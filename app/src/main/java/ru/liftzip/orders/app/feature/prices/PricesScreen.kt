package ru.liftzip.orders.app.feature.prices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppTextField(query, model::search, "Поиск по названию, артикулу и вариантам")
            Text("Записи появляются из сохранённых заказов; предложения без заказа появятся в версии 0.4", style = MaterialTheme.typography.bodySmall)
        }
        if (products.isEmpty()) item { EmptyState("Товары появятся после сохранения заказа") }
        items(products, key = { it.id }) { product -> ProductCard(product) { onOpenProduct(product.id) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductCard(product: Product, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            product.article?.let { Text("Артикул: $it") }
            product.brand?.takeIf { it.isNotBlank() }?.let { Text("Бренд: $it") }
            if (product.aliases.isNotEmpty()) Text("Варианты: ${product.aliases.joinToString()}")
        }
    }
}
