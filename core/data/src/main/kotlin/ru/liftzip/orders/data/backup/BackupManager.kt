package ru.liftzip.orders.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.database.toDomain
import ru.liftzip.orders.domain.calculation.OrderCalculator
import ru.liftzip.orders.domain.model.CostOwnerType
import ru.liftzip.orders.domain.model.GroupState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val database: OrdersDatabase,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    private val backupDao = database.backupDao()

    suspend fun inspect(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        readAndValidate(uri).second
    }

    suspend fun exportTo(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "w")
            ?: throw BackupException("Не удалось открыть место сохранения")
        stream.use { exportSnapshot(it) }
    }

    suspend fun importFrom(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        val (payload, inspection) = readAndValidate(uri)
        createLocalRecoveryCopy()
        replaceDatabase(payload)
        inspection
    }

    fun latestRecoveryCopy(): File? = File(context.filesDir, "recovery-backups")
        .listFiles { file -> file.name.endsWith(".zip") }
        ?.maxByOrNull { it.lastModified() }

    suspend fun createLocalRecoveryCopy(): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "recovery-backups").apply { mkdirs() }
        val file = File(directory, "before-import-${Instant.now().toEpochMilli()}.zip")
        file.outputStream().buffered().use { exportSnapshot(it) }
        file
    }

    private suspend fun exportSnapshot(output: OutputStream): BackupInspection {
        val payload = database.withTransaction {
            BackupPayload(
                products = backupDao.allProducts(), groups = backupDao.allGroups(), orders = backupDao.allOrders(),
                lines = backupDao.allLines(), costs = backupDao.allCosts(), priceRecords = backupDao.allPriceRecords(),
                changeEvents = backupDao.allChangeEvents(), orderCommands = backupDao.allOrderCommands(),
                groupCommands = backupDao.allGroupCommands(), metadata = backupDao.allMetadata(),
            )
        }
        val data = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        val counts = payload.counts()
        val manifest = BackupManifest(
            format = FORMAT, formatVersion = FORMAT_VERSION, schemaVersion = OrdersDatabase.SCHEMA_VERSION,
            createdAt = Instant.now().toEpochMilli(), dataSha256 = sha256(data), counts = counts,
        )
        val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(data)
            zip.closeEntry()
        }
        return BackupInspection(manifest.createdAt, manifest.schemaVersion, counts)
    }

    private fun readAndValidate(uri: Uri): Pair<BackupPayload, BackupInspection> {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupException("Не удалось открыть резервную копию")
        val entries = input.use { stream -> readEntries(ZipInputStream(stream)) }
        val manifestBytes = entries["manifest.json"] ?: throw BackupException("В архиве нет manifest.json")
        val data = entries["data.json"] ?: throw BackupException("В архиве нет data.json")
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupException("Повреждён manifest.json", error)
        }
        if (manifest.format != FORMAT || manifest.formatVersion != FORMAT_VERSION) {
            throw BackupException("Неподдерживаемый формат резервной копии")
        }
        if (manifest.schemaVersion != OrdersDatabase.SCHEMA_VERSION) {
            val reason = if (manifest.schemaVersion > OrdersDatabase.SCHEMA_VERSION) "более новая" else "другая"
            throw BackupException("Архив содержит $reason версию схемы: ${manifest.schemaVersion}")
        }
        if (!manifest.dataSha256.equals(sha256(data), ignoreCase = true)) {
            throw BackupException("Контрольная сумма данных не совпадает")
        }
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), data.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupException("Повреждены данные резервной копии", error)
        }
        if (payload.counts() != manifest.counts) throw BackupException("Количество записей не совпадает с manifest.json")
        validatePayload(payload)
        return payload to BackupInspection(manifest.createdAt, manifest.schemaVersion, manifest.counts)
    }

    private fun readEntries(zip: ZipInputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            if (entry.name !in setOf("manifest.json", "data.json")) continue
            if (result.containsKey(entry.name)) throw BackupException("Повторяющийся файл в архиве")
            val limit = if (entry.name == "manifest.json") 1_048_576 else 64 * 1024 * 1024
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                if (output.size() + read > limit || total > 66 * 1024 * 1024) {
                    throw BackupException("Архив превышает допустимый размер")
                }
                output.write(buffer, 0, read)
            }
            result[entry.name] = output.toByteArray()
            zip.closeEntry()
        }
        return result
    }

    private suspend fun replaceDatabase(payload: BackupPayload) {
        database.withTransaction {
            backupDao.deleteOrderCommands()
            backupDao.deleteGroupCommands()
            backupDao.deleteChangeEvents()
            backupDao.deletePriceRecords()
            backupDao.deleteCosts()
            backupDao.deleteLines()
            backupDao.deleteOrders()
            backupDao.deleteProducts()
            backupDao.deleteGroups()
            backupDao.deleteMetadata()
            backupDao.insertGroups(payload.groups)
            backupDao.insertProducts(payload.products)
            backupDao.insertOrders(payload.orders)
            backupDao.insertLines(payload.lines)
            backupDao.insertCosts(payload.costs)
            backupDao.insertPriceRecords(payload.priceRecords)
            backupDao.insertChangeEvents(payload.changeEvents)
            backupDao.insertOrderCommands(payload.orderCommands)
            backupDao.insertGroupCommands(payload.groupCommands)
            backupDao.insertMetadata(payload.metadata)
        }
    }

    private fun validatePayload(payload: BackupPayload) {
        try {
            val products = payload.products.uniqueBy { it.id }
            val groups = payload.groups.uniqueBy { it.id }
            val orders = payload.orders.uniqueBy { it.id }
            val lines = payload.lines.uniqueBy { it.id }
            val costs = payload.costs.uniqueBy { it.id }
            val records = payload.priceRecords.uniqueBy { it.id }
            payload.changeEvents.uniqueBy { it.id }
            payload.orderCommands.uniqueBy { it.commandId }
            payload.groupCommands.uniqueBy { it.commandId }
            if (groups.values.count { it.state == GroupState.FORMING.name } != 1) {
                throw BackupException("В копии должна быть ровно одна формируемая группа")
            }
            if (groups.values.map { it.number }.distinct().size != groups.size ||
                orders.values.map { it.number }.distinct().size != orders.size
            ) throw BackupException("Нарушена уникальность номеров")
            payload.products.forEach { it.toDomain() }
            payload.groups.forEach { it.toDomain() }
            payload.orders.forEach { order ->
                if (order.groupId !in groups) throw BackupException("Заказ ссылается на отсутствующую группу")
                if (order.receivedPaymentMinor < 0) throw BackupException("Отрицательная оплата в заказе ${order.number}")
                val orderLines = payload.lines.filter { it.orderId == order.id }
                if (orderLines.isEmpty()) throw BackupException("Заказ ${order.number} не содержит позиций")
                orderLines.forEach { line ->
                    if (line.productId !in products) throw BackupException("Позиция ссылается на отсутствующий товар")
                    val domain = line.toDomain()
                    if (domain.quantity <= java.math.BigDecimal.ZERO || line.saleUnitPriceMinor < 0) {
                        throw BackupException("Некорректное количество или цена продажи")
                    }
                    val purchaseParts = listOfNotNull(line.purchaseUnitPrice, line.purchaseCurrency, line.purchaseRate)
                    if (purchaseParts.isNotEmpty() && purchaseParts.size != 3) {
                        throw BackupException("Неполный набор закупочных полей")
                    }
                    val purchasePrice = domain.purchaseUnitPrice
                    val purchaseRate = domain.purchaseRate
                    if (purchasePrice != null) {
                        if (purchasePrice < java.math.BigDecimal.ZERO ||
                            purchaseRate == null || purchaseRate <= java.math.BigDecimal.ZERO ||
                            (domain.purchaseCurrency == ru.liftzip.orders.domain.model.Currency.RUB &&
                                purchaseRate.compareTo(java.math.BigDecimal.ONE) != 0)
                        ) throw BackupException("Некорректный закупочный расчёт")
                    }
                }
                val domainLines = orderLines.map { it.toDomain() }
                val sales = domainLines.fold(ru.liftzip.orders.domain.model.Money.ZERO) { sum, line ->
                    sum + OrderCalculator.lineSale(line)
                }
                if (sales.minorUnits != order.receivedPaymentMinor) {
                    throw BackupException("Оплата заказа ${order.number} не равна сумме продажи")
                }
                val orderCosts = payload.costs.filter {
                    (it.ownerType == CostOwnerType.ORDER.name && it.ownerId == order.id) ||
                        (it.ownerType == CostOwnerType.LINE.name && orderLines.any { line -> line.id == it.ownerId })
                }
                OrderCalculator.calculate(order.toDomain(), domainLines, orderCosts.map { it.toDomain() })
            }
            payload.lines.forEach { if (it.orderId !in orders) throw BackupException("Позиция без заказа") }
            payload.costs.forEach { cost ->
                val valid = when (CostOwnerType.valueOf(cost.ownerType)) {
                    CostOwnerType.LINE -> cost.ownerId in lines
                    CostOwnerType.ORDER -> cost.ownerId in orders
                    CostOwnerType.GROUP -> cost.ownerId in groups
                }
                if (!valid) throw BackupException("Расход ссылается на отсутствующего владельца")
                val domainCost = cost.toDomain()
                if (domainCost.amount < java.math.BigDecimal.ZERO) throw BackupException("Отрицательный расход")
                OrderCalculator.costInRub(domainCost)
            }
            val recordKeys = mutableSetOf<String>()
            payload.priceRecords.forEach { record ->
                val line = lines[record.sourceLineId] ?: throw BackupException("Запись цены без исходной позиции")
                if (record.orderId != line.orderId || record.productId != line.productId || record.orderId !in orders) {
                    throw BackupException("Нарушена связь записи цены с заказом")
                }
                if (!recordKeys.add("${record.sourceLineId}:${record.type}")) {
                    throw BackupException("Дублируется производная запись цены")
                }
                val domainRecord = record.toDomain()
                when (domainRecord.type) {
                    ru.liftzip.orders.domain.model.PriceEventType.SALE -> {
                        if (domainRecord.currency != ru.liftzip.orders.domain.model.Currency.RUB ||
                            domainRecord.price.compareTo(ru.liftzip.orders.domain.model.Money(line.saleUnitPriceMinor).toBigDecimal()) != 0
                        ) throw BackupException("Запись продажи не совпадает с исходной позицией")
                    }
                    ru.liftzip.orders.domain.model.PriceEventType.PURCHASE -> {
                        if (line.purchaseUnitPrice == null || domainRecord.price.compareTo(line.purchaseUnitPrice.toBigDecimal()) != 0 ||
                            domainRecord.currency.name != line.purchaseCurrency || domainRecord.rate.compareTo(line.purchaseRate!!.toBigDecimal()) != 0
                        ) throw BackupException("Запись закупки не совпадает с исходной позицией")
                    }
                }
            }
            payload.orderCommands.forEach { if (it.orderId !in orders) throw BackupException("Команда без заказа") }
            payload.groupCommands.forEach {
                if (it.groupId !in groups || it.nextGroupId !in groups) throw BackupException("Команда без группы")
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            throw BackupException("Некорректные значения или связи в резервной копии", error)
        }
    }

    private fun <T, K> List<T>.uniqueBy(key: (T) -> K): Map<K, T> {
        val result = LinkedHashMap<K, T>()
        for (item in this) if (result.put(key(item), item) != null) throw BackupException("Дублируется идентификатор записи")
        return result
    }

    private fun BackupPayload.counts(): Map<String, Int> = mapOf(
        "products" to products.size, "groups" to groups.size, "orders" to orders.size,
        "lines" to lines.size, "costs" to costs.size, "priceRecords" to priceRecords.size,
        "changeEvents" to changeEvents.size, "orderCommands" to orderCommands.size,
        "groupCommands" to groupCommands.size, "metadata" to metadata.size,
    )

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    companion object {
        const val FORMAT = "liftzip-orders-backup"
        const val FORMAT_VERSION = 1
    }
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
