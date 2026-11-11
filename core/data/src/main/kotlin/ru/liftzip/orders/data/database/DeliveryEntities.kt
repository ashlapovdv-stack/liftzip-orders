package ru.liftzip.orders.data.database

import androidx.room.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow

@Serializable
@Entity(tableName="delivery_invoices", foreignKeys=[ForeignKey(entity=OrderEntity::class,parentColumns=["id"],childColumns=["orderId"],onDelete=ForeignKey.CASCADE)])
data class DeliveryInvoiceEntity(
    @PrimaryKey val orderId:String, val amountMinor:Long?, val date:String?,
    val actualWeight:String?, val actualTariff:String?, val actualRate:String?,
    val note:String, val revision:Long,
)
@Serializable
@Entity(tableName="delivery_payments", foreignKeys=[ForeignKey(entity=OrderEntity::class,parentColumns=["id"],childColumns=["orderId"],onDelete=ForeignKey.CASCADE)], indices=[Index("orderId"),Index(value=["reversalOf"],unique=true)])
data class DeliveryPaymentEntity(
    @PrimaryKey val id:String, val orderId:String, val party:String,
    val amountMinor:Long, val date:String, val note:String, val reversalOf:String?,
)
@Dao
interface DeliveryDao {
    @Query("SELECT * FROM delivery_invoices WHERE orderId=:id") fun observeInvoice(id:String):Flow<DeliveryInvoiceEntity?>
    @Query("SELECT * FROM delivery_invoices WHERE orderId=:id") suspend fun invoice(id:String):DeliveryInvoiceEntity?
    @Query("SELECT * FROM delivery_payments WHERE orderId=:id ORDER BY date,id") fun observePayments(id:String):Flow<List<DeliveryPaymentEntity>>
    @Query("SELECT * FROM delivery_payments WHERE orderId=:id ORDER BY date,id") suspend fun payments(id:String):List<DeliveryPaymentEntity>
    @Query("SELECT * FROM delivery_payments WHERE id=:id") suspend fun payment(id:String):DeliveryPaymentEntity?
    @Query("SELECT * FROM delivery_payments WHERE reversalOf=:id") suspend fun reversal(id:String):DeliveryPaymentEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveInvoice(item:DeliveryInvoiceEntity)
    @Insert suspend fun insertPayment(item:DeliveryPaymentEntity)
}
