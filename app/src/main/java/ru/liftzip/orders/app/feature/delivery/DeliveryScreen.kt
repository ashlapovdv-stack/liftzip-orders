package ru.liftzip.orders.app.feature.delivery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.liftzip.orders.app.ui.*
import ru.liftzip.orders.domain.delivery.*
import ru.liftzip.orders.domain.model.Money
import ru.liftzip.orders.domain.repository.OrdersRepository
import java.time.LocalDate
import java.util.UUID

data class InvoiceDraft(val amount:String="",val date:String=LocalDate.now().toString(),val weight:String="",val tariff:String="",val rate:String="",val note:String="",val revision:Long=0)
class DeliveryViewModel(private val repo:OrdersRepository,private val id:UUID):ViewModel() {
    val details=MutableStateFlow<DeliveryDetails?>(null)
    val draft=MutableStateFlow(InvoiceDraft())
    val dirty=MutableStateFlow(false)
    val busy=MutableStateFlow(false)
    val message=MutableStateFlow<String?>(null)
    init { viewModelScope.launch { repo.observeDelivery(id).collect { value ->
        details.value=value
        if(value!=null && !dirty.value) { val i=value.invoice;draft.value=InvoiceDraft(i.amount?.toBigDecimal()?.toPlainString().orEmpty(),i.date?.toString() ?: LocalDate.now().toString(),i.actualWeight?.toPlainString().orEmpty(),i.actualTariff?.toPlainString().orEmpty(),i.actualRate?.toPlainString().orEmpty(),i.note,i.revision) }
    } } }
    fun update(f:(InvoiceDraft)->InvoiceDraft) { draft.update(f);dirty.value=true }
    fun calculate() { try { val d=draft.value; val amount=DeliveryCalculator.amount(requireNotNull(d.weight.parseDecimal()),requireNotNull(d.tariff.parseDecimal()),requireNotNull(d.rate.parseDecimal()));update { it.copy(amount=amount.toBigDecimal().toPlainString()) } } catch(_:Exception) { message.value="Заполните фактический вес, тариф и положительный курс" } }
    fun save(onDone:()->Unit={}) { if(busy.value)return;busy.value=true;viewModelScope.launch {
        try { val d=draft.value
            fun optional(text:String)= if(text.isBlank()) null else requireNotNull(text.parseDecimal()) { "Некорректное число" }
            val amount=optional(d.amount)
            require(amount==null || amount.stripTrailingZeros().scale()<=2) { "Счёт в рублях — с точностью до копеек" }
            repo.saveDeliveryInvoice(DeliveryInvoice(id,amount?.let(Money::fromRubles),if(amount==null)null else LocalDate.parse(d.date),optional(d.weight),optional(d.tariff),optional(d.rate),d.note,d.revision))
            draft.update { it.copy(revision=d.revision+1) };dirty.value=false;onDone()
        } catch(e:Exception) { message.value=e.message ?: "Не удалось сохранить счёт" } finally { busy.value=false }
    } }
    fun pay(command:UUID,party:DeliveryParty,amount:String,date:String,note:String,onDone:()->Unit) {
        if(busy.value)return;busy.value=true;viewModelScope.launch { try {
            val value=requireNotNull(amount.parseDecimal()) { "Укажите сумму" };require(value.stripTrailingZeros().scale()<=2) { "Оплата — с точностью до копеек" }
            repo.addDeliveryPayment(DeliveryPayment(command,id,party,Money.fromRubles(value),LocalDate.parse(date),note));onDone()
        } catch(e:Exception) { message.value=e.message ?: "Не удалось записать оплату" } finally { busy.value=false } }
    }
    fun reverse(payment:DeliveryPayment) { if(busy.value)return;busy.value=true;viewModelScope.launch {try {repo.reverseDeliveryPayment(payment.id,UUID.randomUUID())}catch(e:Exception){message.value=e.message}finally{busy.value=false}} }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryScreen(repo:OrdersRepository,orderId:UUID,onBack:()->Unit) {
    val model=viewModel<DeliveryViewModel>(key="delivery-$orderId",factory=viewModelFactory { initializer { DeliveryViewModel(repo,orderId) } })
    val details by model.details.collectAsState();val draft by model.draft.collectAsState();val dirty by model.dirty.collectAsState();val busy by model.busy.collectAsState();val message by model.message.collectAsState()
    var showParameters by rememberSaveable { mutableStateOf(false) }
    var party by remember { mutableStateOf<DeliveryParty?>(null) }
    var paymentId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var amount by rememberSaveable { mutableStateOf("") };var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) };var note by rememberSaveable { mutableStateOf("") }
    var reverse by remember { mutableStateOf<DeliveryPayment?>(null) };var leaving by remember { mutableStateOf(false) }
    fun back() { if(dirty)leaving=true else onBack() }
    BackHandler { back() }
    Scaffold(topBar={TopAppBar(title={Text("Доставка заказа")},navigationIcon={IconButton(onClick={back()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}},actions={TextButton(onClick={model.save()},enabled=!busy && dirty){Text("Сохранить")}})}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val data=details
            if(data==null)item{Text("Загрузка…")} else {
                item {
                    Text("Прогноз и факт",style=MaterialTheme.typography.titleLarge)
                    Text("Ориентир: ${data.planned?.format() ?: "не заполнен для всех позиций"}")
                    Text("Счёт логиста: ${data.invoice.amount?.format() ?: "не получен"}",fontWeight=FontWeight.SemiBold)
                    Text("Отклонение от ориентира: ${data.difference?.format() ?: "—"}")
                    Text("Эти суммы не меняют прибыль товара и расчётную группу.",style=MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("Счёт по приходу груза",style=MaterialTheme.typography.titleMedium)
                    AppTextField(draft.amount,{v->model.update{it.copy(amount=v)}},"Фактическая доставка по счёту, ₽",decimal=true)
                    AppTextField(draft.date,{v->model.update{it.copy(date=v)}},"Дата счёта · ГГГГ-ММ-ДД")
                    AppTextField(draft.note,{v->model.update{it.copy(note=v)}},"Логист / номер счёта / комментарий")
                    TextButton(onClick={showParameters=!showParameters}){Text(if(showParameters)"Скрыть параметры факта" else "Фактический вес, тариф и курс")}
                    if(showParameters){
                        AppTextField(draft.weight,{v->model.update{it.copy(weight=v)}},"Фактический вес заказа, кг",decimal=true)
                        AppTextField(draft.tariff,{v->model.update{it.copy(tariff=v)}},"Фактический тариф, CNY/кг",decimal=true)
                        AppTextField(draft.rate,{v->model.update{it.copy(rate=v)}},"Фактический курс RUB за 1 CNY",decimal=true)
                        TextButton(onClick=model::calculate){Text("Подставить расчёт в сумму счёта")}
                        Text("Окончательной считается введённая сумма счёта. Прогноз не перезаписывается.",style=MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("Расчёты",style=MaterialTheme.typography.titleMedium)
                    Text("Получено от клиента: ${data.clientPaid.format()}")
                    Text(data.clientBalance?.let { if(it<Money.ZERO) "Переплата клиента: ${(Money.ZERO-it).format()}" else "Получить от клиента: ${it.format()}" } ?: "Долг клиента: ждём счёт")
                    Text("Оплачено логисту: ${data.logisticsPaid.format()}")
                    Text(data.logisticsBalance?.let { if(it<Money.ZERO) "Аванс логисту: ${(Money.ZERO-it).format()}" else "Оплатить логисту: ${it.format()}" } ?: "Долг логисту: ждём счёт")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={party=DeliveryParty.CLIENT},enabled=!busy,modifier=Modifier.weight(1f)){Text("Получено")}
                        OutlinedButton(onClick={party=DeliveryParty.LOGISTICS},enabled=!busy,modifier=Modifier.weight(1f)){Text("Логисту")}
                    }
                    Text("Кнопки фиксируют уже состоявшуюся оплату; деньги не переводят.",style=MaterialTheme.typography.bodySmall)
                }
                item { Text("Журнал оплат",style=MaterialTheme.typography.titleMedium) }
                items(data.payments,key={it.id}) { payment ->
                    Column {
                        Text("${payment.date} · ${if(payment.party==DeliveryParty.CLIENT)"От клиента" else "Логисту"} · ${payment.amount.format()}",fontWeight=FontWeight.Medium)
                        if(payment.note.isNotBlank())Text(payment.note,style=MaterialTheme.typography.bodySmall)
                        if(payment.reversalOf==null && data.payments.none {it.reversalOf==payment.id})TextButton(onClick={reverse=payment},enabled=!busy){Text("Отменить запись")}
                        if(payment.reversalOf!=null)Text("Сторно предыдущей записи",style=MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    party?.let { selected -> AlertDialog(onDismissRequest={if(!busy)party=null},title={Text(if(selected==DeliveryParty.CLIENT)"Получено от клиента" else "Оплачено логисту")},text={Column {
        AppTextField(amount,{amount=it},"Сумма, ₽",decimal=true);AppTextField(date,{date=it},"Дата · ГГГГ-ММ-ДД");AppTextField(note,{note=it},"Комментарий")
    }},confirmButton={TextButton(onClick={model.pay(UUID.fromString(paymentId),selected,amount,date,note){party=null;amount="";note="";paymentId=UUID.randomUUID().toString()}},enabled=!busy){Text("Записать")}},dismissButton={TextButton(onClick={party=null},enabled=!busy){Text("Отмена")}}) }
    reverse?.let { p -> AlertDialog(onDismissRequest={reverse=null},title={Text("Отменить запись оплаты?")},text={Text("Будет добавлено сторно. История сохранится. Реального возврата денег это не выполняет.")},confirmButton={TextButton(onClick={model.reverse(p);reverse=null}){Text("Добавить сторно")}},dismissButton={TextButton(onClick={reverse=null}){Text("Отмена")}}) }
    if(leaving)AlertDialog(onDismissRequest={leaving=false},title={Text("Счёт не сохранён")},confirmButton={TextButton(onClick={model.save(onBack)}){Text("Сохранить и выйти")}},dismissButton={TextButton(onClick={onBack()}){Text("Выйти без сохранения")}})
    message?.let { AlertDialog(onDismissRequest={model.message.value=null},text={Text(it)},confirmButton={TextButton(onClick={model.message.value=null}){Text("Понятно")}}) }
}
