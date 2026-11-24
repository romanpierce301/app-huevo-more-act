package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import com.example.R
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.data.local.entity.DeliveryEntity
import com.example.domain.CalculationEngine
import com.example.domain.DeliveryModality
import com.example.domain.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PairedPrinterDevice(
    val name: String,
    val address: String,
    val type: String, // "BLUETOOTH" or "USB"
    val isPaired: Boolean = true,
    val isPrinterLikely: Boolean = false,
)

data class BluetoothDiscoveryState(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val pairingAddress: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val needsLocationToggleHint: Boolean = false,
)

class ThermalPrinterManager(private val context: Context) {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val USB_PERMISSION_ACTION = "com.aistudio.hyhhuevos.qmzpkt.USB_PERMISSION"
        private const val LINE_WIDTH_CHARS = 38
        private val PRINTER_KEYWORDS = listOf(
            "print", "impres", "pos", "mtp", "rpp", "pt-", "zj-", "btp",
            "xprinter", "gprinter", "epson", "star", "bixolon", "hoin",
            "sunmi", "thermal", "termica", "58", "60", "80", "mpt", "rp-",
        )
    }

    private val discoveredBluetoothMap = ConcurrentHashMap<String, PairedPrinterDevice>()
    private val receiverLock = Any()

    @Volatile
    private var isReceiverRegistered = false

    @Volatile
    private var pendingPairAddress: String? = null

    @Volatile
    private var pendingPairSuccess: ((PairedPrinterDevice) -> Unit)? = null

    @Volatile
    private var pendingPairFailure: ((String) -> Unit)? = null

    @Volatile
    private var activeLeScanner: BluetoothLeScanner? = null

    @Volatile
    private var activeLeCallback: ScanCallback? = null

    private val _discoveryState = MutableStateFlow(
        BluetoothDiscoveryState(
            isBluetoothSupported = bluetoothAdapter() != null,
            isBluetoothEnabled = bluetoothAdapter()?.isEnabled == true,
        ),
    )
    val discoveryState: StateFlow<BluetoothDiscoveryState> = _discoveryState.asStateFlow()

    private val _availablePrinters = MutableStateFlow<List<PairedPrinterDevice>>(emptyList())
    val availablePrinters: StateFlow<List<PairedPrinterDevice>> = _availablePrinters.asStateFlow()

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = state == BluetoothAdapter.STATE_ON
                    _discoveryState.update {
                        it.copy(
                            isBluetoothEnabled = enabled,
                            isScanning = if (!enabled) false else it.isScanning,
                            pairingAddress = if (!enabled) null else it.pairingAddress,
                            statusMessage = if (!enabled) null else it.statusMessage,
                        )
                    }
                    if (enabled) {
                        refreshKnownPrinters()
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _discoveryState.update {
                        it.copy(
                            isScanning = true,
                            errorMessage = null,
                            needsLocationToggleHint = false,
                            statusMessage = "Buscando impresoras Bluetooth cercanas...",
                        )
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    ) ?: return
                    val address = device.address?.takeIf { it.isNotBlank() } ?: return
                    val rawName = try {
                        if (hasBluetoothPermission()) device.name?.trim().orEmpty() else ""
                    } catch (_: SecurityException) {
                        ""
                    }
                    val bonded = try {
                        if (hasBluetoothPermission()) device.bondState == BluetoothDevice.BOND_BONDED else false
                    } catch (_: SecurityException) {
                        false
                    }
                    val majorClass = try {
                        if (hasBluetoothPermission()) device.bluetoothClass?.majorDeviceClass else null
                    } catch (_: SecurityException) {
                        null
                    }
                    val existing = discoveredBluetoothMap[address]
                    val resolvedName = when {
                        rawName.isNotBlank() -> rawName
                        existing != null && !existing.name.startsWith("Dispositivo Bluetooth") -> existing.name
                        else -> "Dispositivo Bluetooth (${address.takeLast(5)})"
                    }
                    val likely = isLikelyThermalPrinter(resolvedName, majorClass) || (existing?.isPrinterLikely == true)
                    discoveredBluetoothMap[address] = PairedPrinterDevice(
                        name = resolvedName,
                        address = address,
                        type = "BLUETOOTH",
                        isPaired = bonded,
                        isPrinterLikely = likely,
                    )
                    rebuildAvailablePrinters()
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    val foundNewCount = discoveredBluetoothMap.values.count { !it.isPaired }
                    val needsLocHint = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                        foundNewCount == 0 &&
                        !isLocationEnabledForLegacyScan()
                    _discoveryState.update { current ->
                        if (current.pairingAddress != null) {
                            current.copy(isScanning = false)
                        } else {
                            current.copy(
                                isScanning = false,
                                needsLocationToggleHint = needsLocHint,
                                statusMessage = if (foundNewCount > 0) {
                                    "Se encontraron $foundNewCount dispositivos cercanos. Toca uno para vincularlo."
                                } else {
                                    "Búsqueda finalizada. Si tu impresora nueva no aparece, enciéndela y vuelve a buscar."
                                },
                            )
                        }
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    ) ?: return
                    val address = device.address ?: return
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val devName = try {
                        if (hasBluetoothPermission()) {
                            device.name?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    } catch (_: SecurityException) {
                        null
                    } ?: discoveredBluetoothMap[address]?.name ?: "Impresora Bluetooth"

                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            _discoveryState.update {
                                it.copy(
                                    pairingAddress = address,
                                    errorMessage = null,
                                    statusMessage = "Vinculando con $devName... Si pide PIN, suele ser 0000 o 1234.",
                                )
                            }
                        }

                        BluetoothDevice.BOND_BONDED -> {
                            val paired = PairedPrinterDevice(
                                name = devName,
                                address = address,
                                type = "BLUETOOTH",
                                isPaired = true,
                                isPrinterLikely = true,
                            )
                            discoveredBluetoothMap[address] = paired
                            refreshKnownPrinters()
                            _discoveryState.update {
                                it.copy(
                                    pairingAddress = null,
                                    errorMessage = null,
                                    statusMessage = "¡Impresora vinculada y lista: $devName!",
                                )
                            }
                            if (pendingPairAddress == address) {
                                val cb = pendingPairSuccess
                                clearPendingPairCallbacks()
                                cb?.invoke(paired)
                            }
                        }

                        BluetoothDevice.BOND_NONE -> {
                            if (prevState == BluetoothDevice.BOND_BONDING || pendingPairAddress == address) {
                                val err = "No se pudo vincular con $devName. Verifica que esté encendida o revisa el PIN (0000 / 1234)."
                                _discoveryState.update {
                                    it.copy(
                                        pairingAddress = null,
                                        errorMessage = err,
                                        statusMessage = null,
                                    )
                                }
                                if (pendingPairAddress == address) {
                                    val failCb = pendingPairFailure
                                    clearPendingPairCallbacks()
                                    failCb?.invoke(err)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun isLikelyThermalPrinter(name: String?, majorClass: Int?): Boolean {
        if (majorClass == BluetoothClass.Device.Major.IMAGING) return true
        val lower = name?.lowercase(Locale.ROOT).orEmpty()
        return PRINTER_KEYWORDS.any { lower.contains(it) }
    }

    private fun isLocationEnabledForLegacyScan(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun getRequiredScanPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    }

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasBluetoothScanPermission(): Boolean {
        return getRequiredScanPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothSupported(): Boolean = bluetoothAdapter() != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter()?.isEnabled == true

    private fun ensureReceiverRegistered() {
        synchronized(receiverLock) {
            if (isReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(
                context,
                discoveryReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            isReceiverRegistered = true
        }
    }

    private fun clearPendingPairCallbacks() {
        pendingPairAddress = null
        pendingPairSuccess = null
        pendingPairFailure = null
    }

    private fun rebuildAvailablePrinters() {
        val pairedBt = getPairedBluetoothPrinters()
        val pairedAddresses = pairedBt.map { it.address }.toSet()
        pairedBt.forEach { discoveredBluetoothMap[it.address] = it }

        val discoveredOnly = discoveredBluetoothMap.values
            .filter { it.address !in pairedAddresses && !it.isPaired }
            .sortedWith(
                compareByDescending<PairedPrinterDevice> { it.isPrinterLikely }
                    .thenBy { it.name.startsWith("Dispositivo Bluetooth") }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
        val usb = getConnectedUsbPrinters()
        _availablePrinters.value = pairedBt + usb + discoveredOnly
    }

    fun refreshKnownPrinters() {
        val adapter = bluetoothAdapter()
        _discoveryState.update {
            it.copy(
                isBluetoothSupported = adapter != null,
                isBluetoothEnabled = adapter?.isEnabled == true,
            )
        }
        rebuildAvailablePrinters()
    }

    @SuppressLint("MissingPermission")
    private fun upsertDiscoveredDevice(device: BluetoothDevice, fallbackName: String? = null) {
        val address = device.address?.takeIf { it.isNotBlank() } ?: return
        val rawName = try {
            if (hasBluetoothPermission()) device.name?.trim().orEmpty() else ""
        } catch (_: SecurityException) {
            ""
        }.ifBlank { fallbackName?.trim().orEmpty() }
        val bonded = try {
            if (hasBluetoothPermission()) device.bondState == BluetoothDevice.BOND_BONDED else false
        } catch (_: SecurityException) {
            false
        }
        val majorClass = try {
            if (hasBluetoothPermission()) device.bluetoothClass?.majorDeviceClass else null
        } catch (_: SecurityException) {
            null
        }
        val existing = discoveredBluetoothMap[address]
        val resolvedName = when {
            rawName.isNotBlank() -> rawName
            existing != null && !existing.name.startsWith("Dispositivo Bluetooth") -> existing.name
            else -> "Dispositivo Bluetooth (${address.takeLast(5)})"
        }
        val likely = isLikelyThermalPrinter(resolvedName, majorClass) || (existing?.isPrinterLikely == true)
        discoveredBluetoothMap[address] = PairedPrinterDevice(
            name = resolvedName,
            address = address,
            type = "BLUETOOTH",
            isPaired = bonded,
            isPrinterLikely = likely,
        )
        rebuildAvailablePrinters()
    }

    @SuppressLint("MissingPermission")
    fun toPrinterDevice(device: BluetoothDevice): PairedPrinterDevice {
        val address = device.address ?: ""
        val name = try {
            if (hasBluetoothPermission()) device.name?.takeIf { it.isNotBlank() } else null
        } catch (_: SecurityException) {
            null
        } ?: "Impresora Bluetooth"
        val bonded = try {
            if (hasBluetoothPermission()) device.bondState == BluetoothDevice.BOND_BONDED else false
        } catch (_: SecurityException) {
            false
        }
        val majorClass = try {
            if (hasBluetoothPermission()) device.bluetoothClass?.majorDeviceClass else null
        } catch (_: SecurityException) {
            null
        }
        return PairedPrinterDevice(
            name = name,
            address = address,
            type = "BLUETOOTH",
            isPaired = bonded,
            isPrinterLikely = isLikelyThermalPrinter(name, majorClass),
        )
    }

    fun isLocationRequiredAndDisabled(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabledForLegacyScan()
    }

    fun supportsCompanionDevicePicker(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    fun launchCompanionDevicePicker(
        onLauncherReady: (IntentSender) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsCompanionDevicePicker()) {
            onFailure("Este dispositivo no soporta el asistente del sistema.")
            return
        }
        val manager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (manager == null) {
            onFailure("Asistente Bluetooth no disponible en este teléfono.")
            return
        }
        val filter = BluetoothDeviceFilter.Builder().build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.associate(
                request,
                ContextCompat.getMainExecutor(context),
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        onLauncherReady(intentSender)
                    }

                    override fun onFailure(error: CharSequence?) {
                        onFailure(error?.toString()?.takeIf { it.isNotBlank() } ?: "No se encontraron impresoras cercanas.")
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            manager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        onLauncherReady(chooserLauncher)
                    }

                    override fun onFailure(error: CharSequence?) {
                        onFailure(error?.toString()?.takeIf { it.isNotBlank() } ?: "No se encontraron impresoras cercanas.")
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    fun extractCompanionDevice(data: Intent?): BluetoothDevice? {
        if (data == null) return null
        val direct = IntentCompat.getParcelableExtra(
            data,
            CompanionDeviceManager.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        )
        if (direct != null) return direct
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val info = IntentCompat.getParcelableExtra(
                data,
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
            return info?.associatedDevice?.bluetoothDevice
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startBleFallbackScan(adapter: BluetoothAdapter): Boolean {
        if (!hasBluetoothScanPermission()) return false
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (_: Exception) {
            null
        } ?: return false

        stopBleScanOnly()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val dev = result?.device ?: return
                val scanName = result.scanRecord?.deviceName
                upsertDiscoveredDevice(dev, scanName)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { res ->
                    res.device?.let { upsertDiscoveredDevice(it, res.scanRecord?.deviceName) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopBleScanOnly()
                _discoveryState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "No se pudo escanear por Bluetooth (código $errorCode). Usa el Asistente o Ajustes Bluetooth.",
                    )
                }
            }
        }
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            activeLeScanner = scanner
            activeLeCallback = callback
            _discoveryState.update {
                it.copy(
                    isBluetoothSupported = true,
                    isBluetoothEnabled = true,
                    isScanning = true,
                    errorMessage = null,
                    needsLocationToggleHint = false,
                    statusMessage = "Buscando impresoras Bluetooth cercanas...",
                )
            }
            true
        } catch (_: Exception) {
            activeLeScanner = null
            activeLeCallback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScanOnly() {
        val cb = activeLeCallback ?: return
        val scanner = activeLeScanner
        activeLeCallback = null
        activeLeScanner = null
        if (scanner != null && hasBluetoothScanPermission()) {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
    }

    private fun prepareDiscoveryPrechecks(): Result<BluetoothAdapter> {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            val msg = "Este dispositivo no cuenta con Bluetooth."
            _discoveryState.update {
                it.copy(isBluetoothSupported = false, isScanning = false, errorMessage = msg)
            }
            refreshKnownPrinters()
            return Result.failure(IllegalStateException(msg))
        }
        if (!hasBluetoothScanPermission()) {
            val msg = "Otorga el permiso de Bluetooth para buscar impresoras cercanas."
            _discoveryState.update { it.copy(isScanning = false, errorMessage = msg) }
            refreshKnownPrinters()
            return Result.failure(IllegalStateException(msg))
        }
        if (!adapter.isEnabled && adapter.state != BluetoothAdapter.STATE_TURNING_ON) {
            val msg = "Enciende el Bluetooth para buscar impresoras nuevas."
            _discoveryState.update {
                it.copy(isBluetoothEnabled = false, isScanning = false, errorMessage = msg)
            }
            refreshKnownPrinters()
            return Result.failure(IllegalStateException(msg))
        }
        if (isLocationRequiredAndDisabled()) {
            val msg = "Activa la Ubicación del teléfono para que Android permita detectar impresoras nuevas."
            _discoveryState.update {
                it.copy(
                    isScanning = false,
                    needsLocationToggleHint = true,
                    errorMessage = msg,
                )
            }
            refreshKnownPrinters()
            return Result.failure(IllegalStateException(msg))
        }
        return Result.success(adapter)
    }

    @SuppressLint("MissingPermission")
    suspend fun startDiscoveryWithFallback(): Result<Unit> {
        val adapter = prepareDiscoveryPrechecks().getOrElse { return Result.failure(it) }

        if (adapter.state == BluetoothAdapter.STATE_TURNING_ON) {
            var waited = 0
            while (adapter.state == BluetoothAdapter.STATE_TURNING_ON && waited < 1800) {
                delay(150)
                waited += 150
            }
        }
        if (!adapter.isEnabled) {
            val msg = "Enciende el Bluetooth para buscar impresoras nuevas."
            _discoveryState.update {
                it.copy(isBluetoothEnabled = false, isScanning = false, errorMessage = msg)
            }
            return Result.failure(IllegalStateException(msg))
        }

        ensureReceiverRegistered()
        discoveredBluetoothMap.keys.toList().forEach { key ->
            if (discoveredBluetoothMap[key]?.isPaired == false) {
                discoveredBluetoothMap.remove(key)
            }
        }
        refreshKnownPrinters()

        return try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
                delay(400)
            }
            var started = adapter.startDiscovery()
            if (!started) {
                delay(350)
                started = adapter.startDiscovery()
            }
            if (started) {
                _discoveryState.update {
                    it.copy(
                        isBluetoothSupported = true,
                        isBluetoothEnabled = true,
                        isScanning = true,
                        errorMessage = null,
                        needsLocationToggleHint = false,
                        statusMessage = "Buscando impresoras Bluetooth cercanas...",
                    )
                }
                Result.success(Unit)
            } else if (startBleFallbackScan(adapter)) {
                Result.success(Unit)
            } else {
                val msg = if (supportsCompanionDevicePicker()) {
                    "Tu teléfono bloqueó la búsqueda directa. Toca 'Asistente del sistema' para elegir tu impresora."
                } else {
                    "No se pudo iniciar la búsqueda directa. Usa 'Ajustes Bluetooth' para vincularla."
                }
                _discoveryState.update { it.copy(isScanning = false, errorMessage = msg) }
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: SecurityException) {
            val msg = "Permiso de búsqueda Bluetooth denegado."
            _discoveryState.update { it.copy(isScanning = false, errorMessage = msg) }
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(): Result<Unit> {
        val adapter = prepareDiscoveryPrechecks().getOrElse { return Result.failure(it) }

        ensureReceiverRegistered()
        // Conserva vinculadas actuales y limpia hallazgos anteriores sin vincular para una búsqueda fresca
        discoveredBluetoothMap.keys.toList().forEach { key ->
            if (discoveredBluetoothMap[key]?.isPaired == false) {
                discoveredBluetoothMap.remove(key)
            }
        }
        refreshKnownPrinters()

        return try {
            val started = if (adapter.isDiscovering) {
                true
            } else {
                adapter.startDiscovery()
            }
            if (started) {
                _discoveryState.update {
                    it.copy(
                        isBluetoothSupported = true,
                        isBluetoothEnabled = true,
                        isScanning = true,
                        errorMessage = null,
                        needsLocationToggleHint = false,
                        statusMessage = "Buscando impresoras Bluetooth cercanas...",
                    )
                }
                Result.success(Unit)
            } else if (startBleFallbackScan(adapter)) {
                Result.success(Unit)
            } else {
                val msg = "No se pudo iniciar la búsqueda directa. Toca 'Asistente del sistema' o 'Ajustes Bluetooth'."
                _discoveryState.update { it.copy(isScanning = false, errorMessage = msg) }
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: SecurityException) {
            val msg = "Permiso de búsqueda Bluetooth denegado."
            _discoveryState.update { it.copy(isScanning = false, errorMessage = msg) }
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        stopBleScanOnly()
        val adapter = bluetoothAdapter()
        if (adapter != null && hasBluetoothScanPermission()) {
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {
            }
        }
        _discoveryState.update { it.copy(isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun pairPrinter(
        device: PairedPrinterDevice,
        onPaired: (PairedPrinterDevice) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            val msg = "Enciende el Bluetooth antes de vincular la impresora."
            _discoveryState.update { it.copy(pairingAddress = null, errorMessage = msg) }
            onFailure(msg)
            return
        }
        if (!hasBluetoothPermission()) {
            val msg = "Otorga el permiso de conexión Bluetooth para vincular la impresora."
            _discoveryState.update { it.copy(pairingAddress = null, errorMessage = msg) }
            onFailure(msg)
            return
        }

        ensureReceiverRegistered()
        if (hasBluetoothScanPermission()) {
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {
            }
        }
        _discoveryState.update { it.copy(isScanning = false) }

        try {
            val remote = adapter.getRemoteDevice(device.address)
            if (remote.bondState == BluetoothDevice.BOND_BONDED) {
                val ready = device.copy(isPaired = true)
                discoveredBluetoothMap[device.address] = ready
                refreshKnownPrinters()
                _discoveryState.update {
                    it.copy(
                        pairingAddress = null,
                        errorMessage = null,
                        statusMessage = "¡Impresora lista: ${ready.name}!",
                    )
                }
                onPaired(ready)
                return
            }

            pendingPairAddress = device.address
            pendingPairSuccess = onPaired
            pendingPairFailure = onFailure

            _discoveryState.update {
                it.copy(
                    pairingAddress = device.address,
                    errorMessage = null,
                    statusMessage = "Vinculando con ${device.name}... Acepta la solicitud en tu pantalla.",
                )
            }
            val started = remote.createBond()
            if (!started) {
                clearPendingPairCallbacks()
                val msg = "No se pudo iniciar la vinculación con ${device.name}. Asegúrate de que esté encendida y cerca."
                _discoveryState.update {
                    it.copy(pairingAddress = null, errorMessage = msg, statusMessage = null)
                }
                onFailure(msg)
            }
        } catch (_: Exception) {
            clearPendingPairCallbacks()
            val msg = "Error al vincular con ${device.name}. Intenta de nuevo."
            _discoveryState.update {
                it.copy(pairingAddress = null, errorMessage = msg, statusMessage = null)
            }
            onFailure(msg)
        }
    }

    fun cancelPendingPairingTimeout() {
        val currentAddr = pendingPairAddress ?: return
        val name = discoveredBluetoothMap[currentAddr]?.name ?: "la impresora"
        clearPendingPairCallbacks()
        _discoveryState.update {
            it.copy(
                pairingAddress = null,
                errorMessage = "Tiempo de espera agotado al vincular con $name. Intenta tocar Vincular de nuevo.",
                statusMessage = null,
            )
        }
    }

    fun release() {
        stopDiscovery()
        clearPendingPairCallbacks()
        synchronized(receiverLock) {
            if (isReceiverRegistered) {
                try {
                    context.unregisterReceiver(discoveryReceiver)
                } catch (_: IllegalArgumentException) {
                }
                isReceiverRegistered = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothPrinters(): List<PairedPrinterDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter: BluetoothAdapter = bluetoothAdapter() ?: return emptyList()
        return try {
            adapter.bondedDevices?.map { device: BluetoothDevice ->
                val devName = device.name?.takeIf { it.isNotBlank() } ?: "Impresora Bluetooth"
                val majorClass = device.bluetoothClass?.majorDeviceClass
                PairedPrinterDevice(
                    name = devName,
                    address = device.address ?: "",
                    type = "BLUETOOTH",
                    isPaired = true,
                    isPrinterLikely = isLikelyThermalPrinter(devName, majorClass),
                )
            }?.sortedWith(
                compareByDescending<PairedPrinterDevice> { it.isPrinterLikely }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            ) ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun getConnectedUsbPrinters(): List<PairedPrinterDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return usbManager.deviceList.values.map { dev ->
            PairedPrinterDevice(
                name = dev.productName ?: "Impresora USB (${dev.vendorId}:${dev.productId})",
                address = dev.deviceName,
                type = "USB",
                isPaired = true,
                isPrinterLikely = true,
            )
        }
    }

    suspend fun printDeliveryTicket(
        delivery: DeliveryEntity,
        settings: BusinessSettingsEntity,
        client: ClientEntity? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val bytes = buildEscPosTicketBytes(delivery, settings, client)
        when (settings.printerType) {
            "USB" -> printViaUsb(settings.printerAddress, bytes)
            else -> printViaBluetooth(settings.printerAddress, bytes)
        }
    }

    suspend fun printTestTicket(settings: BusinessSettingsEntity): Result<String> =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x1B, 0x40)) // ESC @ Init
            out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
            out.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold ON
            out.write(asciiLine(settings.businessName))
            out.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF
            out.write(asciiLine(settings.slogan))
            out.write(asciiLine("--------------------------------------"))
            out.write(asciiLine("PRUEBA DE IMPRESION 60MM"))
            out.write(asciiLine("CONEXION VERIFICADA"))
            out.write(asciiLine("--------------------------------------"))
            out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
            val bytes = out.toByteArray()
            when (settings.printerType) {
                "USB" -> printViaUsb(settings.printerAddress, bytes)
                else -> printViaBluetooth(settings.printerAddress, bytes)
            }
        }

    @SuppressLint("MissingPermission")
    private fun printViaBluetooth(address: String, data: ByteArray): Result<String> {
        if (!hasBluetoothPermission()) {
            return Result.failure(IllegalStateException("Permiso de Bluetooth requerido."))
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: return Result.failure(IllegalStateException("Bluetooth no disponible en este dispositivo."))
        if (!adapter.isEnabled) {
            return Result.failure(IllegalStateException("Encienda el Bluetooth para imprimir."))
        }
        val targetAddress = address.ifBlank {
            adapter.bondedDevices?.firstOrNull()?.address ?: ""
        }
        if (targetAddress.isBlank()) {
            return Result.failure(IllegalStateException("No hay impresora Bluetooth vinculada. Configúrela en Configuración."))
        }
        return try {
            val device = adapter.getRemoteDevice(targetAddress)
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            socket.connect()
            socket.outputStream.use { stream ->
                stream.write(data)
                stream.flush()
            }
            socket.close()
            Result.success("Ticket enviado a impresora Bluetooth")
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Error al imprimir por Bluetooth: ${e.message ?: "Verifique impresora"}"))
        }
    }

    private fun printViaUsb(deviceName: String, data: ByteArray): Result<String> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Result.failure(IllegalStateException("USB Host no soportado."))
        val device: UsbDevice = usbManager.deviceList[deviceName]
            ?: usbManager.deviceList.values.firstOrNull()
            ?: return Result.failure(IllegalStateException("No se detectó impresora USB OTG conectada."))

        if (!usbManager.hasPermission(device)) {
            val intent = Intent(USB_PERMISSION_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pi)
            return Result.failure(IllegalStateException("Acepte el permiso USB OTG y vuelva a intentar imprimir."))
        }

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    val conn = usbManager.openDevice(device)
                        ?: return Result.failure(IllegalStateException("No se pudo abrir conexión USB."))
                    conn.claimInterface(intf, true)
                    val transferred = conn.bulkTransfer(ep, data, data.size, 5000)
                    conn.releaseInterface(intf)
                    conn.close()
                    return if (transferred >= 0) {
                        Result.success("Ticket enviado a impresora USB")
                    } else {
                        Result.failure(IllegalStateException("Falló la transferencia USB a la impresora."))
                    }
                }
            }
        }
        return Result.failure(IllegalStateException("El dispositivo USB no tiene endpoint de impresión compatible."))
    }

    fun buildEscPosTicketBytes(
        delivery: DeliveryEntity,
        settings: BusinessSettingsEntity,
        client: ClientEntity? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dateFmt = SimpleDateFormat("dd 'de' MMMM, yyyy", Locale("es", "MX"))
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.US)
        val isCancelled = delivery.status == "CANCELADA"
        val isCredit = delivery.paymentMethod == PaymentMethod.CREDITO.dbValue
        val weighedItems = CalculationEngine.parseWeighedItemsJson(delivery.itemsSnapshotJson)
        val hasEggs = delivery.boxes > 0.0 || weighedItems.isNotEmpty()
        val hasSugar = delivery.sugarSacks > 0.0
        val sugarWeightKg = if (hasSugar) {
            BigDecimal.valueOf(delivery.sugarSacks).multiply(BigDecimal.valueOf(delivery.sugarSackWeightKg))
        } else {
            BigDecimal.ZERO
        }
        val totalNetWeightKg = BigDecimal.valueOf(delivery.netWeightKg).add(sugarWeightKg)
        val modalityLabel = CalculationEngine.resolveModalityLabel(delivery.modality, weighedItems)
        val brandBreakdown = if (hasEggs) {
            CalculationEngine.summarizeBrandBreakdown(
                items = weighedItems,
                fallbackBrand = delivery.brand,
                fallbackPricePerKg = BigDecimal.valueOf(delivery.pricePerKg),
                fallbackBoxes = BigDecimal.valueOf(delivery.boxes),
                fallbackNetWeightKg = BigDecimal.valueOf(delivery.netWeightKg),
            )
        } else {
            emptyList()
        }
        val headerBrandSummary = if (hasEggs) {
            CalculationEngine.resolveBrandSummaryLabel(delivery.brand, weighedItems)
        } else {
            ""
        }
        val rawSubtotal = BigDecimal.valueOf(delivery.rawAmountMxn).setScale(2, RoundingMode.HALF_UP)
        val finalBd = BigDecimal.valueOf(delivery.finalAmountMxn).setScale(2, RoundingMode.HALF_UP)
        val roundingDiff = finalBd.subtract(rawSubtotal).setScale(2, RoundingMode.HALF_UP)
        val effectivePaidMxn = if (!isCancelled) {
            (delivery.finalAmountMxn - delivery.pendingBalanceMxn).coerceIn(0.0, delivery.finalAmountMxn)
        } else {
            delivery.initialPaymentMxn
        }
        val statusText = when {
            isCancelled -> "CANCELADO"
            delivery.pendingBalanceMxn > 0.0 -> "ENTREGADO / PENDIENTE LIQUIDACION"
            else -> "LIQUIDADO"
        }

        out.write(byteArrayOf(0x1B, 0x40)) // Init
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center

        // Logo térmico 1-bit de 432 dots (formato 60mm)
        BitmapFactory.decodeResource(context.resources, R.drawable.img_ticket_logo)?.let { bmp ->
            out.write(bitmapToEscPosRaster(bmp, maxWidthPx = 432))
        }

        if (settings.address.isNotBlank()) {
            wrapAsciiLines(settings.address).forEach { out.write(asciiLine(it)) }
        }
        if (settings.phone.isNotBlank()) out.write(asciiLine("Tel: ${settings.phone}"))
        out.write(asciiLine("======================================"))

        if (isCancelled) {
            out.write(byteArrayOf(0x1B, 0x45, 0x01))
            out.write(byteArrayOf(0x1D, 0x21, 0x11)) // Double width & height
            out.write(asciiLine("CANCELADO"))
            out.write(byteArrayOf(0x1D, 0x21, 0x00))
            out.write(byteArrayOf(0x1B, 0x45, 0x00))
            out.write(asciiLine("--------------------------------------"))
        }

        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(asciiLine("COMPROBANTE DE REMISION"))
        out.write(byteArrayOf(0x1D, 0x21, 0x01)) // Double height folio
        out.write(asciiLine(delivery.folio))
        out.write(byteArrayOf(0x1D, 0x21, 0x00))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))

        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
        out.write(pairLine("Fecha:", dateFmt.format(Date(delivery.dateMillis))))
        out.write(pairLine("Hora:", timeFmt.format(Date(delivery.dateMillis))))
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        wrapAsciiLines(statusText).forEach { out.write(asciiLine(it)) }
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
        out.write(asciiLine("--------------------------------------"))

        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(asciiLine("CLIENTE"))
        wrapAsciiLines(delivery.clientName).forEach { out.write(asciiLine(it)) }
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        if (!client?.address.isNullOrBlank()) {
            wrapAsciiLines(client?.address.orEmpty()).forEach { out.write(asciiLine(it)) }
        }
        if (!client?.phone.isNullOrBlank()) {
            out.write(asciiLine("Tel: ${client?.phone.orEmpty()}"))
        }

        out.write(asciiLine("--------------------------------------"))
        val productHeader = when {
            hasEggs && hasSugar -> "$headerBrandSummary - $modalityLabel + Azucar"
            hasSugar -> "Azucar (Bultos)"
            else -> "$headerBrandSummary - $modalityLabel"
        }
        wrapAsciiLines(productHeader).forEach { out.write(asciiLine(it)) }

        brandBreakdown.forEach { b ->
            val leftPart = "${b.brand.take(12)} ${CalculationEngine.formatExactWeight(b.netWeightKg)}kg x ${CalculationEngine.formatMoney(b.pricePerKg)}"
            val rightSub = CalculationEngine.formatMoney(b.subtotalMxn.setScale(2, RoundingMode.HALF_UP))
            if (normalizeAscii(leftPart).length + 1 + normalizeAscii(rightSub).length <= LINE_WIDTH_CHARS) {
                out.write(pairLine(leftPart, rightSub))
            } else {
                wrapAsciiLines(b.brand).forEach { out.write(asciiLine(it)) }
                val detailLeft = "${CalculationEngine.formatExactWeight(b.netWeightKg)}kg x ${CalculationEngine.formatMoney(b.pricePerKg)}/kg"
                out.write(pairLine(detailLeft, rightSub))
            }
        }
        if (hasSugar) {
            val sLeft = "Azucar ${CalculationEngine.formatExactBoxes(delivery.sugarSacks)}bto x ${CalculationEngine.formatExactWeight(delivery.sugarSackWeightKg)}kg"
            val sRight = CalculationEngine.formatMoney(delivery.sugarSubtotalMxn)
            if (normalizeAscii(sLeft).length + 1 + normalizeAscii(sRight).length <= LINE_WIDTH_CHARS) {
                out.write(pairLine(sLeft, sRight))
            } else {
                out.write(asciiLine("Azucar (${CalculationEngine.formatExactBoxes(delivery.sugarSacks)} bto)"))
                out.write(pairLine("${CalculationEngine.formatExactWeight(delivery.sugarSackWeightKg)}kg x ${CalculationEngine.formatMoney(delivery.sugarPricePerSackMxn)}/bto", sRight))
            }
        }

        out.write(asciiLine("======================================"))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(pairLine("KILOS NETOS", "${CalculationEngine.formatExactWeight(totalNetWeightKg)} kg"))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(asciiLine("======================================"))

        out.write(pairLine("Subtotal:", CalculationEngine.formatMoney(rawSubtotal)))
        if (roundingDiff.compareTo(BigDecimal.ZERO) != 0) {
            val sign = if (roundingDiff > BigDecimal.ZERO) "+" else "-"
            out.write(pairLine("Redondeo:", "$sign${CalculationEngine.formatMoney(roundingDiff.abs())}"))
        }
        out.write(asciiLine("--------------------------------------"))

        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(pairLine("TOTAL", "${CalculationEngine.formatMoney(delivery.finalAmountMxn)} MXN"))
        wrapAsciiLines(CalculationEngine.amountInWordsMxn(delivery.finalAmountMxn)).forEach {
            out.write(asciiLine(it))
        }
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(asciiLine("--------------------------------------"))

        val payLabel = PaymentMethod.fromDb(delivery.paymentMethod).label
        out.write(pairLine("Pagado / Abono:", "${CalculationEngine.formatMoney(effectivePaidMxn)} MXN"))
        out.write(asciiLine("Forma pago: $payLabel"))

        // Barra invertida ESC/POS (GS B 1 / GS B 0)
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(byteArrayOf(0x1D, 0x42, 0x01))
        out.write(
            pairLine(
                if (delivery.pendingBalanceMxn > 0.0) "RESTA ENTREGA" else "LIQUIDADO",
                "${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN",
            ),
        )
        out.write(byteArrayOf(0x1D, 0x42, 0x00))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))

        out.write(asciiLine("--------------------------------------"))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(pairLine("Saldo anterior:", "${CalculationEngine.formatMoney(delivery.previousDebtSnapshotMxn)} MXN"))
        out.write(pairLine("Esta remision:", "+${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN"))
        val totalDebt = delivery.previousDebtSnapshotMxn + delivery.pendingBalanceMxn
        out.write(pairLine("SALDO TOTAL", "${CalculationEngine.formatMoney(totalDebt)} MXN"))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))

        if (isCredit) {
            out.write(asciiLine("--------------------------------------"))
            val legend = when {
                isCancelled -> "Nota cancelada. Las obligaciones de pago y entrega correspondientes fueron revertidas."
                delivery.pendingBalanceMxn > 0.0 ->
                    CalculationEngine.buildPendingCreditLegendSegments(
                        deliveryDateMillis = delivery.dateMillis,
                        pendingBalanceMxn = delivery.pendingBalanceMxn,
                        totalDebtMxn = totalDebt,
                        effectiveTermDays = client?.creditTermDays ?: settings.creditTermDays,
                        moratoriumInterestPercent = settings.creditMoratoriumInterestPercent,
                        paymentPlace = settings.creditPaymentPlace,
                        extraCondition = settings.creditLegendExtra,
                        closingNote = settings.creditLegendClosing,
                    ).joinToString("") { it.text }
                else ->
                    "Recibi la mercancia descrita en esta remision a mi entera satisfaccion y cubri el importe total senalado. Acepto mi firma digitalizada como constancia de recepcion."
            }
            out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left
            wrapAsciiLines(legend).forEach { out.write(asciiLine(it)) }
            out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
            delivery.signaturePath?.let { path ->
                val sigFile = File(path)
                if (sigFile.exists()) {
                    BitmapFactory.decodeFile(sigFile.absolutePath)?.let { sigBmp ->
                        out.write(bitmapToEscPosRaster(sigBmp, maxWidthPx = 360))
                    }
                }
            }
            out.write(asciiLine("Recibi de conformidad"))
            out.write(byteArrayOf(0x1B, 0x45, 0x01))
            wrapAsciiLines(delivery.clientName).forEach { out.write(asciiLine(it)) }
            out.write(byteArrayOf(0x1B, 0x45, 0x00))
        }

        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        out.write(asciiLine("--------------------------------------"))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(asciiLine("!Gracias por su preferencia!"))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(asciiLine("Calidad y frescura en cada huevo"))

        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Cut if supported
        return out.toByteArray()
    }

    private fun wrapAsciiLines(text: String, maxCols: Int = LINE_WIDTH_CHARS): List<String> {
        val clean = normalizeAscii(text).trim()
        if (clean.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in clean.split(Regex("\\s+"))) {
            if (current.isEmpty()) {
                current.append(word.take(maxCols))
            } else if (current.length + 1 + word.length <= maxCols) {
                current.append(' ').append(word)
            } else {
                lines += current.toString()
                current = StringBuilder(word.take(maxCols))
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun pairLine(left: String, right: String): ByteArray {
        val cleanLeft = normalizeAscii(left)
        val cleanRight = normalizeAscii(right)
        val spaces = (LINE_WIDTH_CHARS - cleanLeft.length - cleanRight.length).coerceAtLeast(1)
        return (cleanLeft + " ".repeat(spaces) + cleanRight + "\n").toByteArray(Charsets.US_ASCII)
    }

    private fun asciiLine(text: String): ByteArray {
        return (normalizeAscii(text) + "\n").toByteArray(Charsets.US_ASCII)
    }

    private fun normalizeAscii(input: String): String {
        val norm = Normalizer.normalize(input, Normalizer.Form.NFD)
        return norm.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^\\x20-\\x7E]"), "")
    }

    private fun bitmapToEscPosRaster(source: Bitmap, maxWidthPx: Int): ByteArray {
        val targetW = ((source.width.coerceAtMost(maxWidthPx)) / 8) * 8
        val safeW = targetW.coerceAtLeast(8)
        val targetH = ((source.height.toFloat() / source.width.toFloat()) * safeW).toInt().coerceAtLeast(8)
        val scaled = Bitmap.createScaledBitmap(source, safeW, targetH, true)

        val widthBytes = safeW / 8
        val out = ByteArrayOutputStream()
        // GS v 0 m xL xH yL yH
        out.write(
            byteArrayOf(
                0x1D,
                0x76,
                0x30,
                0x00,
                (widthBytes and 0xFF).toByte(),
                ((widthBytes shr 8) and 0xFF).toByte(),
                (targetH and 0xFF).toByte(),
                ((targetH shr 8) and 0xFF).toByte(),
            ),
        )
        for (y in 0 until targetH) {
            for (xByte in 0 until widthBytes) {
                var slice = 0
                for (bit in 0 until 8) {
                    val px = scaled.getPixel(xByte * 8 + bit, y)
                    val alpha = Color.alpha(px)
                    val lum = (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px))
                    val isDark = alpha > 128 && lum < 160
                    if (isDark) {
                        slice = slice or (1 shl (7 - bit))
                    }
                }
                out.write(slice)
            }
        }
        return out.toByteArray()
    }
}
