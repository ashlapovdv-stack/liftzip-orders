package ru.liftzip.orders.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.app.feature.settings.writeDailyBackup
import ru.liftzip.orders.data.backup.BackupManager
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DailyBackupTest {
    @Test fun verifiesNewArchiveBeforeRotatingOnlyAutomaticCopies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "daily-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, OrdersDatabase::class.java).build()
        try {
            RoomOrdersRepository(database).initialize()
            val manager = BackupManager(context, database)
            repeat(7) { index ->
                File(folder, "Заказы-авто-2000-01-0${index + 1}_12-00-00-000Z-12345678.zip").writeText("old")
            }
            val manual = File(folder, "Заказы-резервная-копия.zip").apply { writeText("manual") }
            val interrupted = File(folder, "Заказы-авто-временный-${UUID.randomUUID()}.bin").apply { writeText("partial") }

            val completed = writeDailyBackup(manager, DocumentFile.fromFile(folder))

            val copies = folder.listFiles().orEmpty().filter { it.name.startsWith("Заказы-авто-") }
            assertTrue(completed.keptSeven)
            assertEquals(7, copies.size)
            assertEquals("manual", manual.readText())
            assertFalse(interrupted.exists())
            assertFalse(File(folder, "Заказы-авто-2000-01-01_12-00-00-000Z-12345678.zip").exists())
            val latest = copies.maxBy { it.name }
            assertEquals(completed.createdAt, manager.inspect(Uri.fromFile(latest)).createdAt)
        } finally {
            database.close()
            folder.deleteRecursively()
        }
    }
}
