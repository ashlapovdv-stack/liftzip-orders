package ru.liftzip.orders.app.feature.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.liftzip.orders.app.OrdersApplication
import ru.liftzip.orders.data.backup.BackupManager
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LocalBackupSettings(
    val folder: Uri? = null,
    val folderName: String? = null,
    val lastSuccess: Long? = null,
    val error: String? = null,
)

internal object DailyBackupSchedule {
    private const val DAILY = "orders-local-backup"
    internal const val NOW = "orders-local-backup-now"
    internal const val FORCE = "force"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyBackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS).build(),
        )
    }

    fun runNow(context: Context, replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyBackupWorker>()
                .setInputData(workDataOf(FORCE to true)).build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).also {
            it.cancelUniqueWork(DAILY)
            it.cancelUniqueWork(NOW)
        }
    }
}

class DailyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        backupLock.withLock {
            val app = applicationContext as OrdersApplication
            val config = app.settings.localBackup.first()
            val uri = config.folder ?: return@withLock Result.success()
            if (!inputData.getBoolean(DailyBackupSchedule.FORCE, false) &&
                config.lastSuccess?.let { System.currentTimeMillis() - it in 0 until TimeUnit.HOURS.toMillis(20) } == true
            ) return@withLock Result.success()

            try {
                val folder = DocumentFile.fromTreeUri(app, uri)
                    ?: throw SecurityException("Папка недоступна")
                if (!folder.isDirectory || !folder.canWrite()) throw SecurityException("Папка недоступна")
                val archive = writeDailyBackup(app.openOrders().backup, folder)
                app.settings.recordDailyBackup(uri, archive.createdAt, if (archive.keptSeven) null else
                    "Копия сохранена, но некоторые старые файлы не удалось удалить")
                try {
                    if (DriveBackupStore(app).settings.first().email != null) DriveBackupSchedule.enqueue(app)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The verified local copy remains successful if the upload cannot be scheduled.
                }
                Result.success()
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                app.settings.recordDailyBackup(uri, null, "Нет доступа к папке. Выберите её заново.")
                Result.failure()
            } catch (_: Exception) {
                app.settings.recordDailyBackup(uri, null, "Не удалось сохранить копию. Проверьте папку и свободное место.")
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        }
    }

    private companion object {
        val backupLock = Mutex()
    }
}

private const val prefix = "Заказы-авто-"
private val dateInName = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC)
private val automaticName = Regex("""$prefix\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}Z-[0-9a-f]{8}\.zip""")
private val pendingName = Regex("""${prefix}временный-[0-9a-f-]{36}(?:\.bin)?""")

internal data class CompletedDailyBackup(val createdAt: Long, val keptSeven: Boolean)

internal suspend fun writeDailyBackup(backup: BackupManager, folder: DocumentFile): CompletedDailyBackup {
    val token = UUID.randomUUID().toString()
    val pending = folder.createFile("application/octet-stream", "${prefix}временный-$token")
        ?: throw IOException("Не удалось создать файл")
    val inspection = try {
        backup.exportTo(pending.uri)
        val checked = backup.inspect(pending.uri)
        val name = "$prefix${dateInName.format(Instant.ofEpochMilli(checked.createdAt))}Z-${token.take(8)}.zip"
        if (!pending.renameTo(name)) throw IOException("Не удалось завершить запись архива")
        checked
    } catch (error: Exception) {
        runCatching { pending.delete() }
        throw error
    }

    val keptSeven = runCatching {
        val files = folder.listFiles().mapNotNull { document ->
            document.name?.takeIf { automaticName.matches(it) || pendingName.matches(it) }
                ?.takeIf { document.isFile }?.let { it to document }
        }
        val backups = files.filter { (name, _) -> automaticName.matches(name) }
            .sortedByDescending { (name, _) -> name }
        val expired = backups.drop(7) + files.filter { (name, _) -> pendingName.matches(name) }
        expired.map { (_, document) -> document.delete() }.all { it }
    }.getOrDefault(false)
    return CompletedDailyBackup(inspection.createdAt, keptSeven)
}

internal fun latestDailyBackup(folder: DocumentFile?): DocumentFile? =
    folder?.listFiles()?.mapNotNull { file ->
        file.name?.takeIf { automaticName.matches(it) && file.isFile }?.let { it to file }
    }?.maxByOrNull { it.first }?.second
