package ru.liftzip.orders.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.liftzip.orders.app.AppContainer
import ru.liftzip.orders.app.feature.groups.GroupDetailScreen
import ru.liftzip.orders.app.feature.groups.GroupsScreen
import ru.liftzip.orders.app.feature.orders.OrderEditorScreen
import ru.liftzip.orders.app.feature.orders.OrdersScreen
import ru.liftzip.orders.app.feature.prices.PricesScreen
import ru.liftzip.orders.app.feature.prices.ProductDetailScreen
import ru.liftzip.orders.app.feature.settings.SettingsScreen
import java.util.UUID

private val tabTitles = listOf("Заказы", "Группы", "История цен")
private val tabIcons = listOf(Icons.Default.ShoppingCart, Icons.Default.List, Icons.Default.DateRange)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersApp(container: AppContainer) {
    val nav = rememberNavController()
    val mainModel = viewModel<MainViewModel>(factory = viewModelFactory { initializer { MainViewModel(container.settings) } })
    val selectedTab by mainModel.selectedTab.collectAsState()

    NavHost(navController = nav, startDestination = "main", modifier = Modifier.fillMaxSize()) {
        composable("main") {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(tabTitles[selectedTab.coerceIn(0, 2)]) },
                        actions = {
                            IconButton(onClick = { nav.navigate("settings") }) {
                                Icon(Icons.Default.Settings, contentDescription = "Настройки и резервные копии")
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        tabTitles.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { mainModel.selectTab(index) },
                                icon = { Icon(tabIcons[index], contentDescription = title) },
                                label = { Text(title) },
                            )
                        }
                    }
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
                    when (selectedTab.coerceIn(0, 2)) {
                        0 -> OrdersScreen(
                            container.repository,
                            onOpenOrder = { nav.navigate("order/$it") },
                            onNewOrder = { nav.navigate("order/new") },
                        )
                        1 -> GroupsScreen(container.repository) { nav.navigate("group/$it") }
                        else -> PricesScreen(container.repository) { nav.navigate("product/$it") }
                    }
                }
            }
        }
        composable("order/new") {
            OrderEditorScreen(container.repository, null, onBack = { nav.popBackStack() })
        }
        composable(
            "order/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            OrderEditorScreen(container.repository, id, onBack = { nav.popBackStack() })
        }
        composable(
            "group/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            GroupDetailScreen(
                container.repository, id, onBack = { nav.popBackStack() },
                onOpenOrder = { nav.navigate("order/$it") },
            )
        }
        composable(
            "product/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            ProductDetailScreen(
                container.repository, id, onBack = { nav.popBackStack() },
                onOpenOrder = { nav.navigate("order/$it") },
            )
        }
        composable("settings") {
            SettingsScreen(container.backup, container.settings, onBack = { nav.popBackStack() })
        }
    }
}
