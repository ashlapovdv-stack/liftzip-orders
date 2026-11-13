package ru.liftzip.orders.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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
import ru.liftzip.orders.app.feature.mail.GroupDraftScreen
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainModel = viewModel<MainViewModel>(factory = viewModelFactory { initializer { MainViewModel(container.settings) } })
    val selectedTab by mainModel.selectedTab.collectAsState()
    val currentEntry by nav.currentBackStackEntryAsState()
    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mainModel.selectTab(0) }
    BackHandler {
        when {
            currentEntry?.destination?.route != "main" -> {
                nav.popBackStack("main", false)
                mainModel.selectTab(0)
            }
            selectedTab != 0 -> mainModel.selectTab(0)
            else -> confirmExit = true
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Выйти из приложения?") },
            confirmButton = { TextButton(onClick = {
                confirmExit = false
                (context as? android.app.Activity)?.finish()
            }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Отмена") } },
        )
    }

    NavHost(navController = nav, startDestination = "main", modifier = Modifier.fillMaxSize()) {
        composable("main") {
            MainScreenScaffold(selectedTab = selectedTab, onSelectTab = { mainModel.selectTab(it) },
                onSettings = { nav.navigate("settings") }) {
                when (selectedTab.coerceIn(0, 2)) {
                    0 -> OrdersScreen(container.repository,
                        onOpenOrder = { nav.navigate("order/$it") },
                        onNewOrder = { nav.navigate("order/new") },
                        onOpenGroup = { nav.navigate("group/$it") })
                    1 -> GroupsScreen(container.repository) { nav.navigate("group/$it") }
                    else -> PricesScreen(container.repository) { nav.navigate("product/$it") }
                }
            }
        }
        composable("order/new") {
            OrderEditorScreen(container.repository, container.settings, null, onBack = { nav.popBackStack() })
        }
        composable(
            "order/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            OrderEditorScreen(container.repository, container.settings, id, onBack = { nav.popBackStack() })
        }
        composable(
            "group/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            GroupDetailScreen(
                container.repository, id, onBack = { nav.popBackStack() },
                onOpenOrder = { nav.navigate("order/$it") },
                onOpenDrafts = { nav.navigate("drafts/$id") },
            )
        }
        composable("drafts/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            GroupDraftScreen(container.repository, id, onBack = { nav.popBackStack() })
        }
        composable(
            "product/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = UUID.fromString(entry.arguments?.getString("id"))
            ProductDetailScreen(
                container.repository, id, onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(container.backup, container.settings, onBack = { nav.popBackStack() })
        }
    }
}

/** Compact chrome shared by the three main tabs, including system bar insets. */
@Composable
internal fun MainScreenScaffold(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(modifier = Modifier.navigationBarsPadding(), containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tabTitles[selectedTab.coerceIn(0, 2)], Modifier.weight(1f).testTag("main-title"),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
                        fontWeight = FontWeight.Medium)
                    IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Settings, "Настройки и резервные копии", Modifier.size(22.dp))
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = Color.White) {
                Column(Modifier.testTag("main-bottom-bar")) {
                    HorizontalDivider(color = Color(0xFFE1E8E5))
                    Row(Modifier.fillMaxWidth().height(44.dp)) {
                        tabTitles.forEachIndexed { index, title ->
                            val selected = selectedTab == index
                            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(Modifier.weight(1f).fillMaxHeight().testTag("main-tab-$index")
                                .selectable(selected, role = Role.Tab, onClick = { onSelectTab(index) }),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Box(Modifier.size(36.dp, 24.dp).background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(tabIcons[index], null, Modifier.size(20.dp), tint = color)
                                }
                                Text(title, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = color)
                            }
                        }
                    }
                }
            }
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
}
