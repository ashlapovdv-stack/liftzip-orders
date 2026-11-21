package ru.liftzip.orders.app

import ru.liftzip.orders.app.ui.rememberLoaded
import android.os.Bundle
import ru.liftzip.orders.app.feature.settings.DriveArchive
import ru.liftzip.orders.app.feature.settings.RecoveryCodeDialog
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.liftzip.orders.data.backup.BackupManager
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.ui.OrdersApp
import ru.liftzip.orders.app.ui.OrdersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OrdersApplication
        setContent {
            OrdersTheme {
                var container by remember { mutableStateOf<AppContainer?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                suspend fun open(load: suspend () -> AppContainer) {
                    error = null
                    try {
                        container = load()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        error = failure.message ?: "Не удалось открыть данные заказов"
                    }
                }
                LaunchedEffect(app) { open(app::openOrders) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch { open { app.restoreOrders(uri) } }
                }
                var encryptedBackup by remember { mutableStateOf<Uri?>(null) }
                val encryptedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    encryptedBackup = uri
                }
                encryptedBackup?.let { uri ->
                    RecoveryCodeDialog(
                        onCancel = { encryptedBackup = null },
                        onConfirm = { code ->
                            encryptedBackup = null
                            scope.launch {
                                open {
                                    var archive: java.io.File? = null
                                    try {
                                        withContext(Dispatchers.IO) { archive = DriveArchive.decrypt(app, uri, code) }
                                        app.restoreOrders(Uri.fromFile(checkNotNull(archive)))
                                    } finally { archive?.delete() }
                                }
                            }
                        },
                    )
                }
                val opened = container
                if (opened != null) OrdersApp(opened)
                else Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val failure = error
                    if (failure == null) CircularProgressIndicator() else {
                        Text("Не удалось открыть базу заказов")
                        Text(failure)
                        Text("Исходная база сохранена. Восстановление создаст отдельную базу из выбранной копии.")
                        OutlinedButton(onClick = { scope.launch { open(app::openOrders) } }) { Text("Повторить") }
                        val local by rememberLoaded<java.io.File?>(null, failure) {
                            withContext(Dispatchers.IO) { BackupManager.latestRecoveryCopy(app.filesDir) }
                        }
                        local?.let { copy ->
                            OutlinedButton(onClick = { scope.launch { open { app.restoreOrders(Uri.fromFile(copy)) } } }) {
                                Text("Восстановить последнюю локальную копию")
                            }
                        }
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                            Text("Выбрать резервную копию")
                        }
                        OutlinedButton(onClick = {
                            encryptedPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                        }) { Text("Выбрать зашифрованную копию") }
                    }
                }
            }
        }
    }
}
