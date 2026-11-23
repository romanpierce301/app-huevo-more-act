package com.example.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.HyhApplication
import com.example.data.local.entity.AbonoEntity
import com.example.data.local.entity.BackupRecordEntity
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.data.local.entity.DailyClosingEntity
import com.example.data.local.entity.DeliveryEntity
import com.example.data.local.entity.EggPurchaseEntity
import com.example.data.local.entity.InventoryBrandEntity
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.SugarInventoryEntity
import com.example.data.local.entity.SugarPurchaseEntity
import com.example.domain.CalculationEngine
import com.example.domain.DeliveryCalculationResult
import com.example.domain.DeliveryModality
import com.example.domain.PaymentMethod
import com.example.domain.WeighedBoxItem
import com.example.system.PairedPrinterDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

data class ClientWithDebt(
    val client: ClientEntity,
    val activeDebtMxn: Double,
    val deliveryCount: Int,
)

data class DeliveryDraftState(
    val clientId: Long? = null,
    val brand: String = "",
    val modality: DeliveryModality = DeliveryModality.CAJA_COMPLETA,
    val weightInputKg: String = "",
    val pricePerKgInput: String = "",
    val weighedItems: List<WeighedBoxItem> = emptyList(),
    val nextBoxSeq: Long = 1L,
    val paymentMethod: PaymentMethod = PaymentMethod.CONTADO,
    val initialAbonoInput: String = "",
    val sugarSacksInput: String = "",
    val sugarWeightPerSackInput: String = "",
    val sugarPricePerSackInput: String = "",
) {
    val hasCapturedData: Boolean
        get() = clientId != null ||
            brand.isNotBlank() ||
            weighedItems.isNotEmpty() ||
            weightInputKg.isNotBlank() ||
            initialAbonoInput.isNotBlank() ||
            sugarSacksInput.isNotBlank() ||
            sugarWeightPerSackInput.isNotBlank() ||
            sugarPricePerSackInput.isNotBlank()
}

sealed interface OverlayState {
    data object None : OverlayState
    data class Loading(val title: String, val message: String) : OverlayState
    data class Success(val title: String, val message: String) : OverlayState
    data class Error(val title: String, val message: String) : OverlayState
}

data class PendingSafSave(
    val file: File,
    val mimeType: String,
    val suggestedFileName: String,
)

class HyhViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HyhApplication
    private val repository = app.repository
    private val soundManager = app.soundManager
    private val printerManager = app.printerManager

    val settings: StateFlow<BusinessSettingsEntity> = repository.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BusinessSettingsEntity())

    val rawClients: StateFlow<List<ClientEntity>> = repository.observeClients()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deliveries: StateFlow<List<DeliveryEntity>> = repository.observeDeliveries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val clientsWithDebt: StateFlow<List<ClientWithDebt>> = combine(rawClients, deliveries) { clients, allDeliveries ->
        val grouped = allDeliveries.groupBy { it.clientId }
        clients.map { client ->
            val clientDeliveries = grouped[client.id].orEmpty()
            val activeDebt = clientDeliveries
                .filter { it.status == "ACTIVA" }
                .fold(BigDecimal.ZERO) { acc, d -> acc.add(BigDecimal.valueOf(d.pendingBalanceMxn)) }
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
            ClientWithDebt(
                client = client,
                activeDebtMxn = activeDebt,
                deliveryCount = clientDeliveries.size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val purchases: StateFlow<List<EggPurchaseEntity>> = repository.observePurchases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inventoryBrands: StateFlow<List<InventoryBrandEntity>> = repository.observeInventoryBrands()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sugarPurchases: StateFlow<List<SugarPurchaseEntity>> = repository.observeSugarPurchases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sugarInventory: StateFlow<SugarInventoryEntity> = repository.observeSugarInventory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SugarInventoryEntity())

    val activeAbonos: StateFlow<List<AbonoEntity>> = repository.observeActiveAbonos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ReminderEntity>> = repository.observeReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyClosings: StateFlow<List<DailyClosingEntity>> = repository.observeDailyClosings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backups: StateFlow<List<BackupRecordEntity>> = repository.observeBackups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _overlayState = MutableStateFlow<OverlayState>(OverlayState.None)
    val overlayState: StateFlow<OverlayState> = _overlayState.asStateFlow()

    private val _deliveryDraft = MutableStateFlow(DeliveryDraftState())
    val deliveryDraft: StateFlow<DeliveryDraftState> = _deliveryDraft.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    private val _pendingSafSave = MutableStateFlow<PendingSafSave?>(null)
    val pendingSafSave: StateFlow<PendingSafSave?> = _pendingSafSave.asStateFlow()

    private val _pairedPrinters = MutableStateFlow<List<PairedPrinterDevice>>(emptyList())
    val pairedPrinters: StateFlow<List<PairedPrinterDevice>> = _pairedPrinters.asStateFlow()

    private val _dueTodayBannerReminders = MutableStateFlow<List<ReminderEntity>>(emptyList())
    val dueTodayBannerReminders: StateFlow<List<ReminderEntity>> = _dueTodayBannerReminders.asStateFlow()

    private var startupReminderSoundPlayed = false

    init {
        viewModelScope.launch {
            reminders.collect { list ->
                val endOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                val due = list.filter { !it.isCompleted && it.deliveryDateMillis <= endOfToday }
                _dueTodayBannerReminders.value = due
                if (due.isNotEmpty() && !startupReminderSoundPlayed) {
                    startupReminderSoundPlayed = true
                    soundManager.playNotificationSound()
                }
            }
        }
    }

    fun clearFeedback() {
        _feedbackMessage.value = null
    }

    fun dismissOverlay() {
        _overlayState.value = OverlayState.None
    }

    fun showFeedback(msg: String) {
        _feedbackMessage.value = msg
    }

    // --- Overlays operacionales (Sección 11) ---
    fun runDeliveriesHistoryLoading() {
        if (_overlayState.value is OverlayState.Loading) return
        viewModelScope.launch {
            try {
                _overlayState.value = OverlayState.Loading(
                    title = "Cargando entregas",
                    message = "Recuperando historial local de notas y folios...",
                )
                delay(2400)
            } finally {
                if (_overlayState.value is OverlayState.Loading) {
                    _overlayState.value = OverlayState.None
                }
            }
        }
    }

    fun openTicketWithLoading(onReady: () -> Unit) {
        if (_overlayState.value is OverlayState.Loading) return
        viewModelScope.launch {
            var completed = false
            try {
                _overlayState.value = OverlayState.Loading(
                    title = "Cargando ticket",
                    message = "Preparando comprobante térmico de 60 mm...",
                )
                delay(2200)
                completed = true
            } finally {
                if (_overlayState.value is OverlayState.Loading) {
                    _overlayState.value = OverlayState.None
                }
            }
            if (completed) {
                onReady()
            }
        }
    }

    // --- Clientes ---
    fun saveClient(
        id: Long = 0,
        name: String,
        phone: String,
        address: String,
        specialPriceText: String,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val specialPrice = specialPriceText.trim().takeIf { it.isNotEmpty() }?.let {
                    it.replace(",", ".").toDoubleOrNull()
                        ?: throw IllegalArgumentException("Precio especial inválido")
                }
                repository.saveClient(id, name, phone, address, specialPrice)
            }.onSuccess {
                _feedbackMessage.value = if (id > 0) "Cliente actualizado" else "Cliente registrado"
                onSaved()
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al guardar cliente"
            }
        }
    }

    fun updateClientSpecialPrice(clientId: Long, specialPriceText: String) {
        viewModelScope.launch {
            runCatching {
                val parsed = specialPriceText.trim().takeIf { it.isNotEmpty() }?.let {
                    it.replace(",", ".").toDoubleOrNull()
                        ?: throw IllegalArgumentException("Precio especial inválido")
                }
                repository.updateClientSpecialPrice(clientId, parsed)
            }.onSuccess {
                _feedbackMessage.value = "Tarifa del cliente actualizada"
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al actualizar tarifa"
            }
        }
    }

    fun deleteClient(client: ClientEntity) {
        viewModelScope.launch {
            repository.deleteClientIfAllowed(client)
                .onSuccess { _feedbackMessage.value = "Cliente eliminado" }
                .onFailure { _feedbackMessage.value = it.message ?: "No se pudo eliminar el cliente" }
        }
    }

    // --- Compras de huevo ---
    fun registerPurchase(
        purchaseDateMillis: Long,
        boxesText: String,
        brand: String,
        grossWeightText: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val boxes = CalculationEngine.parsePositiveDecimal(boxesText).toDouble()
                val gross = CalculationEngine.parsePositiveDecimal(grossWeightText).toDouble()
                repository.registerPurchase(purchaseDateMillis, boxes, brand, gross)
            }.onSuccess {
                _feedbackMessage.value = "Compra registrada e inventario actualizado"
                onSuccess()
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Datos inválidos en la compra"
            }
        }
    }

    fun registerSugarPurchase(
        purchaseDateMillis: Long,
        sacksText: String,
        totalWeightText: String,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val sacks = CalculationEngine.parsePositiveDecimal(sacksText).toDouble()
                val totalWeight = CalculationEngine.parsePositiveDecimal(totalWeightText).toDouble()
                repository.registerSugarPurchase(purchaseDateMillis, sacks, totalWeight)
            }.onSuccess {
                _feedbackMessage.value = "Compra de azúcar registrada e inventario actualizado"
                onSuccess()
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Datos inválidos en la compra de azúcar"
            }
        }
    }

    // --- Nueva entrega y flujo de pago ---
    fun startDeliveryForClient(clientId: Long?) {
        val selectedClient = clientId?.let { id -> rawClients.value.firstOrNull { it.id == id } }
        val basePrice = selectedClient?.specialPricePerKg ?: settings.value.globalPricePerKg
        _deliveryDraft.value = DeliveryDraftState(
            clientId = clientId,
            pricePerKgInput = CalculationEngine.formatPlainDecimal(basePrice),
        )
    }

    fun clearDeliveryDraft() {
        _deliveryDraft.value = DeliveryDraftState()
    }

    fun selectDraftClient(clientId: Long) {
        val selectedClient = rawClients.value.firstOrNull { it.id == clientId }
        _deliveryDraft.update { current ->
            val previousClient = current.clientId?.let { id ->
                rawClients.value.firstOrNull { it.id == id }
            }
            val special = selectedClient?.specialPricePerKg
            val nextPrice = when {
                special != null && special > 0.0 ->
                    CalculationEngine.formatPlainDecimal(special)
                previousClient?.specialPricePerKg != null && settings.value.globalPricePerKg > 0.0 ->
                    CalculationEngine.formatPlainDecimal(settings.value.globalPricePerKg)
                current.pricePerKgInput.isBlank() && settings.value.globalPricePerKg > 0.0 ->
                    CalculationEngine.formatPlainDecimal(settings.value.globalPricePerKg)
                else -> current.pricePerKgInput
            }
            current.copy(clientId = clientId, pricePerKgInput = nextPrice)
        }
    }

    fun updateDraftBrand(brand: String) {
        _deliveryDraft.update { it.copy(brand = brand) }
    }

    fun updateDraftModality(modality: DeliveryModality) {
        _deliveryDraft.update { it.copy(modality = modality) }
    }

    fun updateDraftWeight(weight: String) {
        _deliveryDraft.update { it.copy(weightInputKg = weight) }
    }

    fun updateDraftPricePerKg(price: String) {
        _deliveryDraft.update { it.copy(pricePerKgInput = price) }
    }

    fun addWeighedBoxItem() {
        val current = _deliveryDraft.value
        val priceBd = CalculationEngine.parsePositiveDecimal(current.pricePerKgInput)
        if (priceBd <= BigDecimal.ZERO) {
            _feedbackMessage.value = "Ingresa el precio por kilogramo antes de agregar"
            return
        }
        val itemBrand = current.brand.trim()
            .ifBlank { inventoryBrands.value.firstOrNull()?.brand ?: "General" }
        CalculationEngine.calculateSingleBoxItem(
            id = current.nextBoxSeq,
            modality = current.modality,
            weightInputKg = current.weightInputKg,
            brand = itemBrand,
            pricePerKg = priceBd,
        ).onSuccess { item ->
            _deliveryDraft.update {
                it.copy(
                    weighedItems = it.weighedItems + item,
                    weightInputKg = "",
                    nextBoxSeq = it.nextBoxSeq + 1L,
                )
            }
        }.onFailure { err ->
            _feedbackMessage.value = err.message ?: "Ingresa un peso válido para agregar"
        }
    }

    fun removeWeighedBoxItem(itemId: Long) {
        _deliveryDraft.update { current ->
            current.copy(weighedItems = current.weighedItems.filterNot { it.id == itemId })
        }
    }

    fun updateDraftPaymentMethod(method: PaymentMethod) {
        _deliveryDraft.update { it.copy(paymentMethod = method, initialAbonoInput = if (method == PaymentMethod.CONTADO) "" else it.initialAbonoInput) }
    }

    fun updateDraftInitialAbono(abono: String) {
        _deliveryDraft.update { it.copy(initialAbonoInput = abono) }
    }

    fun updateDraftSugarSacks(sacks: String) {
        _deliveryDraft.update { it.copy(sugarSacksInput = sacks) }
    }

    fun updateDraftSugarWeightPerSack(weight: String) {
        _deliveryDraft.update { it.copy(sugarWeightPerSackInput = weight) }
    }

    fun updateDraftSugarPricePerSack(price: String) {
        _deliveryDraft.update { it.copy(sugarPricePerSackInput = price) }
    }

    fun computeDraftCalculation(
        draft: DeliveryDraftState,
        @Suppress("UNUSED_PARAMETER") settings: BusinessSettingsEntity,
        @Suppress("UNUSED_PARAMETER") clients: List<ClientWithDebt>,
    ): DeliveryCalculationResult {
        val priceBd = CalculationEngine.parsePositiveDecimal(draft.pricePerKgInput)
        val sugarSummary = CalculationEngine.calculateSugarSummary(
            sacksInput = draft.sugarSacksInput,
            weightPerSackInput = draft.sugarWeightPerSackInput,
            pricePerSackInput = draft.sugarPricePerSackInput,
        )
        return CalculationEngine.calculateDeliveryWithSugar(
            items = draft.weighedItems,
            pricePerKg = priceBd,
            sugarSummary = sugarSummary,
        )
    }

    fun finalizeDelivery(
        signatureBitmap: Bitmap?,
        onCompleted: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val draft = _deliveryDraft.value
            val clientId = draft.clientId
            val selectedClient = clientId?.let { repository.getClientOnce(it) }
            if (selectedClient == null) {
                _feedbackMessage.value = "Seleccione un cliente antes de continuar"
                return@launch
            }
            val priceBd = CalculationEngine.parsePositiveDecimal(draft.pricePerKgInput)
            val sugarSummary = CalculationEngine.calculateSugarSummary(
                sacksInput = draft.sugarSacksInput,
                weightPerSackInput = draft.sugarWeightPerSackInput,
                pricePerSackInput = draft.sugarPricePerSackInput,
            )
            if (sugarSummary.isPartialError) {
                _feedbackMessage.value = "Completa bultos, peso y precio del azúcar (o deja los campos vacíos)"
                return@launch
            }
            if (draft.weighedItems.isEmpty() && !sugarSummary.isPresent) {
                _feedbackMessage.value = "Agrega al menos una caja (+) o bultos de azúcar"
                return@launch
            }
            val calc = CalculationEngine.calculateDeliveryWithSugar(
                items = draft.weighedItems,
                pricePerKg = priceBd,
                sugarSummary = sugarSummary,
            )
            if (!calc.isValid) {
                _feedbackMessage.value = if (draft.weighedItems.isNotEmpty() && priceBd <= BigDecimal.ZERO) {
                    "Ingresa el precio por kilogramo en Nueva Entrega"
                } else {
                    "Verifique cajas pesadas, bultos de azúcar y precios"
                }
                return@launch
            }
            if (draft.paymentMethod == PaymentMethod.CREDITO && signatureBitmap == null) {
                _feedbackMessage.value = "La firma del cliente es obligatoria para entregas a crédito"
                return@launch
            }

            val effectivePrice = if (draft.weighedItems.isEmpty()) {
                0.0
            } else if (calc.pricePerKg > BigDecimal.ZERO) {
                calc.pricePerKg.toDouble()
            } else {
                priceBd.toDouble()
            }
            val finalMxn = calc.finalAmountMxn.toDouble()
            val initialAbono = if (draft.paymentMethod == PaymentMethod.CONTADO) {
                finalMxn
            } else {
                CalculationEngine.parsePositiveDecimal(draft.initialAbonoInput)
                    .toDouble()
                    .coerceIn(0.0, finalMxn)
            }
            val resolvedBrand = if (draft.weighedItems.isNotEmpty()) {
                CalculationEngine.resolveBrandSummaryLabel(
                    fallbackBrand = draft.brand.ifBlank { repository.getDefaultBrandOnce() },
                    items = draft.weighedItems,
                )
            } else {
                "Azúcar"
            }
            val distinctMods = draft.weighedItems.map { it.modality }.distinct()
            val primaryModality = distinctMods.firstOrNull() ?: draft.modality

            runCatching {
                repository.registerDelivery(
                    clientId = selectedClient.id,
                    modality = primaryModality,
                    brand = resolvedBrand,
                    boxes = calc.boxesDecimal.toDouble(),
                    grossWeightKg = calc.inputWeightKg.toDouble(),
                    tareDeductionKg = calc.tareDeductionKg.toDouble(),
                    netWeightKg = calc.netWeightKg.toDouble(),
                    pricePerKg = effectivePrice,
                    rawAmountMxn = calc.rawAmountMxn.toDouble(),
                    finalAmountMxn = finalMxn,
                    paymentMethod = draft.paymentMethod,
                    initialPaymentMxn = initialAbono,
                    signatureBitmap = signatureBitmap,
                    weighedItems = draft.weighedItems,
                    sugarSacks = if (sugarSummary.isPresent) sugarSummary.sacks.toDouble() else 0.0,
                    sugarSackWeightKg = if (sugarSummary.isPresent) sugarSummary.weightPerSackKg.toDouble() else 0.0,
                    sugarPricePerSackMxn = if (sugarSummary.isPresent) sugarSummary.pricePerSackMxn.toDouble() else 0.0,
                    sugarSubtotalMxn = if (sugarSummary.isPresent) sugarSummary.subtotalMxn.toDouble() else 0.0,
                )
            }.onSuccess { deliveryId ->
                soundManager.playPaymentSuccessSound()
                _deliveryDraft.value = DeliveryDraftState()
                _overlayState.value = OverlayState.Success(
                    title = "Entrega registrada",
                    message = "El ticket y los saldos locales se guardaron correctamente.",
                )
                delay(2600)
                _overlayState.value = OverlayState.None
                onCompleted(deliveryId)
            }.onFailure { err ->
                _feedbackMessage.value = err.message ?: "No se pudo registrar la entrega"
            }
        }
    }

    fun recordAbono(
        clientId: Long,
        deliveryId: Long,
        amountText: String,
        note: String,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val amount = CalculationEngine.parsePositiveDecimal(amountText).toDouble()
                repository.recordAbono(clientId, deliveryId, amount, note)
            }.onSuccess {
                soundManager.playPaymentSuccessSound()
                _feedbackMessage.value = "Abono registrado correctamente"
                onDone()
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al registrar abono"
            }
        }
    }

    fun cancelDelivery(deliveryId: Long, reason: String) {
        viewModelScope.launch {
            runCatching {
                repository.cancelDelivery(deliveryId, reason)
            }.onSuccess {
                soundManager.playCancellationSound()
                _overlayState.value = OverlayState.Error(
                    title = "Entrega cancelada",
                    message = "Se conservó el folio y se revirtieron inventario y saldos.",
                )
                delay(2400)
                _overlayState.value = OverlayState.None
            }.onFailure {
                _feedbackMessage.value = it.message ?: "No se pudo cancelar la entrega"
            }
        }
    }

    // --- Recordatorios ---
    fun saveReminder(
        id: Long = 0,
        clientId: Long,
        clientName: String,
        deliveryDateMillis: Long,
        boxesNote: String,
        note: String,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                repository.saveReminder(id, clientId, clientName, deliveryDateMillis, boxesNote, note)
            }.onSuccess {
                soundManager.playNotificationSound()
                _feedbackMessage.value = "Recordatorio guardado y programado"
                onSaved()
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al guardar recordatorio"
            }
        }
    }

    fun toggleReminderCompleted(reminder: ReminderEntity) {
        viewModelScope.launch {
            repository.toggleReminderCompleted(reminder)
            _feedbackMessage.value = if (!reminder.isCompleted) {
                "Recordatorio marcado como completado"
            } else {
                "Recordatorio reactivado"
            }
        }
    }

    fun deleteReminder(reminder: ReminderEntity) {
        viewModelScope.launch {
            repository.deleteReminder(reminder)
            _feedbackMessage.value = "Recordatorio eliminado"
        }
    }

    // --- Cierres diarios ---
    fun generateDailyClosing() {
        viewModelScope.launch {
            runCatching {
                repository.generateDailyClosing()
            }.onSuccess { closing ->
                _overlayState.value = OverlayState.Success(
                    title = "Cierre diario completado",
                    message = "Folio ${closing.folio} guardado en PDF y JSON.",
                )
                delay(2600)
                _overlayState.value = OverlayState.None
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al generar cierre diario"
            }
        }
    }

    // --- Documentos, WhatsApp, Descargas e Impresión ---
    fun printDeliveryTicket(delivery: DeliveryEntity) {
        viewModelScope.launch {
            val currentSettings = repository.getSettingsOnce()
            val client = repository.getClientOnce(delivery.clientId)
            printerManager.printDeliveryTicket(delivery, currentSettings, client)
                .onSuccess { msg -> _feedbackMessage.value = msg }
                .onFailure { err -> _feedbackMessage.value = err.message ?: "Error de impresión" }
        }
    }

    fun shareDeliveryPdfWhatsApp(delivery: DeliveryEntity) {
        viewModelScope.launch {
            runCatching {
                val pdfFile = repository.generateTicketPdf(delivery)
                val uri = FileProvider.getUriForFile(
                    app,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    pdfFile,
                )
                val waIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Comprobante H y H HUEVOS • Folio ${delivery.folio}",
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.whatsapp")
                }
                try {
                    app.startActivity(waIntent)
                } catch (_: ActivityNotFoundException) {
                    val chooser = Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Compartir comprobante PDF",
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(chooser)
                }
            }.onFailure {
                _feedbackMessage.value = "No se pudo compartir el PDF: ${it.message}"
            }
        }
    }

    fun downloadDeliveryPdf(delivery: DeliveryEntity) {
        viewModelScope.launch {
            runCatching {
                repository.generateTicketPdf(delivery)
            }.onSuccess { file ->
                saveOrRequestSaf(file, "application/pdf", file.name, "HYH_Huevos")
            }.onFailure {
                _feedbackMessage.value = "Error al generar PDF: ${it.message}"
            }
        }
    }

    fun downloadDeliveryJson(delivery: DeliveryEntity) {
        viewModelScope.launch {
            runCatching {
                repository.generateTicketJson(delivery)
            }.onSuccess { file ->
                saveOrRequestSaf(file, "application/json", file.name, "HYH_Huevos")
            }.onFailure {
                _feedbackMessage.value = "Error al generar JSON: ${it.message}"
            }
        }
    }

    fun downloadClosingPdf(closing: DailyClosingEntity) {
        viewModelScope.launch {
            val file = File(closing.pdfPath)
            if (file.exists()) {
                saveOrRequestSaf(file, "application/pdf", file.name, "HYH_Cierres")
            } else {
                _feedbackMessage.value = "Archivo PDF del cierre no encontrado"
            }
        }
    }

    fun downloadClosingJson(closing: DailyClosingEntity) {
        viewModelScope.launch {
            val file = File(closing.jsonPath)
            if (file.exists()) {
                saveOrRequestSaf(file, "application/json", file.name, "HYH_Cierres")
            } else {
                _feedbackMessage.value = "Archivo JSON del cierre no encontrado"
            }
        }
    }

    fun createFullBackupZip() {
        viewModelScope.launch {
            runCatching {
                repository.createFullBackupZip()
            }.onSuccess { record ->
                saveOrRequestSaf(File(record.filePath), "application/zip", record.fileName, "HYH_Backups")
            }.onFailure {
                _feedbackMessage.value = "Error al crear respaldo ZIP: ${it.message}"
            }
        }
    }

    fun exportFullJsonBackup() {
        viewModelScope.launch {
            runCatching {
                repository.exportFullJsonBackup()
            }.onSuccess { record ->
                saveOrRequestSaf(File(record.filePath), "application/json", record.fileName, "HYH_Backups")
            }.onFailure {
                _feedbackMessage.value = "Error al exportar JSON: ${it.message}"
            }
        }
    }

    fun saveExistingBackupToDownloads(record: BackupRecordEntity) {
        viewModelScope.launch {
            val mime = if (record.format.equals("ZIP", ignoreCase = true)) "application/zip" else "application/json"
            saveOrRequestSaf(File(record.filePath), mime, record.fileName, "HYH_Backups")
        }
    }

    fun restoreFromUri(uri: Uri) {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _overlayState.value = OverlayState.Loading("Restaurando respaldo", "Validando datos locales...")
            val result = repository.restoreBackupFromUri(uri)
            val remaining = (2000L - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            _overlayState.value = OverlayState.None
            result.onSuccess { msg ->
                _feedbackMessage.value = msg
            }.onFailure { err ->
                _feedbackMessage.value = err.message ?: "El respaldo es inválido o está dañado"
            }
        }
    }

    fun restoreFromRecord(record: BackupRecordEntity) {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _overlayState.value = OverlayState.Loading("Restaurando respaldo", "Recuperando base de datos...")
            val result = repository.restoreFromLocalBackup(record)
            val remaining = (2000L - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            _overlayState.value = OverlayState.None
            result.onSuccess { msg ->
                _feedbackMessage.value = msg
            }.onFailure { err ->
                _feedbackMessage.value = err.message ?: "No se pudo restaurar el respaldo"
            }
        }
    }

    private suspend fun saveOrRequestSaf(
        file: File,
        mimeType: String,
        suggestedName: String,
        subFolder: String,
    ) {
        val result = repository.saveFileToPublicDownloads(file, mimeType, subFolder)
        result.onSuccess { msg ->
            _feedbackMessage.value = msg
        }.onFailure { err ->
            if (err is UnsupportedOperationException) {
                _pendingSafSave.value = PendingSafSave(file, mimeType, suggestedName)
            } else {
                _feedbackMessage.value = err.message ?: "No se pudo guardar el archivo"
            }
        }
    }

    fun completePendingSafSave(destUri: Uri?) {
        val pending = _pendingSafSave.value ?: return
        _pendingSafSave.value = null
        if (destUri == null) return
        viewModelScope.launch {
            runCatching {
                app.contentResolver.openOutputStream(destUri)?.use { out ->
                    pending.file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("No se pudo abrir destino")
            }.onSuccess {
                _feedbackMessage.value = "Archivo guardado: ${pending.suggestedFileName}"
            }.onFailure {
                _feedbackMessage.value = "Error al guardar archivo: ${it.message}"
            }
        }
    }

    // --- Configuración y periféricos ---
    fun saveBusinessConfig(phone: String, address: String, slogan: String, globalPriceText: String) {
        viewModelScope.launch {
            runCatching {
                val price = CalculationEngine.parsePositiveDecimal(globalPriceText).toDouble()
                repository.updateBusinessSettings(phone, address, slogan, price)
            }.onSuccess {
                _feedbackMessage.value = "Configuración guardada"
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error en configuración"
            }
        }
    }

    fun refreshPrinters() {
        val bt = printerManager.getPairedBluetoothPrinters()
        val usb = printerManager.getConnectedUsbPrinters()
        _pairedPrinters.value = bt + usb
    }

    fun selectPrinter(device: PairedPrinterDevice) {
        viewModelScope.launch {
            repository.updatePrinterSettings(device.type, device.name, device.address)
            _feedbackMessage.value = "Impresora seleccionada: ${device.name}"
        }
    }

    fun selectPrinterType(type: String) {
        viewModelScope.launch {
            val current = repository.getSettingsOnce()
            repository.updatePrinterSettings(type, current.printerName, current.printerAddress)
        }
    }

    fun testPrinter() {
        viewModelScope.launch {
            val current = repository.getSettingsOnce()
            printerManager.printTestTicket(current)
                .onSuccess { _feedbackMessage.value = it }
                .onFailure { _feedbackMessage.value = it.message ?: "Error al probar impresora" }
        }
    }

    fun saveCreditLegendConfig(
        creditTermDaysText: String,
        moratoriumInterestText: String,
        paymentPlace: String,
        legendExtra: String,
        legendClosing: String,
    ) {
        viewModelScope.launch {
            runCatching {
                val cleanDays = creditTermDaysText.trim()
                val days = cleanDays.toIntOrNull()
                    ?: throw IllegalArgumentException("Ingresa un número válido de días límite")
                val interestClean = moratoriumInterestText.trim().replace(",", ".")
                val interest = if (interestClean.isEmpty()) {
                    0.0
                } else {
                    interestClean.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Interés moratorio inválido")
                }
                repository.updateCreditLegendSettings(
                    creditTermDays = days,
                    moratoriumInterestPercent = interest,
                    paymentPlace = paymentPlace,
                    legendExtra = legendExtra,
                    legendClosing = legendClosing,
                )
            }.onSuccess {
                _feedbackMessage.value = "Configuración de crédito y leyenda guardada"
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al guardar configuración de crédito"
            }
        }
    }

    fun updateClientCreditTermDays(clientId: Long, daysText: String) {
        viewModelScope.launch {
            runCatching {
                val parsed = daysText.trim().takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull()
                        ?: throw IllegalArgumentException("Plazo límite de días inválido")
                }
                repository.updateClientCreditTermDays(clientId, parsed)
            }.onSuccess {
                _feedbackMessage.value = "Plazo de crédito del cliente actualizado"
            }.onFailure {
                _feedbackMessage.value = it.message ?: "Error al actualizar plazo del cliente"
            }
        }
    }
}
