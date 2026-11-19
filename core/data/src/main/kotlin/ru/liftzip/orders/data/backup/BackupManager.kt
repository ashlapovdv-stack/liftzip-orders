package ru.liftzip.orders.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.liftzip.orders.data.database.AppMetadataEntity
import ru.liftzip.orders.data.database.OrderLineImageEntity
import java.util.UUID
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

    private data class Snapshot(
        val payload: BackupPayload,
        val manifest: BackupManifest,
        val staging: File,
    ) {
        val inspection get() = BackupInspection(
            manifest.createdAt, manifest.schemaVersion, manifest.counts, manifest.formatVersion >= 2,
        )
    }

    suspend fun inspect(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        withStagedBackup(uri) { it.inspection }
    }

    suspend fun exportTo(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        mutex.withLock {
            finishPendingRestore()
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw BackupException("Не удалось открыть место сохранения")
            stream.use { exportSnapshot(it) }
        }
    }

    suspend fun importFrom(uri: Uri): BackupInspection = withContext(Dispatchers.IO) {
        mutex.withLock {
            finishPendingRestore()
            restore(uri, createRecoveryCopy = true)
        }
    }

    /** Used only with a separate, empty database from the startup recovery screen. */
    suspend fun restoreIntoNewDatabase(uri: Uri, activate: () -> Unit): BackupInspection = withContext(Dispatchers.IO) {
        mutex.withLock { restore(uri, createRecoveryCopy = false, activate = activate) }
    }

    private suspend fun restore(uri: Uri, createRecoveryCopy: Boolean, activate: () -> Unit = {}): BackupInspection =
        withStagedBackup(uri) { snapshot ->
            if (createRecoveryCopy) writeLocalRecoveryCopy()
            val journalId = UUID.randomUUID().toString().takeIf { snapshot.manifest.formatVersion >= 2 }
            var journal: File? = null
            try {
                if (journalId != null) {
                    journal = backupJournal(context.filesDir, journalId)
                    val incoming = File(journal, "incoming").apply { mkdirs() }
                    for (name in backupRoots) {
                        val source = File(snapshot.staging, name).apply { mkdirs() }
                        check(source.renameTo(File(incoming, name))) { "Не удалось подготовить вложения" }
                    }
                }
                replaceDatabase(snapshot, journalId, preservePreviousFiles = !createRecoveryCopy)
            } catch (error: Exception) {
                // If COMMIT completed before cancellation, startup owns the journal.
                if (journalId != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (backupDao.metadataValue(PENDING) != journalId) journal?.deleteRecursively()
                }
                throw error
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                activate()
                finishPendingRestore()
            }
            snapshot.inspection
        }

    suspend fun recoverPendingRestore() = withContext(Dispatchers.IO) {
        mutex.withLock { finishPendingRestore() }
    }

    private suspend fun finishPendingRestore() {
        val id = backupDao.metadataValue(PENDING) ?: return
        val journal = backupJournal(context.filesDir, id)
        val preservePrevious = backupDao.metadataValue(PRESERVE_PREVIOUS) == "true"
        installBackupFiles(context.filesDir, journal)
        database.withTransaction {
            backupDao.deleteMetadataKey(PENDING)
            backupDao.deleteMetadataKey(PRESERVE_PREVIOUS)
        }
        if (!preservePrevious) journal.deleteRecursively()
    }

    fun latestRecoveryCopy(): File? = File(context.filesDir, "recovery-backups")
        .listFiles { file -> file.isFile && file.name.endsWith(".zip") }
        ?.maxByOrNull { it.lastModified() }

    suspend fun createLocalRecoveryCopy(): File = withContext(Dispatchers.IO) {
        mutex.withLock { finishPendingRestore(); writeLocalRecoveryCopy() }
    }

    private suspend fun writeLocalRecoveryCopy(): File {
        val directory = File(context.filesDir, "recovery-backups").apply { mkdirs() }
        val file = File(directory, "before-import-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.zip")
        val pending = File(directory, "${file.name}.tmp")
        try {
            pending.outputStream().use { exportSnapshot(it) }
            withStagedBackup(Uri.fromFile(pending)) { it.inspection }
            check(pending.renameTo(file)) { "Не удалось записать проверенную копию" }
            return file
        } finally {
            pending.delete()
        }
    }

    private suspend fun exportSnapshot(output: OutputStream): BackupInspection {
        return ZipOutputStream(output.buffered()).use { zip ->
            database.withTransaction {
                val payload = BackupPayload(
                    products = backupDao.allProducts(), groups = backupDao.allGroups(), orders = backupDao.allOrders(),
                    lines = backupDao.allLines(), costs = backupDao.allCosts(), priceRecords = backupDao.allPriceRecords(),
                    changeEvents = backupDao.allChangeEvents(), orderCommands = backupDao.allOrderCommands(),
                    groupCommands = backupDao.allGroupCommands(), metadata = backupDao.allMetadata().filterNot { it.key == PENDING || it.key == PRESERVE_PREVIOUS },
                    deliveryInvoices = backupDao.allDeliveryInvoices(), deliveryPayments = backupDao.allDeliveryPayments(),
                )
                val data = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
                if (data.size > 64 * 1024 * 1024) throw BackupException("Данные заказа превышают допустимый размер архива")
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(data)
                zip.closeEntry()
                val images = backupDao.imageIndex()
                val media = mutableListOf<BackupMedia>()

                fun writeMedia(path: String, input: java.io.InputStream, limit: Long = 512L * 1024 * 1024) {
                    input.use {
                        zip.putNextEntry(ZipEntry(path))
                        media += copyBackupFile(it, zip, path, limit)
                        zip.closeEntry()
                    }
                }
                for (image in images) writeMedia("photos/${image.id}", backupDao.imageBytes(image.id).inputStream(), MAX_PHOTO)
                val owners = payload.orders.associate { it.id to it.groupId }
                val lineOwners = payload.lines.associate { it.id to it.orderId }
                for (root in backupRoots) {
                    val folder = File(context.filesDir, root)
                    folder.walkTopDown().onEnter { !it.name.endsWith("-staging") }.filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                        val path = file.relativeTo(context.filesDir).invariantSeparatorsPath
                        check(isBackupMediaPath(path)) { "Неизвестный путь вложения" }
                        val segments = path.split('/')
                        if (root == "order_documents" &&
                            (segments[1] !in owners || (segments.size == 4 && lineOwners[segments[2]] != segments[1]))
                        ) return@forEach
                        if (root == "mail_drafts") {
                            val orderId = segments[2].take(36)
                            if (owners[orderId] != segments[1]) return@forEach
                            if (segments.size == 4) {
                                val metadata = File(file.parentFile!!.parentFile, "$orderId.json")
                                if (!metadata.isFile) return@forEach
                                val generation = json.parseToJsonElement(metadata.readText()) as? kotlinx.serialization.json.JsonObject
                                val folderName = (generation?.get("generation") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: orderId
                                if (segments[2] != folderName) return@forEach
                            }
                        }
                        writeMedia(path, file.inputStream())
                    }
                }
                val manifest = BackupManifest(
                    FORMAT, FORMAT_VERSION, OrdersDatabase.SCHEMA_VERSION, Instant.now().toEpochMilli(),
                    sha256(data), payload.counts(), images, media,
                )
                val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
                if (manifestBytes.size > 16 * 1024 * 1024 || media.sumOf { it.size } > 8L * 1024 * 1024 * 1024) {
                    throw BackupException("Вложения превышают допустимый размер архива")
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestBytes)
                zip.closeEntry()
                BackupInspection(manifest.createdAt, manifest.schemaVersion, manifest.counts, true)
            }
        }
    }

    private suspend fun <T> withStagedBackup(uri: Uri, block: suspend (Snapshot) -> T): T {
        val staging = File(context.filesDir, "backup-staging/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = try {
                readAndValidate(uri, staging)
            } catch (error: BackupException) {
                throw error
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw BackupException("Не удалось проверить резервную копию", error)
            }
            return block(snapshot)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun readAndValidate(uri: Uri, staging: File): Snapshot {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw BackupException("Не удалось открыть резервную копию")
        val entries = mutableMapOf<String, ByteArray>()
        val media = mutableListOf<BackupMedia>()
        val names = hashSetOf<String>()
        var total = 0L
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                if (entry.isDirectory) continue
                if (!names.add(path)) throw BackupException("Повторяющийся файл в архиве")
                when (path) {
                    "manifest.json", "data.json" -> {
                        val output = ByteArrayOutputStream()
                        copyBackupFile(zip, output, path, if (path == "manifest.json") 16L * 1024 * 1024 else 64L * 1024 * 1024)
                        entries[path] = output.toByteArray()
                    }
                    else -> {
                        if (!isBackupMediaPath(path)) throw BackupException("Недопустимый путь в архиве")
                        val file = File(staging, path)
                        file.parentFile?.mkdirs()
                        media += file.outputStream().use { copyBackupFile(zip, it, path, if (path.startsWith("photos/")) MAX_PHOTO else 512L * 1024 * 1024) }
                        total += media.last().size
                        if (total > 8L * 1024 * 1024 * 1024) throw BackupException("Архив превышает допустимый размер")
                    }
                }
                zip.closeEntry()
            }
        }
        val manifestBytes = entries["manifest.json"] ?: throw BackupException("В архиве нет manifest.json")
        val data = entries["data.json"] ?: throw BackupException("В архиве нет data.json")
        val manifest = json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        if (manifest.format != FORMAT || manifest.formatVersion !in 1..FORMAT_VERSION) throw BackupException("Неподдерживаемый формат резервной копии")
        if (manifest.schemaVersion !in 2..OrdersDatabase.SCHEMA_VERSION) throw BackupException("Неподдерживаемая версия схемы: ${manifest.schemaVersion}")
        if (!manifest.dataSha256.equals(sha256(data), ignoreCase = true)) throw BackupException("Контрольная сумма данных не совпадает")
        val payload = json.decodeFromString(BackupPayload.serializer(), data.toString(Charsets.UTF_8))
        val counts = if (manifest.schemaVersion == 2) payload.counts().filterKeys { it !in setOf("deliveryInvoices", "deliveryPayments") } else payload.counts()
        if (counts != manifest.counts) throw BackupException("Количество записей не совпадает с manifest.json")
        if (manifest.schemaVersion == 2 && (payload.deliveryInvoices.isNotEmpty() || payload.deliveryPayments.isNotEmpty())) throw BackupException("Данные доставки не соответствуют версии архива")
        if (media.sortedBy { it.path } != manifest.media.sortedBy { it.path }) throw BackupException("Не совпадает состав или контрольная сумма вложений")
        val lines = payload.lines.mapTo(hashSetOf()) { it.id }
        val images = manifest.images.uniqueBy { it.id }
        if ((manifest.formatVersion == 1 && (media.isNotEmpty() || images.isNotEmpty())) ||
            images.values.any { it.lineId !in lines || it.position < 0 || File(staging, "photos/${it.id}").length() == 0L } ||
            images.keys.map { "photos/$it" }.toSet() != media.map { it.path }.filter { it.startsWith("photos/") }.toSet()
        ) throw BackupException("Некорректные связи фотографий")
        validatePayload(payload)
        return Snapshot(payload, manifest, staging)
    }

    private suspend fun replaceDatabase(snapshot: Snapshot, journalId: String?, preservePreviousFiles: Boolean) {
        val payload = snapshot.payload
        val manifest = snapshot.manifest
        database.withTransaction {
            val oldImages = if (manifest.formatVersion == 1) {
                val lineIds = payload.lines.mapTo(hashSetOf()) { it.id }
                backupDao.imageIndex().filter { it.lineId in lineIds }
                    .map { OrderLineImageEntity(it.id, it.lineId, it.position, backupDao.imageBytes(it.id)) }
            } else emptyList()
            backupDao.deleteDeliveryPayments()
            backupDao.deleteDeliveryInvoices()
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
            val ordersByGroup = payload.orders.groupBy { it.groupId }
            backupDao.insertGroups(payload.groups.map { group ->
                if (group.state == GroupState.FORMING.name) group
                else group.copy(state = ru.liftzip.orders.domain.model.groupProgressState(
                    ordersByGroup[group.id].orEmpty().map { it.toDomain().fulfillment },
                ).name)
            })
            backupDao.insertProducts(payload.products)
            backupDao.insertOrders(payload.orders)
            backupDao.insertLines(payload.lines)
            for (image in oldImages) database.ordersDao().insertLineImage(image)
            for (image in manifest.images) {
                val bytes = File(snapshot.staging, "photos/${image.id}").readBytes()
                database.ordersDao().insertLineImage(OrderLineImageEntity(image.id, image.lineId, image.position, bytes))
            }
            backupDao.insertCosts(payload.costs)
            backupDao.insertPriceRecords(payload.priceRecords)
            backupDao.insertChangeEvents(payload.changeEvents)
            backupDao.insertOrderCommands(payload.orderCommands)
            backupDao.insertGroupCommands(payload.groupCommands)
            backupDao.insertMetadata(payload.metadata.filterNot { it.key == PENDING || it.key == PRESERVE_PREVIOUS })
            if (journalId != null) {
                database.ordersDao().putMetadata(AppMetadataEntity(PENDING, journalId))
                if (preservePreviousFiles) database.ordersDao().putMetadata(AppMetadataEntity(PRESERVE_PREVIOUS, "true"))
            }
            backupDao.insertDeliveryInvoices(payload.deliveryInvoices)
            backupDao.insertDeliveryPayments(payload.deliveryPayments)
            database.ordersDao().markEmptyOrdersIncomplete()
        }
    }

    private fun validatePayload(payload: BackupPayload) {
        try {
            val products = payload.products.uniqueBy { it.id }
            val groups = payload.groups.uniqueBy { it.id }
            val orders = payload.orders.uniqueBy { it.id }
            val lines = payload.lines.uniqueBy { it.id }
            validateDeliveryBackup(payload)
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
            payload.lines.forEach {
                ru.liftzip.orders.domain.delivery.DeliveryCalculator.validatePlan(it.deliveryWeight?.toBigDecimal(), it.deliveryTariff?.toBigDecimal(), it.deliveryRate?.toBigDecimal(), it.deliveryPlanDate?.let(java.time.LocalDate::parse))
            }
            payload.products.forEach { it.toDomain() }
            payload.groups.forEach { it.toDomain() }
            payload.groups.forEach { group ->
                val groupOrders = payload.orders.filter { it.groupId == group.id }
                val allDone = groupOrders.isNotEmpty() && groupOrders.all { it.withdrawn && it.transferred && it.received }
                if ((group.state == GroupState.COMPLETED.name) != (group.state != GroupState.FORMING.name && allDone)) {
                    throw BackupException("Статус группы не соответствует отметкам заказов")
                }
            }
            payload.orders.forEach { order ->
                if (order.groupId !in groups) throw BackupException("Заказ ссылается на отсутствующую группу")
                if (order.receivedPaymentMinor < 0) throw BackupException("Отрицательная оплата в заказе ${order.number}")
                val orderLines = payload.lines.filter { it.orderId == order.id }
                orderLines.forEach { line ->
                    if (line.productId !in products) throw BackupException("Позиция ссылается на отсутствующий товар")
                    val domain = line.toDomain()
                    if ((domain.quantity?.let { it <= java.math.BigDecimal.ZERO } == true) || (line.saleUnitPriceMinor != null && line.saleUnitPriceMinor < 0)) {
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
                            line.saleUnitPriceMinor == null || line.quantity == null || domainRecord.price.compareTo(ru.liftzip.orders.domain.model.Money(line.saleUnitPriceMinor!!).toBigDecimal()) != 0
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
        "groupCommands" to groupCommands.size, "metadata" to metadata.size, "deliveryInvoices" to deliveryInvoices.size, "deliveryPayments" to deliveryPayments.size,
    )

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    companion object {
        const val FORMAT = "liftzip-orders-backup"
        const val FORMAT_VERSION = 2
        private const val MAX_PHOTO = 1_500_000L
        private const val PENDING = "backup_restore_pending"
        private const val PRESERVE_PREVIOUS = "backup_preserve_previous"
        private val mutex = Mutex()
    }
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun validateDeliveryBackup(payload: BackupPayload) {
    val orders=payload.orders.map { it.id }.toSet()
    require(payload.deliveryInvoices.map { it.orderId }.distinct().size == payload.deliveryInvoices.size)
    val payments=payload.deliveryPayments.associateBy { it.id }
    require(payments.size == payload.deliveryPayments.size)
    val reversals=mutableSetOf<String>()
    payload.deliveryInvoices.forEach {
        require(it.orderId in orders && it.revision>0)
        ru.liftzip.orders.domain.delivery.DeliveryCalculator.validateInvoice(ru.liftzip.orders.domain.delivery.DeliveryInvoice(java.util.UUID.fromString(it.orderId),it.amountMinor?.let { amount -> ru.liftzip.orders.domain.model.Money(amount) },it.date?.let(java.time.LocalDate::parse),it.actualWeight?.toBigDecimal(),it.actualTariff?.toBigDecimal(),it.actualRate?.toBigDecimal(),it.note,it.revision))
    }
    payments.values.groupBy { it.orderId to it.party }.values.forEach { records ->
        ru.liftzip.orders.domain.delivery.DeliveryCalculator.sumPayments(records.map { ru.liftzip.orders.domain.model.Money(it.amountMinor) })
    }
    payments.values.forEach {
        require(it.orderId in orders); java.util.UUID.fromString(it.id);java.time.LocalDate.parse(it.date)
        ru.liftzip.orders.domain.delivery.DeliveryParty.valueOf(it.party)
        val original=it.reversalOf
        if(original==null) require(it.amountMinor>0) else {
            val first=payments[original] ?: error("Сторно без исходного платежа")
            require(reversals.add(original) && first.reversalOf==null && first.amountMinor>0 && first.orderId==it.orderId && first.party==it.party && it.amountMinor == -first.amountMinor)
        }
    }
}
