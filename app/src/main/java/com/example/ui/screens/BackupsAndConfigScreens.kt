package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.entity.BackupRecordEntity
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.domain.CalculationEngine
import com.example.system.BluetoothDiscoveryState
import com.example.system.PairedPrinterDevice
import com.example.ui.components.AuroraCard
import com.example.ui.components.AuroraEmptyState
import com.example.ui.components.AuroraOutlinedButton
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraStatusBadge
import com.example.ui.components.AuroraTextField
import com.example.ui.components.AuroraTopBar
import com.example.ui.theme.OnPrimary
import com.example.ui.theme.OnSurface
import com.example.ui.theme.OnSurfaceVariant
import com.example.ui.theme.OutlineVariant
import com.example.ui.theme.Primary
import com.example.ui.theme.PrimaryGlow
import com.example.ui.theme.Secondary
import com.example.ui.theme.SurfaceContainer
import com.example.ui.theme.SurfaceContainerLow
import com.example.ui.theme.SurfaceContainerLowest
import com.example.ui.viewmodel.ClientWithDebt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RespaldosLocalesScreen(
    backups: List<BackupRecordEntity>,
    onOpenDrawer: () -> Unit,
    onCreateZipBackup: () -> Unit,
    onExportJsonBackup: () -> Unit,
    onSaveBackupToDownloads: (BackupRecordEntity) -> Unit,
    onRestoreFromUri: (Uri) -> Unit,
    onRestoreFromRecord: (BackupRecordEntity) -> Unit,
) {
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onRestoreFromUri(uri)
        }
    }

    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX")) }

    Column(modifier = Modifier.fillMaxSize()) {
        AuroraTopBar(
            title = "Respaldos Locales",
            subtitle = "Copia ZIP / JSON • Transferencia USB",
            showDrawerMenu = true,
            onMenuOrBackClick = onOpenDrawer,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                AuroraCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.CloudOff, contentDescription = null, tint = PrimaryGlow)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Almacenamiento 100% Local en Dispositivo",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Sin sincronización en la nube. El paquete ZIP incluye datos generales en JSON, cierres diarios (PDF/JSON), tickets, firmas digitales y metadatos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    AuroraPrimaryButton(
                        text = "Generar Respaldo Completo (.ZIP)",
                        icon = Icons.Default.FolderZip,
                        onClick = onCreateZipBackup,
                        testTag = "create_zip_backup_button",
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AuroraOutlinedButton(
                            text = "Exportar JSON",
                            icon = Icons.Default.Code,
                            onClick = onExportJsonBackup,
                            accentColor = Secondary,
                            modifier = Modifier.weight(1f),
                            testTag = "export_json_backup_button",
                        )
                        AuroraOutlinedButton(
                            text = "Restaurar Archivo",
                            icon = Icons.Default.Restore,
                            onClick = {
                                openDocLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                            },
                            accentColor = Primary,
                            modifier = Modifier.weight(1f),
                            testTag = "restore_backup_picker_button",
                        )
                    }
                }
            }

            item {
                AuroraCard(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Usb, contentDescription = null, tint = Secondary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Transferencia a PC por Cable USB",
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "1. Genera un respaldo ZIP o JSON (se guarda automáticamente en Descargas/HYH_Backups).\n2. Conecta tu teléfono a la computadora por USB en modo 'Transferencia de archivos' y copia la carpeta HYH_Backups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                    )
                }
            }

            item {
                Text(
                    text = "Historial de Respaldos (${backups.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                )
            }

            if (backups.isEmpty()) {
                item {
                    AuroraEmptyState(
                        icon = Icons.Default.FolderZip,
                        title = "Sin respaldos generados",
                        subtitle = "Crea un paquete ZIP o exportación JSON para proteger tus datos localmente.",
                    )
                }
            } else {
                items(backups, key = { it.id }) { rec ->
                    AuroraCard(contentPadding = PaddingValues(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rec.fileName, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                                Text(
                                    text = "${dateFmt.format(Date(rec.createdAtMillis))} • ${(rec.sizeBytes / 1024).coerceAtLeast(1)} KB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant,
                                )
                            }
                            AuroraStatusBadge(text = rec.format, tint = Primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AuroraOutlinedButton(
                                text = "Guardar / USB",
                                icon = Icons.Default.Download,
                                onClick = { onSaveBackupToDownloads(rec) },
                                accentColor = Secondary,
                                modifier = Modifier.weight(1f),
                            )
                            AuroraOutlinedButton(
                                text = "Restaurar",
                                icon = Icons.Default.Restore,
                                onClick = { onRestoreFromRecord(rec) },
                                accentColor = PrimaryGlow,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfiguracionScreen(
    settings: BusinessSettingsEntity,
    clients: List<ClientWithDebt>,
    printers: List<PairedPrinterDevice>,
    bluetoothState: BluetoothDiscoveryState = BluetoothDiscoveryState(),
    onOpenDrawer: () -> Unit,
    onSaveBusinessConfig: (phone: String, address: String, slogan: String, globalPriceText: String) -> Unit,
    onUpdateClientSpecialPrice: (clientId: Long, specialPriceText: String) -> Unit,
    onRefreshPrinters: () -> Unit,
    onStartPrinterScan: () -> Unit = onRefreshPrinters,
    onStopPrinterScan: () -> Unit = {},
    onLaunchSystemBluetoothPicker: ((IntentSender) -> Unit) -> Unit = {},
    onHandleSystemBluetoothPickerResult: (Intent?) -> Unit = {},
    onSelectPrinterType: (String) -> Unit,
    onSelectPrinter: (PairedPrinterDevice) -> Unit,
    onTestPrinter: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var phone by remember(settings.phone) { mutableStateOf(settings.phone) }
    var address by remember(settings.address) { mutableStateOf(settings.address) }
    var slogan by remember(settings.slogan) { mutableStateOf(settings.slogan) }
    var globalPriceText by remember(settings.globalPricePerKg) {
        mutableStateOf(if (settings.globalPricePerKg > 0.0) settings.globalPricePerKg.toString() else "")
    }
    var editingSpecialClient by remember { mutableStateOf<ClientEntity?>(null) }
    var permissionDeniedNotice by remember { mutableStateOf(false) }

    val requiredScanPermissions = remember {
        when {
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

    val supportsCompanionPicker = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    val companionPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            onHandleSystemBluetoothPickerResult(res.data)
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onRefreshPrinters()
        val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter?.isEnabled == true) {
            onStartPrinterScan()
        }
    }

    fun launchSearchOrEnableFlow() {
        if (settings.printerType == "USB") {
            onRefreshPrinters()
            return
        }
        val missing = requiredScanPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionDeniedNotice = false
            val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            if (adapter != null && !adapter.isEnabled) {
                runCatching {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.onFailure {
                    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            } else {
                onStartPrinterScan()
            }
        }
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.values.isNotEmpty() && result.values.all { it }
        if (allGranted) {
            permissionDeniedNotice = false
            launchSearchOrEnableFlow()
        } else {
            permissionDeniedNotice = true
            onRefreshPrinters()
        }
    }

    LaunchedEffect(Unit) {
        onRefreshPrinters()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPrinters()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStopPrinterScan()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AuroraTopBar(
            title = "Configuración",
            subtitle = settings.businessName,
            showDrawerMenu = true,
            onMenuOrBackClick = onOpenDrawer,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Datos del Negocio y Precio Global
            item {
                AuroraCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Datos del Negocio",
                            style = MaterialTheme.typography.titleLarge,
                            color = OnSurface,
                        )
                        AuroraStatusBadge(text = settings.businessName, tint = Secondary)
                    }
                    Spacer(Modifier.height(12.dp))
                    AuroraTextField(
                        value = slogan,
                        onValueChange = { slogan = it },
                        label = "Eslogan oficial",
                        testTag = "config_slogan_input",
                    )
                    Spacer(Modifier.height(10.dp))
                    AuroraTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = "Teléfono del negocio",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        testTag = "config_phone_input",
                    )
                    Spacer(Modifier.height(10.dp))
                    AuroraTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = "Dirección del negocio",
                        singleLine = false,
                        testTag = "config_address_input",
                    )
                    Spacer(Modifier.height(10.dp))
                    AuroraTextField(
                        value = globalPriceText,
                        onValueChange = { globalPriceText = it },
                        label = "Precio global por kilogramo (MXN)",
                        suffixText = "MXN/kg",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        testTag = "config_global_price_input",
                    )
                    Spacer(Modifier.height(14.dp))
                    AuroraPrimaryButton(
                        text = "Guardar Datos y Precio Global",
                        icon = Icons.Default.Save,
                        onClick = { onSaveBusinessConfig(phone, address, slogan, globalPriceText) },
                        testTag = "save_business_config_button",
                    )
                }
            }

            // Precio Especial por Cliente
            item {
                AuroraCard {
                    Text(
                        text = "Precios Especiales por Cliente",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Si un cliente tiene precio especial, se usará en lugar del precio global en sus entregas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (clients.isEmpty()) {
                        Text(
                            text = "Registra clientes primero para asignarles tarifas especiales.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                    } else {
                        clients.forEach { c ->
                            Surface(
                                onClick = { editingSpecialClient = c.client },
                                color = SurfaceContainerLowest,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, OutlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("special_price_client_${c.client.id}"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(c.client.name, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                                    Text(
                                        text = if (c.client.specialPricePerKg != null) {
                                            "Especial: ${CalculationEngine.formatMoney(c.client.specialPricePerKg)}/kg"
                                        } else {
                                            "Usa global (${CalculationEngine.formatMoney(settings.globalPricePerKg)})"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (c.client.specialPricePerKg != null) Secondary else Primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Impresora Térmica 60 mm (Bluetooth y USB OTG)
            item {
                val readyPrinters = remember(printers) { printers.filter { it.isPaired || it.type == "USB" } }
                val nearbyNewPrinters = remember(printers) { printers.filter { !it.isPaired && it.type == "BLUETOOTH" } }
                val isBluetoothMode = settings.printerType != "USB"

                AuroraCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Impresora Térmica 60 mm",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface,
                        )
                        if (bluetoothState.isScanning) {
                            AuroraStatusBadge(text = "Buscando...", tint = Secondary)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        listOf("BLUETOOTH" to Icons.Default.Bluetooth, "USB" to Icons.Default.Usb).forEach { (type, icon) ->
                            val selected = settings.printerType == type
                            Surface(
                                onClick = { onSelectPrinterType(type) },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) Primary else SurfaceContainerLow,
                                border = BorderStroke(1.dp, if (selected) Primary else OutlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("printer_type_${type.lowercase()}"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(icon, contentDescription = null, tint = if (selected) OnPrimary else Primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (type == "USB") "USB OTG" else "Bluetooth",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) OnPrimary else OnSurface,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = SurfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OutlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (settings.printerName.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.Print,
                                contentDescription = null,
                                tint = if (settings.printerName.isNotBlank()) PrimaryGlow else Secondary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (settings.printerName.isNotBlank()) {
                                        "Impresora activa: ${settings.printerName}"
                                    } else {
                                        "Ninguna impresora seleccionada"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = OnSurface,
                                )
                                Text(
                                    text = if (settings.printerName.isNotBlank()) {
                                        "Modo ${if (settings.printerType == "USB") "USB OTG" else "Bluetooth"} listo para imprimir."
                                    } else {
                                        "Enciende tu impresora y toca Buscar Impresoras para detectarla y vincularla."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Aviso amigable si el Bluetooth del teléfono está apagado
                    AnimatedVisibility(visible = isBluetoothMode && bluetoothState.isBluetoothSupported && !bluetoothState.isBluetoothEnabled) {
                        Surface(
                            color = Secondary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Secondary.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Default.BluetoothDisabled, contentDescription = null, tint = Secondary)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "El Bluetooth está apagado",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = OnSurface,
                                    )
                                    Text(
                                        text = "Enciéndelo con un toque para buscar impresoras nuevas.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = {
                                        val missing = requiredScanPermissions.filter {
                                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                        }
                                        if (missing.isNotEmpty()) {
                                            btPermissionLauncher.launch(missing.toTypedArray())
                                        } else {
                                            runCatching {
                                                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                            }.onFailure {
                                                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Secondary, contentColor = OnPrimary),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("enable_bluetooth_button"),
                                ) {
                                    Text("Encender")
                                }
                            }
                        }
                    }

                    // Aviso amigable si falta permiso de Bluetooth
                    AnimatedVisibility(visible = isBluetoothMode && permissionDeniedNotice) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Necesitamos permiso de Bluetooth cercano para encontrar y vincular tu impresora.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AuroraOutlinedButton(
                                        text = "Conceder permiso",
                                        icon = Icons.Default.BluetoothSearching,
                                        onClick = { btPermissionLauncher.launch(requiredScanPermissions) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AuroraOutlinedButton(
                                        text = "Ajustes",
                                        icon = Icons.Default.Settings,
                                        onClick = {
                                            runCatching {
                                                val appSettings = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null),
                                                )
                                                context.startActivity(appSettings)
                                            }
                                        },
                                        accentColor = Secondary,
                                        modifier = Modifier.weight(0.8f),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AuroraOutlinedButton(
                            text = if (bluetoothState.isScanning) "Detener búsqueda" else "Buscar Impresoras",
                            icon = if (bluetoothState.isScanning) Icons.Default.Stop else if (isBluetoothMode) Icons.Default.BluetoothSearching else Icons.Default.Refresh,
                            onClick = {
                                if (bluetoothState.isScanning) {
                                    onStopPrinterScan()
                                } else if (!isBluetoothMode) {
                                    onRefreshPrinters()
                                } else {
                                    val missing = requiredScanPermissions.filter {
                                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (missing.isNotEmpty()) {
                                        btPermissionLauncher.launch(missing.toTypedArray())
                                    } else {
                                        launchSearchOrEnableFlow()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            testTag = "scan_printers_button",
                        )
                        AuroraOutlinedButton(
                            text = "Prueba 60mm",
                            icon = Icons.Default.Print,
                            onClick = onTestPrinter,
                            accentColor = Secondary,
                            modifier = Modifier.weight(0.9f),
                            testTag = "test_printer_button",
                        )
                    }

                    // Estado amigable durante búsqueda o vinculación
                    val liveMessage = bluetoothState.errorMessage ?: bluetoothState.statusMessage
                    AnimatedVisibility(visible = bluetoothState.isScanning || bluetoothState.pairingAddress != null || liveMessage != null) {
                        Surface(
                            color = if (bluetoothState.errorMessage != null) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
                            } else {
                                Primary.copy(alpha = 0.10f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (bluetoothState.errorMessage != null) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                                } else {
                                    Primary.copy(alpha = 0.30f)
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (bluetoothState.isScanning || bluetoothState.pairingAddress != null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.4.dp,
                                        color = Primary,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.BluetoothConnected,
                                        contentDescription = null,
                                        tint = if (bluetoothState.errorMessage != null) MaterialTheme.colorScheme.error else Primary,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = liveMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurface,
                                    )
                                    if (bluetoothState.isScanning) {
                                        Text(
                                            text = "Asegúrate de que la impresora esté encendida y cerca.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OnSurfaceVariant,
                                        )
                                    } else if (bluetoothState.needsLocationToggleHint) {
                                        Text(
                                            text = "En Android 11 o anterior, activa la Ubicación del teléfono o usa el Asistente del sistema.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Secondary,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        AuroraOutlinedButton(
                                            text = "Activar Ubicación",
                                            icon = Icons.Default.Settings,
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                                }
                                            },
                                            accentColor = Secondary,
                                            testTag = "open_location_settings_button",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Lista 1: Impresoras ya vinculadas / USB listas para usar
                    if (readyPrinters.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Primary.copy(alpha = 0.14f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Impresoras vinculadas y listas (${readyPrinters.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = OnSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        readyPrinters.forEach { dev ->
                            val isSelected = settings.printerAddress == dev.address && settings.printerType == dev.type
                            Surface(
                                onClick = { onSelectPrinter(dev) },
                                color = if (isSelected) Primary.copy(alpha = 0.16f) else SurfaceContainerLowest,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isSelected) Primary else OutlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("printer_ready_${dev.address}"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dev.name, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                                        Text(
                                            text = "${if (dev.type == "USB") "USB OTG" else "Bluetooth vinculada"} • ${dev.address}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    AuroraStatusBadge(
                                        text = if (isSelected) "Activa" else "Usar",
                                        tint = if (isSelected) PrimaryGlow else Secondary,
                                    )
                                }
                            }
                        }
                    }

                    // Lista 2: Nuevas impresoras o dispositivos encontrados para vincular con 1 toque
                    if (nearbyNewPrinters.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Secondary.copy(alpha = 0.18f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Dispositivos cercanos nuevos (${nearbyNewPrinters.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = Secondary,
                        )
                        Text(
                            text = "Toca uno para vincularlo y dejarlo listo automáticamente (si pide PIN suele ser 0000 o 1234).",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        nearbyNewPrinters.forEach { dev ->
                            val isPairingThis = bluetoothState.pairingAddress == dev.address
                            Surface(
                                onClick = { if (!isPairingThis) onSelectPrinter(dev) },
                                color = SurfaceContainerLowest,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (dev.isPrinterLikely) Secondary.copy(alpha = 0.55f) else OutlineVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("printer_discovered_${dev.address}"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = dev.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = OnSurface,
                                            )
                                            if (dev.isPrinterLikely) {
                                                AuroraStatusBadge(text = "Impresora", tint = PrimaryGlow)
                                            }
                                        }
                                        Text(
                                            text = "Nuevo dispositivo • ${dev.address}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (isPairingThis) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Primary,
                                            )
                                            Text(
                                                text = "Vinculando...",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Primary,
                                            )
                                        }
                                    } else {
                                        Surface(
                                            color = Primary.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f)),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Link,
                                                    contentDescription = null,
                                                    tint = Primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Text(
                                                    text = "Vincular",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Primary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (readyPrinters.isEmpty() && nearbyNewPrinters.isEmpty() && !bluetoothState.isScanning) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "No se detectan impresoras todavía. Enciende tu impresora y toca 'Buscar Impresoras'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                    }

                    if (isBluetoothMode) {
                        if (supportsCompanionPicker) {
                            Spacer(Modifier.height(10.dp))
                            AuroraOutlinedButton(
                                text = "Buscar con Asistente del Sistema",
                                icon = Icons.Default.BluetoothSearching,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        btPermissionLauncher.launch(requiredScanPermissions)
                                    } else {
                                        val adapter = (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                                        if (adapter != null && !adapter.isEnabled) {
                                            runCatching {
                                                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                            }.onFailure {
                                                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                                            }
                                        } else {
                                            onLaunchSystemBluetoothPicker { sender ->
                                                runCatching {
                                                    companionPickerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                                }
                                            }
                                        }
                                    }
                                },
                                accentColor = PrimaryGlow,
                                testTag = "companion_bluetooth_picker_button",
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .heightIn(min = 48.dp)
                                .testTag("open_system_bluetooth_settings_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = OnSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Abrir ajustes Bluetooth del teléfono",
                                style = MaterialTheme.typography.labelMedium,
                                color = OnSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    editingSpecialClient?.let { target ->
        SpecialPriceDialog(
            client = target,
            globalPrice = settings.globalPricePerKg,
            onDismiss = { editingSpecialClient = null },
            onSave = { priceText ->
                onUpdateClientSpecialPrice(target.id, priceText)
                editingSpecialClient = null
            },
        )
    }
}

@Composable
private fun SpecialPriceDialog(
    client: ClientEntity,
    globalPrice: Double,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var priceText by remember { mutableStateOf(client.specialPricePerKg?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainer,
        title = { Text("Precio especial: ${client.name}", color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Deja vacío para usar el precio global (${CalculationEngine.formatMoney(globalPrice)} MXN/kg).",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                )
                AuroraTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = "Precio especial (MXN/kg)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    testTag = "special_price_dialog_input",
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(priceText) },
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_special_price_confirm"),
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancelar", color = Secondary)
            }
        },
    )
}
