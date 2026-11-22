package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.data.local.entity.DeliveryEntity
import com.example.domain.CalculationEngine
import com.example.domain.DeliveryModality
import com.example.domain.PaymentMethod
import kotlinx.coroutines.Dispatchers
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

data class PairedPrinterDevice(
    val name: String,
    val address: String,
    val type: String, // "BLUETOOTH" or "USB"
)

class ThermalPrinterManager(private val context: Context) {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val USB_PERMISSION_ACTION = "com.aistudio.hyhhuevos.qmzpkt.USB_PERMISSION"
        private const val LINE_WIDTH_CHARS = 32
    }

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothPrinters(): List<PairedPrinterDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.map { device: BluetoothDevice ->
                PairedPrinterDevice(
                    name = device.name ?: "Impresora Bluetooth",
                    address = device.address ?: "",
                    type = "BLUETOOTH",
                )
            } ?: emptyList()
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
            out.write(asciiLine("--------------------------------"))
            out.write(asciiLine("PRUEBA DE IMPRESION 48MM"))
            out.write(asciiLine("CONEXION VERIFICADA"))
            out.write(asciiLine("--------------------------------"))
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
        val modalityLabel = CalculationEngine.resolveModalityLabel(delivery.modality, weighedItems)
        val brandBreakdown = CalculationEngine.summarizeBrandBreakdown(
            items = weighedItems,
            fallbackBrand = delivery.brand,
            fallbackPricePerKg = BigDecimal.valueOf(delivery.pricePerKg),
        )
        val hasMultiBrandOrPrice = brandBreakdown.size > 1
        val headerBrandSummary = CalculationEngine.resolveBrandSummaryLabel(delivery.brand, weighedItems)
        val allWeightedHalf = weighedItems.isNotEmpty() &&
            weighedItems.all { it.modality == DeliveryModality.MEDIA_CAJA_PESADA }
        val secondColHeader = if (weighedItems.isEmpty()) {
            if (DeliveryModality.fromDb(delivery.modality) == DeliveryModality.MEDIA_CAJA_PESADA) "ING.kg" else "BRUTO"
        } else if (allWeightedHalf) {
            "ING.kg"
        } else {
            "BRUTO"
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

        // Logo térmico 1-bit de 384 dots (48mm exactos)
        BitmapFactory.decodeResource(context.resources, R.drawable.img_ticket_logo)?.let { bmp ->
            out.write(bitmapToEscPosRaster(bmp, maxWidthPx = 384))
        }

        if (settings.address.isNotBlank()) {
            wrapAsciiLines(settings.address).forEach { out.write(asciiLine(it)) }
        }
        if (settings.phone.isNotBlank()) out.write(asciiLine("Tel: ${settings.phone}"))
        out.write(asciiLine("================================"))

        if (isCancelled) {
            out.write(byteArrayOf(0x1B, 0x45, 0x01))
            out.write(byteArrayOf(0x1D, 0x21, 0x11)) // Double width & height
            out.write(asciiLine("CANCELADO"))
            out.write(byteArrayOf(0x1D, 0x21, 0x00))
            out.write(byteArrayOf(0x1B, 0x45, 0x00))
            out.write(asciiLine("--------------------------------"))
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
        out.write(asciiLine("--------------------------------"))

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

        out.write(asciiLine("--------------------------------"))
        wrapAsciiLines("$headerBrandSummary - $modalityLabel").forEach { out.write(asciiLine(it)) }

        // Tabla de 4 columnas en 32 caracteres: No.(5) + BRUTO(9) + TARA(8) + NETO(10) = 32
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(
            tableRow4(
                if (weighedItems.isNotEmpty()) "No." else "CANT",
                secondColHeader,
                "TARA",
                "NETO kg",
            ),
        )
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        if (weighedItems.isNotEmpty()) {
            var prevGroupKey: Pair<String, BigDecimal>? = null
            weighedItems.forEachIndexed { idx, item ->
                if (hasMultiBrandOrPrice) {
                    val effBrand = item.brand.trim().ifBlank { delivery.brand.ifBlank { "General" } }
                    val effPrice = (if (item.pricePerKg > BigDecimal.ZERO) item.pricePerKg else BigDecimal.valueOf(delivery.pricePerKg))
                        .stripTrailingZeros()
                    val groupKey = effBrand to effPrice
                    if (groupKey != prevGroupKey) {
                        prevGroupKey = groupKey
                        out.write(byteArrayOf(0x1B, 0x45, 0x01))
                        wrapAsciiLines("$effBrand (${CalculationEngine.formatMoney(effPrice)}/kg)")
                            .forEach { out.write(asciiLine(it)) }
                        out.write(byteArrayOf(0x1B, 0x45, 0x00))
                    }
                }
                val numLabel = if (item.modality != DeliveryModality.CAJA_COMPLETA) {
                    String.format(Locale.US, "%02d*", idx + 1)
                } else {
                    String.format(Locale.US, "%02d", idx + 1)
                }
                out.write(
                    tableRow4(
                        numLabel,
                        CalculationEngine.formatExactWeight(item.inputWeightKg),
                        CalculationEngine.formatExactWeight(item.tareDeductionKg),
                        CalculationEngine.formatExactWeight(item.netWeightKg),
                    ),
                )
            }
        } else {
            out.write(
                tableRow4(
                    CalculationEngine.formatExactBoxes(delivery.boxes),
                    CalculationEngine.formatExactWeight(delivery.grossWeightKg),
                    CalculationEngine.formatExactWeight(delivery.tareDeductionKg),
                    CalculationEngine.formatExactWeight(delivery.netWeightKg),
                ),
            )
        }

        out.write(asciiLine("--------------------------------"))
        out.write(pairLine("Cajas:", CalculationEngine.formatExactBoxes(delivery.boxes)))
        out.write(pairLine("Tara total:", "${CalculationEngine.formatExactWeight(delivery.tareDeductionKg)} kg"))
        out.write(
            pairLine(
                if (secondColHeader == "ING.kg") "Peso ingresado:" else "Bruto total:",
                "${CalculationEngine.formatExactWeight(delivery.grossWeightKg)} kg",
            ),
        )

        out.write(asciiLine("================================"))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(pairLine("KILOS NETOS", "${CalculationEngine.formatExactWeight(delivery.netWeightKg)} kg"))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(asciiLine("================================"))

        if (hasMultiBrandOrPrice) {
            brandBreakdown.forEach { b ->
                wrapAsciiLines("${b.brand} ${CalculationEngine.formatExactWeight(b.netWeightKg)}kg x ${CalculationEngine.formatMoney(b.pricePerKg)}/kg")
                    .forEach { out.write(asciiLine(it)) }
                val rightSub = CalculationEngine.formatMoney(b.subtotalMxn.setScale(2, RoundingMode.HALF_UP))
                out.write(pairLine("  Importe ${b.brand.take(10)}:", rightSub))
            }
        } else {
            out.write(pairLine("Precio/kg:", CalculationEngine.formatMoney(delivery.pricePerKg)))
        }
        out.write(pairLine("Subtotal:", CalculationEngine.formatMoney(rawSubtotal)))
        if (roundingDiff.compareTo(BigDecimal.ZERO) != 0) {
            val sign = if (roundingDiff > BigDecimal.ZERO) "+" else "-"
            out.write(pairLine("Redondeo:", "$sign${CalculationEngine.formatMoney(roundingDiff.abs())}"))
        }
        out.write(asciiLine("--------------------------------"))

        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(pairLine("TOTAL", "${CalculationEngine.formatMoney(delivery.finalAmountMxn)} MXN"))
        wrapAsciiLines(CalculationEngine.amountInWordsMxn(delivery.finalAmountMxn)).forEach {
            out.write(asciiLine(it))
        }
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(asciiLine("--------------------------------"))

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

        out.write(asciiLine("--------------------------------"))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(pairLine("Saldo anterior:", "${CalculationEngine.formatMoney(delivery.previousDebtSnapshotMxn)} MXN"))
        out.write(pairLine("Esta remision:", "+${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN"))
        val totalDebt = delivery.previousDebtSnapshotMxn + delivery.pendingBalanceMxn
        out.write(pairLine("SALDO TOTAL", "${CalculationEngine.formatMoney(totalDebt)} MXN"))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))

        if (isCredit) {
            out.write(asciiLine("--------------------------------"))
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
                        out.write(bitmapToEscPosRaster(sigBmp, maxWidthPx = 320))
                    }
                }
            }
            out.write(asciiLine("Recibi de conformidad"))
            out.write(byteArrayOf(0x1B, 0x45, 0x01))
            wrapAsciiLines(delivery.clientName).forEach { out.write(asciiLine(it)) }
            out.write(byteArrayOf(0x1B, 0x45, 0x00))
        }

        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        out.write(asciiLine("--------------------------------"))
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write(asciiLine("!Gracias por su preferencia!"))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        out.write(asciiLine("Calidad y frescura en cada huevo"))

        out.write(byteArrayOf(0x0A, 0x0A, 0x0A))
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Cut if supported
        return out.toByteArray()
    }

    private fun tableRow4(c1: String, c2: String, c3: String, c4: String): ByteArray {
        val col1 = normalizeAscii(c1).take(5).padEnd(5, ' ')
        val col2 = normalizeAscii(c2).take(9).padStart(9, ' ')
        val col3 = normalizeAscii(c3).take(8).padStart(8, ' ')
        val col4 = normalizeAscii(c4).take(10).padStart(10, ' ')
        return (col1 + col2 + col3 + col4 + "\n").toByteArray(Charsets.US_ASCII)
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
