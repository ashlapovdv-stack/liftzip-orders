package ru.liftzip.orders.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.liftzip.orders.data.backup.BackupException
import ru.liftzip.orders.data.backup.BackupManager
import ru.liftzip.orders.data.backup.BackupManifest
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.repository.RoomOrdersRepository
import ru.liftzip.orders.domain.model.OrderLineDraft
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.model.SaveOrderCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var context: Context
    private lateinit var database: OrdersDatabase
    private lateinit var repository: RoomOrdersRepository
    private lateinit var manager: BackupManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, OrdersDatabase::class.java).build()
        repository = RoomOrdersRepository(database)
        manager = BackupManager(context, database)
        runBlocking { repository.initialize() }
    }

    @After fun tearDown() { database.close() }

    @Test fun exportRestorePreservesUuidAndReplacesNewerState() = runTest {
        val orderId = repository.saveOrder(orderCommand("Первый"))
        val file = File(context.cacheDir, "roundtrip.zip")
        val uri = Uri.fromFile(file)
        manager.exportTo(uri)
        repository.saveOrder(orderCommand("Второй"))
        assertEquals(2, repository.observeOrders("", OrdersFilter.ALL, 50).first().size)

        manager.importFrom(uri)
        val restored = repository.observeOrders("", OrdersFilter.ALL, 50).first()
        assertEquals(1, restored.size)
        assertEquals(orderId, restored.single().order.id)
        assertEquals("Первый", restored.single().order.counterparty)
        assertTrue(File(context.filesDir, "recovery-backups").listFiles()?.isNotEmpty() == true)
    }

    @Test fun corruptAndNewerArchivesLeaveCurrentDataUntouched() = runTest {
        val orderId = repository.saveOrder(orderCommand("Сохранить"))
        val valid = File(context.cacheDir, "valid.zip")
        manager.exportTo(Uri.fromFile(valid))
        val corrupt = File(context.cacheDir, "corrupt.zip").apply { writeText("not a zip") }
        assertRejected(corrupt)
        val newer = newerArchive(valid)
        assertRejected(newer)
        assertEquals(orderId, repository.observeOrders("", OrdersFilter.ALL, 50).first().single().order.id)
    }

    @Test fun exportUsesActualSchemaAndRestoresRateAndFulfillment() = runTest {
        val id = repository.saveOrder(orderCommand("Готовый").copy(orderRate = BigDecimal("13.25"), receivedPayment = BigDecimal.ZERO))
        val group = repository.observeOrder(id).first()!!.group
        repository.finishGroup(ru.liftzip.orders.domain.model.FinishGroupCommand(UUID.randomUUID(), group.id))
        ru.liftzip.orders.domain.model.OrderAction.entries.forEach { repository.setOrderAction(id, it, true) }
        val file = File(context.cacheDir, "fulfillment-roundtrip.zip")
        val inspection = manager.exportTo(Uri.fromFile(file))
        assertEquals(database.openHelper.readableDatabase.version, inspection.schemaVersion)
        repository.setOrderAction(id, ru.liftzip.orders.domain.model.OrderAction.WITHDRAWN, false)
        manager.importFrom(Uri.fromFile(file))
        val restored = repository.observeOrder(id).first()!!
        assertTrue(restored.order.fulfillment.isComplete)
        assertEquals(ru.liftzip.orders.domain.model.GroupState.COMPLETED, restored.group.state)
        assertEquals(0, restored.order.orderRate!!.compareTo(BigDecimal("13.25")))
        assertEquals(0L, restored.order.receivedPayment.minorUnits)
    }

    @Test fun deliveryRoundtripPreservesLedgerAndDoesNotTouchGoods() = runTest {
        val id=repository.saveOrder(orderCommand("Доставка"))
        val day=LocalDate.parse("2026-11-11")
        repository.saveDeliveryInvoice(ru.liftzip.orders.domain.delivery.DeliveryInvoice(id,ru.liftzip.orders.domain.model.Money(443750),day))
        val payment=ru.liftzip.orders.domain.delivery.DeliveryPayment(UUID.randomUUID(),id,ru.liftzip.orders.domain.delivery.DeliveryParty.CLIENT,ru.liftzip.orders.domain.model.Money(200000),day,"test")
        repository.addDeliveryPayment(payment)
        repository.addDeliveryPayment(payment)
        val file=File(context.cacheDir,"delivery-roundtrip.zip")
        manager.exportTo(Uri.fromFile(file))
        repository.reverseDeliveryPayment(payment.id,UUID.randomUUID())
        manager.importFrom(Uri.fromFile(file))
        val details=repository.observeDelivery(id).first()!!
        assertEquals(1,details.payments.size)
        assertEquals(243750L,details.clientBalance!!.minorUnits)
        assertEquals(10000L,repository.observeOrder(id).first()!!.order.receivedPayment.minorUnits)
    }

    private suspend fun assertRejected(file: File) {
        var rejected = false
        try { manager.importFrom(Uri.fromFile(file)) } catch (_: BackupException) { rejected = true }
        assertTrue("Архив должен быть отклонён: ${file.name}", rejected)
    }

    private fun newerArchive(source: File): File {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(source.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                zip.copyTo(output)
                entries[entry.name] = output.toByteArray()
            }
        }
        val json = Json
        val manifest = json.decodeFromString(BackupManifest.serializer(), entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val updated = manifest.copy(schemaVersion = manifest.schemaVersion + 1)
        val target = File(context.cacheDir, "newer.zip")
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(BackupManifest.serializer(), updated).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(entries.getValue("data.json"))
            zip.closeEntry()
        }
        return target
    }

    @Test fun deletedOrderNumberAndAuditSurviveBackupRestore() = runTest {
        val id = repository.saveOrder(orderCommand("Удаляемый"))
        repository.deleteOrder(id)
        val file = File(context.cacheDir, "deleted-order.zip")
        val uri = Uri.fromFile(file)
        manager.exportTo(uri)
        manager.importFrom(uri)
        assertTrue(repository.observeOrders("", OrdersFilter.ALL, 50).first().isEmpty())
        assertTrue(database.ordersDao().getOrderHistory(id.toString()).any { it.changeType == "DELETED" })
        val next = repository.saveOrder(orderCommand("Следующий"))
        assertEquals(2L, repository.observeOrder(next).first()!!.order.number)
    }

    @Test fun formingGroupFulfillmentSurvivesBackupRestore() = runTest {
        val id = repository.saveOrder(orderCommand("Отметки").copy(
            initialFulfillment = ru.liftzip.orders.domain.model.OrderFulfillment(true, true, true)))
        val uri = Uri.fromFile(File(context.cacheDir, "forming-flags.zip"))
        manager.exportTo(uri)
        manager.importFrom(uri)
        val saved = repository.observeOrder(id).first()!!
        assertTrue(saved.order.fulfillment.isComplete)
        assertEquals(ru.liftzip.orders.domain.model.GroupState.FORMING, saved.group.state)
        repository.finishGroup(ru.liftzip.orders.domain.model.FinishGroupCommand(UUID.randomUUID(), saved.group.id))
        assertEquals(ru.liftzip.orders.domain.model.GroupState.COMPLETED, repository.observeOrder(id).first()!!.group.state)
    }

    private fun orderCommand(counterparty: String) = SaveOrderCommand(
        commandId = UUID.randomUUID(), date = LocalDate.parse("2026-11-11"), counterparty = counterparty,
        receivedPayment = BigDecimal("100"), costsConfirmed = false,
        lines = listOf(OrderLineDraft(
            productName = "Товар $counterparty", quantity = BigDecimal.ONE,
            saleUnitPrice = BigDecimal("100"), zeroSalePriceConfirmed = true,
        )), costs = emptyList(),
    )
}
