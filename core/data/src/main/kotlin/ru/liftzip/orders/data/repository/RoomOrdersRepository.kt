package ru.liftzip.orders.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import ru.liftzip.orders.domain.delivery.*
import ru.liftzip.orders.data.database.DeliveryInvoiceEntity
import ru.liftzip.orders.data.database.DeliveryPaymentEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.liftzip.orders.data.database.AppMetadataEntity
import ru.liftzip.orders.data.database.ChangeEventEntity
import ru.liftzip.orders.data.database.CostEntryEntity
import ru.liftzip.orders.data.database.GroupCommandEntity
import ru.liftzip.orders.data.database.OrderCommandEntity
import ru.liftzip.orders.data.database.OrderEntity
import ru.liftzip.orders.data.database.OrderLineImageEntity
import ru.liftzip.orders.data.database.OrderLineEntity
import ru.liftzip.orders.data.database.OrdersDao
import ru.liftzip.orders.data.database.OrdersDatabase
import ru.liftzip.orders.data.database.PriceRecordEntity
import ru.liftzip.orders.data.database.ProductEntity
import ru.liftzip.orders.data.database.SettlementGroupEntity
import ru.liftzip.orders.data.database.canonical
import ru.liftzip.orders.data.database.decimal
import ru.liftzip.orders.data.database.epoch
import ru.liftzip.orders.data.database.toDomain
import ru.liftzip.orders.data.database.uuid
import ru.liftzip.orders.domain.calculation.OrderCalculator
import ru.liftzip.orders.domain.calculation.OrderValidator
import ru.liftzip.orders.domain.calculation.normalizeArticle
import ru.liftzip.orders.domain.model.CalculationState
import ru.liftzip.orders.domain.model.CostDraft
import ru.liftzip.orders.domain.model.CostKind
import ru.liftzip.orders.domain.model.CostOwnerType
import ru.liftzip.orders.domain.model.Currency
import ru.liftzip.orders.domain.model.DomainValidationException
import ru.liftzip.orders.domain.model.FinishGroupCommand
import ru.liftzip.orders.domain.model.FinishGroupResult
import ru.liftzip.orders.domain.model.GroupDetails
import ru.liftzip.orders.domain.model.GroupState
import ru.liftzip.orders.domain.model.GroupSummary
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.model.OrderDetails
import ru.liftzip.orders.domain.model.OrderLineDraft
import ru.liftzip.orders.domain.model.OrderSummary
import ru.liftzip.orders.domain.model.OrdersFilter
import ru.liftzip.orders.domain.model.PriceEventType
import ru.liftzip.orders.domain.model.PriceRecordView
import ru.liftzip.orders.domain.model.Product
import ru.liftzip.orders.domain.model.ProductPriceHistory
import ru.liftzip.orders.domain.model.SaveGroupCostsCommand
import ru.liftzip.orders.domain.model.SaveOrderCommand
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoomOrdersRepository(
    private val database: OrdersDatabase,
    private val dao: OrdersDao = database.ordersDao(),
) : OrdersRepository {

    private val deliveryDao = database.deliveryDao()
    private fun DeliveryInvoiceEntity.domain() = DeliveryInvoice(orderId.uuid(), amountMinor?.let(::Money), date?.let(java.time.LocalDate::parse), actualWeight?.decimal(), actualTariff?.decimal(), actualRate?.decimal(), note, revision)
    private fun DeliveryPaymentEntity.domain() = DeliveryPayment(id.uuid(), orderId.uuid(), DeliveryParty.valueOf(party), Money(amountMinor), java.time.LocalDate.parse(date), note, reversalOf?.uuid())
    override fun observeDelivery(orderId: UUID): Flow<DeliveryDetails?> = combine(
        observeOrder(orderId), deliveryDao.observeInvoice(orderId.toString()), deliveryDao.observePayments(orderId.toString()),
    ) { order, invoice, payments -> order?.let { DeliveryDetails(invoice?.domain() ?: DeliveryInvoice(orderId), payments.map { it.domain() }, it.lines) } }.flowOn(Dispatchers.IO)

    override suspend fun saveDeliveryInvoice(invoice: DeliveryInvoice) = withContext(Dispatchers.IO) {
        DeliveryCalculator.validateInvoice(invoice)
        database.withTransaction {
            val id=invoice.orderId.toString()
            require(dao.getOrder(id) != null) { "Заказ не найден" }
            val old=deliveryDao.invoice(id)
            require((old?.revision ?: 0)==invoice.revision) { "Счёт изменился. Откройте доставку заново" }
            val entity=DeliveryInvoiceEntity(id, invoice.amount?.minorUnits, invoice.date?.toString(), invoice.actualWeight?.canonical(), invoice.actualTariff?.canonical(), invoice.actualRate?.canonical(), invoice.note, invoice.revision+1)
            deliveryDao.saveInvoice(entity)
            dao.insertChangeEvent(ChangeEventEntity(UUID.randomUUID().toString(), "DELIVERY",id,"INVOICE_UPDATED",Instant.now().epoch(),"local",old?.let { Json.encodeToString(DeliveryInvoiceEntity.serializer(),it) },Json.encodeToString(DeliveryInvoiceEntity.serializer(),entity)))
        }
    }
    override suspend fun addDeliveryPayment(payment: DeliveryPayment) = withContext(Dispatchers.IO) {
        require(payment.amount > Money.ZERO && payment.reversalOf == null) { "Платёж должен быть больше нуля" }
        database.withTransaction {
            require(dao.getOrder(payment.orderId.toString()) != null) { "Заказ не найден" }
            val entity=DeliveryPaymentEntity(payment.id.toString(), payment.orderId.toString(),payment.party.name,payment.amount.minorUnits,payment.date.toString(),payment.note,null)
            val old=deliveryDao.payment(entity.id)
            if(old != null) { require(old==entity) { "Идентификатор операции уже использован" }; return@withTransaction }
            val total=DeliveryCalculator.sumPayments(deliveryDao.payments(entity.orderId).filter { it.party==entity.party }.map { Money(it.amountMinor) })
            total+payment.amount // Reject an overflow before committing the payment.
            deliveryDao.insertPayment(entity)
            dao.insertChangeEvent(ChangeEventEntity(UUID.randomUUID().toString(),"DELIVERY",entity.orderId,"PAYMENT_RECORDED",Instant.now().epoch(),"local",null,Json.encodeToString(DeliveryPaymentEntity.serializer(),entity)))
        }
    }
    override suspend fun reverseDeliveryPayment(id:UUID,commandId:UUID) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val old=deliveryDao.payment(id.toString()) ?: error("Запись не найдена")
            require(old.reversalOf==null && old.amountMinor>0) { "Нельзя отменить сторно" }
            if(deliveryDao.reversal(old.id)!=null) return@withTransaction
            val reversal=old.copy(id=commandId.toString(),amountMinor=-old.amountMinor,reversalOf=old.id,note="Сторно: "+old.note,date=java.time.LocalDate.now().toString())
            deliveryDao.insertPayment(reversal)
            dao.insertChangeEvent(ChangeEventEntity(UUID.randomUUID().toString(),"DELIVERY",old.orderId,"PAYMENT_REVERSED",Instant.now().epoch(),"local",null,Json.encodeToString(DeliveryPaymentEntity.serializer(),reversal)))
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (dao.nextGroupNumber() == 1L && dao.getFormingGroup() == null) {
                val now = Instant.now()
                dao.insertGroup(
                    SettlementGroupEntity(
                        id = UUID.randomUUID().toString(), number = 1, state = GroupState.FORMING.name,
                        costsConfirmed = false, createdAt = now.epoch(), completedAt = null,
                        updatedAt = now.epoch(), revision = 1,
                    ),
                )
            }
        }
    }

    override fun observeOrders(query: String, filter: OrdersFilter, limit: Int): Flow<List<OrderSummary>> {
        val like = "%${query.trim()}%"
        return dao.observeOrders(like, filter.name, limit.coerceIn(1, 500))
            .map { orders -> orders.map { toSummary(it) } }
            .flowOn(Dispatchers.IO)
    }

    override fun observeOrder(orderId: UUID): Flow<OrderDetails?> =
        dao.observeOrder(orderId.toString()).flatMapLatest { order ->
            if (order == null) flowOf(null)
            else dao.observeGroup(order.groupId).map { group ->
                if (group == null) null else loadOrderDetails(order, group)
            }
        }.flowOn(Dispatchers.IO)

    override fun observeGroups(): Flow<List<GroupSummary>> = combine(
        dao.observeGroups(),
        dao.observeAllOrders(),
        dao.observeAllLines(),
        dao.observeAllCosts(),
    ) { groups, orders, lines, costs ->
        groups.map { group ->
            val groupOrders = orders.filter { it.groupId == group.id }
            summarize(group, groupOrders, lines, costs)
        }
    }.flowOn(Dispatchers.IO)

    override fun observeGroup(groupId: UUID): Flow<GroupDetails?> = combine(
        dao.observeGroup(groupId.toString()),
        dao.observeGroupOrders(groupId.toString()),
        dao.observeAllLines(),
        dao.observeAllCosts(),
    ) { group, orders, lines, costs ->
        if (group == null) null else {
            val summaries = orders.map { order ->
                toSummary(order, group, lines.filter { it.orderId == order.id }, costs)
            }
            GroupDetails(
                summary = summarize(group, orders, lines, costs),
                orders = summaries,
                costs = costs.filter { it.ownerType == CostOwnerType.GROUP.name && it.ownerId == group.id }
                    .map { it.toDomain() },
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun observeProducts(query: String, limit: Int): Flow<List<Product>> =
        dao.observeProducts("%${query.trim()}%", limit.coerceIn(1, 500))
            .map { items -> items.map { it.toDomain() } }
            .flowOn(Dispatchers.IO)

    override fun observeProductHistory(productId: UUID): Flow<ProductPriceHistory?> = combine(
        dao.observeProduct(productId.toString()),
        dao.observePriceRecords(productId.toString()),
        dao.observeProductLines(productId.toString()),
    ) { product, records, lines ->
        if (product == null) null else {
            val linesById = lines.associateBy { it.id }
            val orderIds = records.map { it.orderId }.distinct()
            val orders = if (orderIds.isEmpty()) emptyList() else dao.getOrdersByIds(orderIds)
            val imagesByLine = dao.getImagesForLines(lines.map { it.id }).groupBy { it.lineId }
            ProductPriceHistory(
                product = product.toDomain(),
                records = records.mapNotNull { record ->
                    orders.firstOrNull { it.id == record.orderId }?.let {
                        PriceRecordView(record.toDomain(), it.number,
                            linesById[record.sourceLineId]?.deliveryWeight?.decimal(), it.orderRate?.decimal(),
                            linesById[record.sourceLineId]?.deliveryTariff?.decimal(),
                            linesById[record.sourceLineId]?.actualWeight?.decimal(),
                            linesById[record.sourceLineId]?.productUrl,
                            imagesByLine[record.sourceLineId].orEmpty().map { image -> image.jpeg })
                    }
                },
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun setOrderAction(
        orderId: UUID,
        action: ru.liftzip.orders.domain.model.OrderAction,
        checked: Boolean,
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val old = dao.getOrder(orderId.toString()) ?: throw DomainValidationException("Заказ не найден")
            val group = dao.getGroup(old.groupId) ?: throw DomainValidationException("Группа не найдена")
            if (group.state == GroupState.FORMING.name) throw DomainValidationException("Сначала завершите формирование группы")
            val progress = old.toDomain().fulfillment.withAction(action, checked)
            if (progress == old.toDomain().fulfillment) return@withTransaction
            val now = Instant.now()
            val updated = old.copy(
                withdrawn = progress.withdrawn, transferred = progress.transferred, received = progress.received,
                updatedAt = now.epoch(), revision = old.revision + 1,
            )
            dao.updateOrder(updated)
            val orders = dao.getGroupOrders(group.id)
            val state = if (group.state == GroupState.FORMING.name) GroupState.FORMING
                else if (orders.isNotEmpty() && orders.all { it.toDomain().fulfillment.isComplete }) GroupState.COMPLETED
                else GroupState.CALCULATING
            if (state.name != group.state) dao.updateGroup(group.copy(
                state = state.name, updatedAt = now.epoch(), revision = group.revision + 1,
            ))
            dao.insertChangeEvent(ChangeEventEntity(
                id = UUID.randomUUID().toString(), entityType = "ORDER", entityId = old.id,
                changeType = "FULFILLMENT_${action.name}", eventAt = now.epoch(), localUser = "local",
                beforeJson = Json.encodeToString(OrderEntity.serializer(), old),
                afterJson = Json.encodeToString(OrderEntity.serializer(), updated),
            ))
        }
    }

    override suspend fun deleteOrder(orderId: UUID) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val old = dao.getOrder(orderId.toString()) ?: return@withTransaction
            val invoice = deliveryDao.invoice(old.id)
            if (invoice?.amountMinor != null || deliveryDao.payments(old.id).isNotEmpty()) {
                throw DomainValidationException("У заказа есть счёт или оплаты доставки. Удаление не должно стирать финансовые записи")
            }
            val group = dao.getGroup(old.groupId) ?: throw DomainValidationException("Группа не найдена")
            val lines = dao.getOrderLines(old.id)
            val costs = dao.getOrderCosts(old.id)
            val now = Instant.now()
            val before = JsonObject(mapOf(
                "orderData" to Json.parseToJsonElement(auditJson(old, lines, costs)),
                "deliveryDraft" to Json.encodeToJsonElement(DeliveryInvoiceEntity.serializer().nullable, invoice),
            ))
            // Preserve numbering even when the highest numbered order is removed.
            dao.putMetadata(AppMetadataEntity("order_number_high_water", (dao.nextOrderNumber() - 1).toString()))
            dao.insertChangeEvent(ChangeEventEntity(UUID.randomUUID().toString(), "ORDER", old.id,
                "DELETED", now.epoch(), "local", before.toString(), null))
            dao.deleteOrderCosts(old.id)
            dao.deleteOrder(old.id)
            if (group.state != GroupState.FORMING.name) {
                val remaining = dao.getGroupOrders(group.id)
                val state = if (remaining.isNotEmpty() && remaining.all { it.toDomain().fulfillment.isComplete })
                    GroupState.COMPLETED else GroupState.CALCULATING
                dao.updateGroup(group.copy(state = state.name, updatedAt = now.epoch(), revision = group.revision + 1))
            }
        }
    }

    override suspend fun saveOrder(command: SaveOrderCommand): UUID = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.getOrderCommand(command.commandId.toString())?.let { return@withTransaction it.orderId.uuid() }
            OrderValidator.validate(command)
            val now = Instant.now()
            val existing = command.orderId?.let { dao.getOrder(it.toString()) }
                ?: throwIfMissingId(command.orderId)
            val group = if (existing == null) {
                dao.getFormingGroup() ?: createInitialGroupIfNeeded(now)
            } else {
                dao.getGroup(existing.groupId) ?: throw DomainValidationException("Группа заказа не найдена")
            }
            if (existing != null && group.state != GroupState.FORMING.name) {
                assertOnlyPurchaseAndCostsChanged(existing, command)
            }
            val orderId = existing?.id ?: UUID.randomUUID().toString()
            val orderNumber = existing?.number ?: dao.nextOrderNumber()
            val payment = Money.fromRubles(command.receivedPayment)
            var order = OrderEntity(
                id = orderId, number = orderNumber, date = command.date.toString(),
                counterparty = command.counterparty.trim(), groupId = group.id,
                receivedPaymentMinor = payment.minorUnits, comment = command.comment,
                costsConfirmed = command.costsConfirmed, calculationState = CalculationState.INCOMPLETE.name,
                createdAt = existing?.createdAt ?: now.epoch(), updatedAt = now.epoch(),
                revision = (existing?.revision ?: 0) + 1,
                manualDeliveryPriceMinor = command.manualDeliveryPrice?.let { Money.fromRubles(it).minorUnits },
                orderRate = command.orderRate?.canonical(),
                withdrawn = existing?.withdrawn ?: false,
                transferred = existing?.transferred ?: false,
                received = existing?.received ?: false,
            )
            if (existing == null) dao.insertOrder(order) else dao.updateOrder(order)

            val oldLines = if (existing == null) emptyList() else dao.getOrderLines(orderId)
            val oldCosts = if (existing == null) emptyList() else dao.getOrderCosts(orderId)
            val resolvedLines = command.lines.map { draft ->
                val product = resolveProduct(draft, now)
                val old = draft.id?.let { id -> oldLines.firstOrNull { it.id == id.toString() } }
                OrderLineEntity(
                    id = old?.id ?: UUID.randomUUID().toString(), orderId = orderId, productId = product.id,
                    productNameSnapshot = draft.productName.trim(), articleSnapshot = draft.article?.trim()?.ifEmpty { null },
                    quantity = draft.quantity.stripTrailingZeros().toPlainString(),
                    saleUnitPriceMinor = Money.fromRubles(draft.saleUnitPrice).minorUnits,
                    purchaseUnitPrice = draft.purchaseUnitPrice?.canonical(),
                    purchaseCurrency = draft.purchaseCurrency?.name, purchaseRate = draft.purchaseRate?.canonical(),
                    createdAt = old?.createdAt ?: now.epoch(), updatedAt = now.epoch(),
                    revision = (old?.revision ?: 0) + 1,
                    deliveryWeight = draft.deliveryWeight?.canonical(), deliveryTariff = draft.deliveryTariff?.canonical(),
                    deliveryRate = if (draft.deliveryWeight != null || draft.deliveryTariff != null || draft.deliveryPlanDate != null)
                        command.orderRate?.canonical() else null,
                    deliveryPlanDate = draft.deliveryPlanDate?.toString(), productUrl = draft.productUrl,
                    temporaryProductUrl = draft.temporaryProductUrl,
                    actualWeight = draft.actualWeight?.canonical(),
                )
            }
            // Сначала удаляем полиморфные расходы, пока исходные позиции ещё доступны.
            dao.deleteOrderCosts(orderId)
            dao.deleteRemovedLines(orderId, resolvedLines.map { it.id })
            resolvedLines.forEach { line ->
                if (oldLines.any { it.id == line.id }) dao.updateLine(line) else dao.insertLine(line)
                dao.deleteLineImages(line.id)
                command.lines[resolvedLines.indexOf(line)].images.forEachIndexed { position, bytes ->
                    dao.insertLineImage(OrderLineImageEntity(UUID.randomUUID().toString(), line.id, position, bytes))
                }
                syncPriceRecords(order, line, now)
            }
            syncOrderCosts(orderId, command.costs, command.lines, resolvedLines, oldCosts, now)
            val domainOrder = order.toDomain()
            val domainLines = resolvedLines.map { it.toDomain() }
            val domainCosts = dao.getOrderCosts(orderId).map { it.toDomain() }
            val calculation = OrderCalculator.calculate(domainOrder, domainLines, domainCosts)
            order = order.copy(
                calculationState = if (calculation.isComplete) CalculationState.PRELIMINARY.name
                else CalculationState.INCOMPLETE.name,
            )
            dao.updateOrder(order)
            dao.insertChangeEvent(
                ChangeEventEntity(
                    id = UUID.randomUUID().toString(), entityType = "ORDER", entityId = orderId,
                    changeType = if (existing == null) "CREATED" else "UPDATED", eventAt = now.epoch(),
                    localUser = "local", beforeJson = existing?.let { auditJson(it, oldLines, oldCosts) },
                    afterJson = auditJson(order, resolvedLines, dao.getOrderCosts(orderId)),
                ),
            )
            dao.insertOrderCommand(OrderCommandEntity(command.commandId.toString(), orderId, now.epoch()))
            orderId.uuid()
        }
    }

    override suspend fun saveGroupCosts(command: SaveGroupCostsCommand) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val group = dao.getGroup(command.groupId.toString())
                ?: throw DomainValidationException("Группа не найдена")
            command.costs.forEach { OrderValidator.validateCost(it, "Группа") }
            val now = Instant.now()
            val oldCosts = dao.getGroupCosts(group.id)
            dao.deleteGroupCosts(group.id)
            command.costs.forEach { draft ->
                val old = draft.id?.let { id -> oldCosts.firstOrNull { it.id == id.toString() } }
                dao.insertCost(draft.toEntity(CostOwnerType.GROUP, group.id.uuid(), old, now))
            }
            dao.updateGroup(group.copy(
                costsConfirmed = command.costsConfirmed, updatedAt = now.epoch(), revision = group.revision + 1,
            ))
            dao.insertChangeEvent(
                ChangeEventEntity(
                    UUID.randomUUID().toString(), "GROUP", group.id, "COSTS_UPDATED", now.epoch(),
                    "local", groupAuditJson(group.costsConfirmed, oldCosts),
                    groupAuditJson(command.costsConfirmed, dao.getGroupCosts(group.id)),
                ),
            )
        }
    }

    override suspend fun finishGroup(command: FinishGroupCommand): FinishGroupResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.getGroupCommand(command.commandId.toString())?.let { previous ->
                return@withTransaction FinishGroupResult(
                    completedGroupId = previous.groupId.uuid(), nextGroupId = previous.nextGroupId.uuid(), wasRepeated = true,
                )
            }
            val group = dao.getGroup(command.groupId.toString())
                ?: throw DomainValidationException("Группа не найдена")
            if (group.state != GroupState.FORMING.name) {
                throw DomainValidationException("Формирование этой группы уже завершено")
            }
            if (dao.countOrdersInGroup(group.id) == 0) {
                throw DomainValidationException("Пустую группу нельзя отправить на расчёт")
            }
            val now = Instant.now()
            dao.updateGroup(group.copy(
                state = if (dao.getGroupOrders(group.id).all { it.toDomain().fulfillment.isComplete }) GroupState.COMPLETED.name else GroupState.CALCULATING.name,
                completedAt = now.epoch(), updatedAt = now.epoch(),
                revision = group.revision + 1,
            ))
            val next = SettlementGroupEntity(
                id = UUID.randomUUID().toString(), number = dao.nextGroupNumber(), state = GroupState.FORMING.name,
                costsConfirmed = false, createdAt = now.epoch(), completedAt = null,
                updatedAt = now.epoch(), revision = 1,
            )
            dao.insertGroup(next)
            dao.insertGroupCommand(GroupCommandEntity(command.commandId.toString(), group.id, next.id, now.epoch()))
            FinishGroupResult(group.id.uuid(), next.id.uuid(), false)
        }
    }

    private suspend fun loadOrderDetails(order: OrderEntity, group: SettlementGroupEntity): OrderDetails = OrderDetails(
        order = order.toDomain(), group = group.toDomain(),
        lines = dao.getOrderLines(order.id).map { line ->
            line.toDomain().copy(images = dao.getLineImages(line.id).map { it.jpeg })
        },
        costs = dao.getOrderCosts(order.id).map { it.toDomain() },
        history = dao.getOrderHistory(order.id).map { it.toDomain() },
    )

    private suspend fun toSummary(
        order: OrderEntity,
        knownGroup: SettlementGroupEntity? = null,
        knownLines: List<OrderLineEntity>? = null,
        knownCosts: List<CostEntryEntity>? = null,
    ): OrderSummary {
        val group = knownGroup ?: dao.getGroup(order.groupId)
            ?: throw DomainValidationException("Группа заказа не найдена")
        val lines = knownLines ?: dao.getOrderLines(order.id)
        val costs = knownCosts?.filter {
            (it.ownerType == CostOwnerType.ORDER.name && it.ownerId == order.id) ||
                (it.ownerType == CostOwnerType.LINE.name && lines.any { line -> line.id == it.ownerId })
        } ?: dao.getOrderCosts(order.id)
        val calculation = OrderCalculator.calculate(order.toDomain(), lines.map { it.toDomain() }, costs.map { it.toDomain() })
        return OrderSummary(
            order = order.toDomain(), groupNumber = group.number, groupState = GroupState.valueOf(group.state),
            composition = lines.joinToString(", ") { it.productNameSnapshot }.take(160),
            calculation = calculation,
        )
    }

    private fun summarize(
        group: SettlementGroupEntity,
        orders: List<OrderEntity>,
        allLines: List<OrderLineEntity>,
        allCosts: List<CostEntryEntity>,
    ): GroupSummary {
        val triples = orders.map { order ->
            val lines = allLines.filter { it.orderId == order.id }
            val costs = allCosts.filter {
                (it.ownerType == CostOwnerType.ORDER.name && it.ownerId == order.id) ||
                    (it.ownerType == CostOwnerType.LINE.name && lines.any { line -> line.id == it.ownerId })
            }
            Triple(order.toDomain(), lines.map { it.toDomain() }, costs.map { it.toDomain() })
        }
        val groupCosts = allCosts.filter { it.ownerType == CostOwnerType.GROUP.name && it.ownerId == group.id }
            .map { it.toDomain() }
        return OrderCalculator.summarizeGroup(group.toDomain(), triples, groupCosts)
    }

    private suspend fun createInitialGroupIfNeeded(now: Instant): SettlementGroupEntity {
        if (dao.nextGroupNumber() != 1L) throw DomainValidationException("Нет формируемой группы")
        val group = SettlementGroupEntity(
            id = UUID.randomUUID().toString(), number = 1, state = GroupState.FORMING.name,
            costsConfirmed = false, createdAt = now.epoch(), completedAt = null,
            updatedAt = now.epoch(), revision = 1,
        )
        dao.insertGroup(group)
        return group
    }

    private suspend fun resolveProduct(draft: OrderLineDraft, now: Instant): ProductEntity {
        draft.productId?.let { id ->
            return dao.getProduct(id.toString()) ?: throw DomainValidationException("Выбранный товар не найден")
        }
        val normalized = normalizeArticle(draft.article)
        dao.findReusableProduct(draft.productName.trim(), normalized)?.let { return it }
        val product = ProductEntity(
            id = UUID.randomUUID().toString(), name = draft.productName.trim(), article = draft.article?.trim()?.ifEmpty { null },
            normalizedArticle = normalized, brand = null, aliasesJson = "[]", createdAt = now.epoch(),
            updatedAt = now.epoch(), revision = 1,
        )
        dao.insertProduct(product)
        return product
    }

    private suspend fun syncPriceRecords(order: OrderEntity, line: OrderLineEntity, now: Instant) {
        upsertPriceRecord(order, line, PriceEventType.SALE, now)
        if (line.purchaseUnitPrice != null && line.purchaseCurrency != null && line.purchaseRate != null) {
            upsertPriceRecord(order, line, PriceEventType.PURCHASE, now)
        } else {
            dao.deletePriceRecord(line.id, PriceEventType.PURCHASE.name)
        }
    }

    private suspend fun upsertPriceRecord(
        order: OrderEntity,
        line: OrderLineEntity,
        type: PriceEventType,
        now: Instant,
    ) {
        val old = dao.getPriceRecord(line.id, type.name)
        val isSale = type == PriceEventType.SALE
        val record = PriceRecordEntity(
            id = old?.id ?: UUID.randomUUID().toString(), productId = line.productId, sourceLineId = line.id,
            orderId = order.id, type = type.name,
            eventAt = java.time.LocalDate.parse(order.date).atStartOfDay().toInstant(ZoneOffset.UTC).epoch(),
            price = if (isSale) Money(line.saleUnitPriceMinor).toBigDecimal().canonical() else line.purchaseUnitPrice!!,
            currency = if (isSale) Currency.RUB.name else line.purchaseCurrency!!,
            rate = if (isSale) "1" else line.purchaseRate!!, quantity = line.quantity,
            preliminary = !isSale, createdAt = old?.createdAt ?: now.epoch(), updatedAt = now.epoch(),
            revision = (old?.revision ?: 0) + 1,
        )
        if (old == null) dao.insertPriceRecord(record) else dao.updatePriceRecord(record)
    }

    private suspend fun syncOrderCosts(
        orderId: String,
        orderCosts: List<CostDraft>,
        lineDrafts: List<OrderLineDraft>,
        lines: List<OrderLineEntity>,
        oldCosts: List<CostEntryEntity>,
        now: Instant,
    ) {
        orderCosts.forEach { draft ->
            val old = draft.id?.let { id -> oldCosts.firstOrNull { it.id == id.toString() } }
            dao.insertCost(draft.toEntity(CostOwnerType.ORDER, orderId.uuid(), old, now))
        }
        lineDrafts.forEachIndexed { index, draft ->
            val line = lines[index]
            draft.costs.forEach { cost ->
                val old = cost.id?.let { id -> oldCosts.firstOrNull { it.id == id.toString() } }
                dao.insertCost(cost.toEntity(CostOwnerType.LINE, line.id.uuid(), old, now))
            }
        }
    }

    private fun CostDraft.toEntity(
        ownerType: CostOwnerType,
        ownerId: UUID,
        old: CostEntryEntity?,
        now: Instant,
    ) = CostEntryEntity(
        id = old?.id ?: UUID.randomUUID().toString(), ownerType = ownerType.name, ownerId = ownerId.toString(),
        kind = kind.name, amount = amount.canonical(), currency = currency.name, rate = rate.canonical(),
        isConfirmed = isConfirmed, date = date.toString(), comment = comment,
        createdAt = old?.createdAt ?: now.epoch(), updatedAt = now.epoch(), revision = (old?.revision ?: 0) + 1,
    )

    private suspend fun assertOnlyPurchaseAndCostsChanged(existing: OrderEntity, command: SaveOrderCommand) {
        val oldLines = dao.getOrderLines(existing.id).sortedBy { it.id }
        val newLines = command.lines.sortedBy { it.id.toString() }
        val sameLines = newLines.isNotEmpty() && newLines.all { new ->
            val old = oldLines.firstOrNull { it.id == new.id?.toString() } ?: return@all false
            old.id == new.id?.toString() && old.productId == new.productId?.toString() &&
                old.productNameSnapshot == new.productName.trim() &&
                old.articleSnapshot == new.article?.trim()?.ifEmpty { null } &&
                old.quantity.decimal().compareTo(new.quantity) == 0 &&
                old.saleUnitPriceMinor == Money.fromRubles(new.saleUnitPrice).minorUnits
        }
        if (existing.date != command.date.toString() || existing.counterparty != command.counterparty.trim() ||
            existing.receivedPaymentMinor != Money.fromRubles(command.receivedPayment).minorUnits || !sameLines
        ) {
            throw DomainValidationException(
                "После завершения формирования можно менять только закупочные поля, расходы и комментарий",
            )
        }
    }

    private fun auditJson(order: OrderEntity, lines: List<OrderLineEntity>, costs: List<CostEntryEntity>): String = Json.encodeToString(
        JsonObject(
            mapOf(
                "order" to Json.encodeToJsonElement(OrderEntity.serializer(), order),
                "costs" to Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CostEntryEntity.serializer()), costs),
                "lines" to Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(OrderLineEntity.serializer()), lines),
            ),
        ),
    )

    private fun groupAuditJson(confirmed: Boolean, costs: List<CostEntryEntity>): String = Json.encodeToString(
        JsonObject(mapOf(
            "costsConfirmed" to JsonPrimitive(confirmed),
            "costs" to Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CostEntryEntity.serializer()), costs),
        )),
    )

    private fun throwIfMissingId(id: UUID?): OrderEntity? = if (id == null) null
    else throw DomainValidationException("Заказ не найден")
}
