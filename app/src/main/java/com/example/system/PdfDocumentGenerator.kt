package com.example.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.R
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.data.local.entity.DailyClosingEntity
import com.example.data.local.entity.DeliveryEntity
import com.example.domain.CalculationEngine
import com.example.domain.DeliveryModality
import com.example.domain.PaymentMethod
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfDocumentGenerator {

    private val dateFormatter = SimpleDateFormat("dd 'de' MMMM, yyyy", Locale("es", "MX"))
    private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.US)

    fun generateTicketPdf(
        context: Context,
        delivery: DeliveryEntity,
        settings: BusinessSettingsEntity,
        client: ClientEntity? = null,
    ): File {
        val dir = File(context.filesDir, "tickets").apply { mkdirs() }
        val file = File(dir, "Ticket_${delivery.folio}.pdf")

        // 48mm thermal receipt proportional canvas
        val pageWidth = 220
        val margin = 12f
        val contentWidth = (pageWidth - margin * 2).toInt()

        val logoRaw = BitmapFactory.decodeResource(context.resources, R.drawable.img_ticket_logo)
        val signatureRaw = delivery.signaturePath?.let { path ->
            val sigFile = File(path)
            if (sigFile.exists()) BitmapFactory.decodeFile(sigFile.absolutePath) else null
        }

        val estimatedHeight = measureTicketHeight(
            context = context,
            pageWidth = pageWidth,
            margin = margin,
            delivery = delivery,
            settings = settings,
            client = client,
            logoRaw = logoRaw,
            signatureRaw = signatureRaw,
        )

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, estimatedHeight, 1).create()
        val page = pdf.startPage(pageInfo)
        renderTicketToCanvas(
            context = context,
            canvas = page.canvas,
            pageWidth = pageWidth,
            margin = margin,
            delivery = delivery,
            settings = settings,
            client = client,
            logoRaw = logoRaw,
            signatureRaw = signatureRaw,
        )

        pdf.finishPage(page)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    internal fun renderTicketToCanvas(
        context: Context,
        canvas: Canvas,
        pageWidth: Int = 220,
        margin: Float = 12f,
        delivery: DeliveryEntity,
        settings: BusinessSettingsEntity,
        client: ClientEntity? = null,
        logoRaw: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.img_ticket_logo),
        signatureRaw: Bitmap? = delivery.signaturePath?.let { path ->
            val sigFile = File(path)
            if (sigFile.exists()) BitmapFactory.decodeFile(sigFile.absolutePath) else null
        },
    ): Float {
        val contentWidth = (pageWidth - margin * 2).toInt()
        canvas.drawColor(Color.WHITE)

        val monoRegular = Typeface.MONOSPACE
        val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 7.5f
            typeface = monoRegular
        }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 7.5f
            typeface = monoBold
        }
        val thickLinePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.6f
        }
        val thinLinePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 0.8f
        }
        val dashPaint = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 0.8f
        }

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
        val currentAccum = delivery.previousDebtSnapshotMxn + delivery.pendingBalanceMxn
        val effectivePaidMxn = if (!isCancelled) {
            (delivery.finalAmountMxn - delivery.pendingBalanceMxn).coerceIn(0.0, delivery.finalAmountMxn)
        } else {
            delivery.initialPaymentMxn
        }
        val rawSubtotal = BigDecimal.valueOf(delivery.rawAmountMxn).setScale(2, RoundingMode.HALF_UP)
        val finalBd = BigDecimal.valueOf(delivery.finalAmountMxn).setScale(2, RoundingMode.HALF_UP)
        val roundingDiff = finalBd.subtract(rawSubtotal).setScale(2, RoundingMode.HALF_UP)
        val statusText = when {
            isCancelled -> "CANCELADO"
            delivery.pendingBalanceMxn > 0.0 -> "ENTREGADO / PENDIENTE LIQUIDACIÓN"
            else -> "LIQUIDADO"
        }

        var y = 10f

        // Logotipo térmico 1-bit con nombre y lema integrados
        if (logoRaw != null) {
            val targetW = contentWidth
            val targetH = ((logoRaw.height.toFloat() / logoRaw.width.toFloat()) * targetW)
                .toInt()
                .coerceAtLeast(40)
            val scaledLogo = Bitmap.createScaledBitmap(logoRaw, targetW, targetH, true)
            canvas.drawBitmap(scaledLogo, margin, y, null)
            y += targetH + 5f
        }

        if (settings.address.isNotBlank()) {
            y = drawWrapped(canvas, settings.address, paint.apply { textSize = 7f }, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)
        }
        if (settings.phone.isNotBlank()) {
            y = drawWrapped(canvas, "Tel: ${settings.phone}", paint.apply { textSize = 7f }, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)
        }

        y += 4f
        canvas.drawLine(margin, y, pageWidth - margin, y, thickLinePaint)
        y += 10f

        if (isCancelled) {
            val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 13f
                typeface = monoBold
                textAlign = Paint.Align.CENTER
            }
            val boxPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 1.6f
            }
            canvas.drawRect(margin, y - 9f, pageWidth - margin, y + 9f, boxPaint)
            canvas.drawText("CANCELADO", pageWidth / 2f, y + 4f, cancelPaint)
            y += 18f
        }

        boldPaint.textSize = 8.5f
        y = drawWrapped(canvas, "COMPROBANTE DE REMISIÓN", boldPaint, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)
        boldPaint.textSize = 10.5f
        y = drawWrapped(canvas, delivery.folio, boldPaint, margin, y + 1f, contentWidth, Layout.Alignment.ALIGN_CENTER)
        y += 4f

        paint.textSize = 7.5f
        boldPaint.textSize = 7.5f
        y = drawRow(canvas, "Fecha:", dateFormatter.format(Date(delivery.dateMillis)), margin, pageWidth - margin, paint, boldPaint, y)
        y = drawRow(canvas, "Hora:", timeFormatter.format(Date(delivery.dateMillis)), margin, pageWidth - margin, paint, boldPaint, y)
        y += 2f
        boldPaint.textSize = 7f
        y = drawWrapped(canvas, statusText, boldPaint, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)

        y = drawDashedLine(canvas, margin, pageWidth - margin, y + 5f, dashPaint) + 8f

        // Bloque CLIENTE
        boldPaint.textSize = 7.5f
        y = drawWrapped(canvas, "CLIENTE", boldPaint, margin, y, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        boldPaint.textSize = 8.5f
        y = drawWrapped(canvas, delivery.clientName, boldPaint, margin, y, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        paint.textSize = 7f
        if (!client?.address.isNullOrBlank()) {
            y = drawWrapped(canvas, client?.address.orEmpty(), paint, margin, y, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        }
        if (!client?.phone.isNullOrBlank()) {
            y = drawWrapped(canvas, "Tel: ${client?.phone.orEmpty()}", paint, margin, y, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        }

        y = drawDashedLine(canvas, margin, pageWidth - margin, y + 5f, dashPaint) + 8f

        // Producto + Tabla 4 columnas (No./CANT | BRUTO/ING.kg | TARA | NETO kg)
        paint.textSize = 7.2f
        y = drawWrapped(canvas, "$headerBrandSummary • $modalityLabel", paint, margin, y, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        y += 6f

        val colCantX = margin
        val colBrutoX = margin + contentWidth * 0.44f
        val colTaraX = margin + contentWidth * 0.70f
        val colNetoX = pageWidth - margin

        boldPaint.textSize = 6.8f
        val rightBold = Paint(boldPaint).apply { textAlign = Paint.Align.RIGHT }
        val rightReg = Paint(paint).apply {
            textSize = 7.2f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(if (weighedItems.isNotEmpty()) "No." else "CANT", colCantX, y, boldPaint)
        canvas.drawText(secondColHeader, colBrutoX, y, rightBold)
        canvas.drawText("TARA", colTaraX, y, rightBold)
        canvas.drawText("NETO kg", colNetoX, y, rightBold)
        y += 3f
        canvas.drawLine(margin, y, pageWidth - margin, y, thinLinePaint)
        y += 10f

        boldPaint.textSize = 7.2f
        rightBold.textSize = 7.2f
        if (weighedItems.isNotEmpty()) {
            val groupBoldPaint = Paint(boldPaint).apply { textSize = 6.7f }
            var prevGroupKey: Pair<String, BigDecimal>? = null
            weighedItems.forEachIndexed { idx, item ->
                if (hasMultiBrandOrPrice) {
                    val effBrand = item.brand.trim().ifBlank { delivery.brand.ifBlank { "General" } }
                    val effPrice = (if (item.pricePerKg > BigDecimal.ZERO) item.pricePerKg else BigDecimal.valueOf(delivery.pricePerKg))
                        .stripTrailingZeros()
                    val groupKey = effBrand to effPrice
                    if (groupKey != prevGroupKey) {
                        prevGroupKey = groupKey
                        if (idx > 0) y += 2f
                        canvas.drawText(
                            "$effBrand (${CalculationEngine.formatMoney(effPrice)}/kg)".take(34),
                            margin,
                            y,
                            groupBoldPaint,
                        )
                        y += 10f
                    }
                }
                val numLabel = buildString {
                    append(String.format(Locale.US, "%02d", idx + 1))
                    if (item.modality != DeliveryModality.CAJA_COMPLETA) append(" 1/2")
                }
                canvas.drawText(numLabel, colCantX, y, boldPaint)
                canvas.drawText(CalculationEngine.formatExactWeight(item.inputWeightKg), colBrutoX, y, rightReg)
                canvas.drawText(CalculationEngine.formatExactWeight(item.tareDeductionKg), colTaraX, y, rightReg)
                canvas.drawText(CalculationEngine.formatExactWeight(item.netWeightKg), colNetoX, y, rightBold)
                if (idx < weighedItems.lastIndex) {
                    y += 10f
                }
            }
        } else {
            canvas.drawText(CalculationEngine.formatExactBoxes(delivery.boxes), colCantX, y, boldPaint)
            canvas.drawText(CalculationEngine.formatExactWeight(delivery.grossWeightKg), colBrutoX, y, rightReg)
            canvas.drawText(CalculationEngine.formatExactWeight(delivery.tareDeductionKg), colTaraX, y, rightReg)
            canvas.drawText(CalculationEngine.formatExactWeight(delivery.netWeightKg), colNetoX, y, rightBold)
        }

        y = drawDashedLine(canvas, margin, pageWidth - margin, y + 6f, dashPaint) + 10f

        paint.textSize = 7.5f
        boldPaint.textSize = 7.5f
        y = drawRow(canvas, "Cajas:", CalculationEngine.formatExactBoxes(delivery.boxes), margin, pageWidth - margin, paint, boldPaint, y)
        y = drawRow(canvas, "Tara total:", "${CalculationEngine.formatExactWeight(delivery.tareDeductionKg)} kg", margin, pageWidth - margin, paint, boldPaint, y)
        y = drawRow(
            canvas,
            if (secondColHeader == "ING.kg") "Peso ingresado:" else "Bruto total:",
            "${CalculationEngine.formatExactWeight(delivery.grossWeightKg)} kg",
            margin,
            pageWidth - margin,
            paint,
            boldPaint,
            y,
        )

        // Recuadro KILOS NETOS
        y += 2f
        val boxStroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        val netBoxTop = y - 6f
        val netBoxBottom = y + 10f
        canvas.drawRect(RectF(margin, netBoxTop, pageWidth - margin, netBoxBottom), boxStroke)
        boldPaint.textSize = 8.2f
        drawRow(
            canvas,
            "KILOS NETOS",
            "${CalculationEngine.formatExactWeight(delivery.netWeightKg)} kg",
            margin + 5f,
            pageWidth - margin - 5f,
            boldPaint,
            boldPaint,
            y + 5f,
        )
        y = netBoxBottom + 12f

        if (hasMultiBrandOrPrice) {
            paint.textSize = 6.9f
            boldPaint.textSize = 6.9f
            brandBreakdown.forEach { b ->
                val leftPart = "${b.brand.take(11)} ${CalculationEngine.formatMoney(b.pricePerKg)}/kg"
                val rightPart = "${CalculationEngine.formatExactWeight(b.netWeightKg)}kg ${CalculationEngine.formatMoney(b.subtotalMxn.setScale(2, RoundingMode.HALF_UP))}"
                y = drawRow(canvas, leftPart, rightPart, margin, pageWidth - margin, paint, boldPaint, y)
            }
            paint.textSize = 7.5f
            boldPaint.textSize = 7.5f
        } else {
            paint.textSize = 7.5f
            boldPaint.textSize = 7.5f
            y = drawRow(canvas, "Precio por kilo:", CalculationEngine.formatMoney(delivery.pricePerKg), margin, pageWidth - margin, paint, boldPaint, y)
        }
        y = drawRow(canvas, "Subtotal:", CalculationEngine.formatMoney(rawSubtotal), margin, pageWidth - margin, paint, boldPaint, y)
        if (roundingDiff.compareTo(BigDecimal.ZERO) != 0) {
            val sign = if (roundingDiff > BigDecimal.ZERO) "+" else "-"
            y = drawRow(
                canvas,
                "Redondeo:",
                "$sign${CalculationEngine.formatMoney(roundingDiff.abs())}",
                margin,
                pageWidth - margin,
                paint,
                boldPaint,
                y,
            )
        }

        y += 2f
        canvas.drawLine(margin, y, pageWidth - margin, y, thickLinePaint)
        y += 14f

        boldPaint.textSize = 10f
        y = drawRow(canvas, "TOTAL", "${CalculationEngine.formatMoney(delivery.finalAmountMxn)} MXN", margin, pageWidth - margin, boldPaint, boldPaint, y)
        boldPaint.textSize = 7f
        y = drawWrapped(canvas, CalculationEngine.amountInWordsMxn(delivery.finalAmountMxn), boldPaint, margin, y - 4f, contentWidth, Layout.Alignment.ALIGN_NORMAL)

        y += 4f
        canvas.drawLine(margin, y, pageWidth - margin, y, thickLinePaint)
        y += 12f

        paint.textSize = 7.5f
        boldPaint.textSize = 7.5f
        y = drawRow(canvas, "Pagado / Abono:", "${CalculationEngine.formatMoney(effectivePaidMxn)} MXN", margin, pageWidth - margin, paint, boldPaint, y)
        paint.textSize = 7f
        y = drawWrapped(canvas, "Forma de pago: ${PaymentMethod.fromDb(delivery.paymentMethod).label}", paint, margin, y - 4f, contentWidth, Layout.Alignment.ALIGN_NORMAL)

        // Barra negra invertida RESTA ENTREGA / LIQUIDADO
        y += 4f
        val barTop = y
        val barBottom = y + 16f
        val fillBlack = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8f
            typeface = monoBold
        }
        canvas.drawRect(RectF(margin, barTop, pageWidth - margin, barBottom), fillBlack)
        drawRow(
            canvas,
            if (delivery.pendingBalanceMxn > 0.0) "RESTA ENTREGA" else "LIQUIDADO",
            "${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN",
            margin + 5f,
            pageWidth - margin - 5f,
            whiteBold,
            whiteBold,
            barTop + 11f,
        )
        y = barBottom

        y = drawDashedLine(canvas, margin, pageWidth - margin, y + 7f, dashPaint) + 12f

        boldPaint.textSize = 7.5f
        y = drawRow(canvas, "Saldo anterior:", "${CalculationEngine.formatMoney(delivery.previousDebtSnapshotMxn)} MXN", margin, pageWidth - margin, boldPaint, boldPaint, y)
        y = drawRow(canvas, "Esta remisión:", "+${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN", margin, pageWidth - margin, boldPaint, boldPaint, y)
        boldPaint.textSize = 8.5f
        y = drawRow(canvas, "SALDO TOTAL", "${CalculationEngine.formatMoney(currentAccum)} MXN", margin, pageWidth - margin, boldPaint, boldPaint, y + 1f)

        // Solo Crédito (pendiente o liquidado) lleva leyenda + firmabox + Recibí de conformidad
        if (isCredit) {
            y = drawDashedLine(canvas, margin, pageWidth - margin, y + 4f, dashPaint) + 8f
            val legendText = when {
                isCancelled -> "Nota cancelada. Las obligaciones de pago y entrega correspondientes fueron revertidas."
                delivery.pendingBalanceMxn > 0.0 ->
                    CalculationEngine.buildPendingCreditLegendSegments(
                        deliveryDateMillis = delivery.dateMillis,
                        pendingBalanceMxn = delivery.pendingBalanceMxn,
                        totalDebtMxn = currentAccum,
                        effectiveTermDays = client?.creditTermDays ?: settings.creditTermDays,
                        moratoriumInterestPercent = settings.creditMoratoriumInterestPercent,
                        paymentPlace = settings.creditPaymentPlace,
                        extraCondition = settings.creditLegendExtra,
                        closingNote = settings.creditLegendClosing,
                    ).joinToString("") { it.text }
                else ->
                    "Recibí la mercancía descrita en esta remisión a mi entera satisfacción y cubrí el importe total señalado. Acepto mi firma digitalizada como constancia de recepción."
            }
            paint.textSize = 6.5f
            y = drawWrapped(canvas, legendText, paint, margin, y, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            y += 4f

            val sigBoxHeight = 56f
            val sigBoxTop = y
            val sigBoxBottom = y + sigBoxHeight
            canvas.drawRect(RectF(margin, sigBoxTop, pageWidth - margin, sigBoxBottom), thinLinePaint.apply { style = Paint.Style.STROKE })
            if (signatureRaw != null && signatureRaw.width > 0 && signatureRaw.height > 0) {
                val maxSigW = (contentWidth - 8).toFloat()
                val maxSigH = sigBoxHeight - 6f
                val ratio = minOf(maxSigW / signatureRaw.width.toFloat(), maxSigH / signatureRaw.height.toFloat())
                val drawW = (signatureRaw.width * ratio).toInt().coerceAtLeast(1)
                val drawH = (signatureRaw.height * ratio).toInt().coerceAtLeast(1)
                val scaledSig = Bitmap.createScaledBitmap(signatureRaw, drawW, drawH, true)
                val left = margin + (contentWidth - drawW) / 2f
                val top = sigBoxTop + (sigBoxHeight - drawH) / 2f
                canvas.drawBitmap(scaledSig, left, top, null)
            }
            y = sigBoxBottom + 6f

            paint.textSize = 7f
            y = drawWrapped(canvas, "Recibí de conformidad", paint, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)
            boldPaint.textSize = 7.5f
            y = drawWrapped(canvas, delivery.clientName, boldPaint, margin, y - 2f, contentWidth, Layout.Alignment.ALIGN_CENTER)
        }

        y = drawDashedLine(canvas, margin, pageWidth - margin, y + 6f, dashPaint) + 8f
        boldPaint.textSize = 8f
        y = drawWrapped(canvas, "¡Gracias por su preferencia!", boldPaint, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)
        paint.textSize = 7f
        y = drawWrapped(canvas, "Calidad y frescura en cada huevo", paint, margin, y, contentWidth, Layout.Alignment.ALIGN_CENTER)
        return y + 12f
    }

    fun generateDailyClosingPdf(
        context: Context,
        closing: DailyClosingEntity,
        deliveries: List<DeliveryEntity>,
        settings: BusinessSettingsEntity,
    ): File {
        val dir = File(context.filesDir, "cierres").apply { mkdirs() }
        val file = File(dir, "Cierre_${closing.folio}.pdf")

        val pageWidth = 595
        val margin = 36f
        val contentWidth = (pageWidth - margin * 2).toInt()
        val pageHeight = (520 + deliveries.size * 34).coerceAtLeast(842)

        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 11f
        }
        val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10.5f
        }
        val divider = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }

        var y = 44f
        canvas.drawText("${settings.businessName} — CIERRE DIARIO", margin, y, titlePaint)
        y += 18f
        canvas.drawText(settings.slogan, margin, y, subtitlePaint)
        y += 16f
        canvas.drawText("Folio Cierre: ${closing.folio}   |   Fecha: ${closing.dateKey}", margin, y, boldPaint)
        y += 14f
        canvas.drawLine(margin, y, pageWidth - margin, y, divider)
        y += 22f

        val summaryRows = listOf(
            "Total de entregas activas" to closing.totalDeliveries.toString(),
            "Clientes atendidos" to closing.clientsServedCount.toString(),
            "Cajas entregadas" to "${CalculationEngine.formatExactBoxes(closing.totalBoxesDelivered)} cajas",
            "Kilogramos entregados" to "${CalculationEngine.formatExactWeight(closing.totalKgDelivered)} kg",
            "Ventas de contado" to "${CalculationEngine.formatMoney(closing.cashSalesMxn)} MXN",
            "Ventas a crédito" to "${CalculationEngine.formatMoney(closing.creditSalesMxn)} MXN",
            "Abonos recibidos" to "${CalculationEngine.formatMoney(closing.abonosReceivedMxn)} MXN",
            "Dinero cobrado total" to "${CalculationEngine.formatMoney(closing.totalMoneyCollectedMxn)} MXN",
            "Deudas pendientes totales" to "${CalculationEngine.formatMoney(closing.pendingDebtsMxn)} MXN",
        )
        for ((label, value) in summaryRows) {
            y = drawRow(canvas, label, value, margin, pageWidth - margin, bodyPaint, boldPaint, y)
            y += 4f
        }

        y += 10f
        canvas.drawLine(margin, y, pageWidth - margin, y, divider)
        y += 20f
        canvas.drawText("Detalle de Entregas", margin, y, boldPaint.apply { textSize = 13f })
        y += 16f

        bodyPaint.textSize = 9.5f
        boldPaint.textSize = 9.5f
        for (d in deliveries) {
            if (y > pageHeight - 60f) break
            val statusSuffix = if (d.status == "CANCELADA") " [CANCELADO]" else ""
            val line1 = "${d.folio}$statusSuffix — ${d.clientName}"
            val line2 = "  ${CalculationEngine.formatExactBoxes(d.boxes)} cjs | ${CalculationEngine.formatExactWeight(d.netWeightKg)} kg | ${PaymentMethod.fromDb(d.paymentMethod).label} | ${CalculationEngine.formatMoney(d.finalAmountMxn)} MXN"
            canvas.drawText(line1.take(80), margin, y, boldPaint)
            y += 13f
            canvas.drawText(line2.take(contentWidth), margin, y, bodyPaint)
            y += 17f
        }

        pdf.finishPage(page)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    internal fun measureTicketHeight(
        context: Context,
        pageWidth: Int = 220,
        margin: Float = 12f,
        delivery: DeliveryEntity,
        settings: BusinessSettingsEntity,
        client: ClientEntity?,
        logoRaw: Bitmap? = BitmapFactory.decodeResource(context.resources, R.drawable.img_ticket_logo),
        signatureRaw: Bitmap? = delivery.signaturePath?.let { path ->
            val sigFile = File(path)
            if (sigFile.exists()) BitmapFactory.decodeFile(sigFile.absolutePath) else null
        },
    ): Int {
        val picture = android.graphics.Picture()
        val recCanvas = picture.beginRecording(pageWidth, 4000)
        val measured = renderTicketToCanvas(
            context = context,
            canvas = recCanvas,
            pageWidth = pageWidth,
            margin = margin,
            delivery = delivery,
            settings = settings,
            client = client,
            logoRaw = logoRaw,
            signatureRaw = signatureRaw,
        )
        picture.endRecording()
        return kotlin.math.ceil(measured.toDouble()).toInt().coerceAtLeast(480)
    }

    private fun drawDashedLine(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        y: Float,
        paint: Paint,
    ): Float {
        var x = startX
        val dash = 5f
        val gap = 4f
        while (x < endX) {
            canvas.drawLine(x, y, (x + dash).coerceAtMost(endX), y, paint)
            x += dash + gap
        }
        return y
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: Paint,
        leftX: Float,
        startY: Float,
        maxWidth: Int,
        alignment: Layout.Alignment,
    ): Float {
        val tp = TextPaint(paint)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, maxWidth)
            .setAlignment(alignment)
            .setLineSpacing(1f, 1.08f)
            .build()
        canvas.save()
        canvas.translate(leftX, startY)
        layout.draw(canvas)
        canvas.restore()
        return startY + layout.height + 2f
    }

    private fun drawRow(
        canvas: Canvas,
        left: String,
        right: String,
        leftX: Float,
        rightX: Float,
        leftPaint: Paint,
        rightPaint: Paint,
        y: Float,
    ): Float {
        val leftMaxLen = 22
        val safeLeft = if (left.length > leftMaxLen) left.take(leftMaxLen - 1) + "…" else left
        val rightMaxLen = 26
        val safeRight = if (right.length > rightMaxLen) right.take(rightMaxLen - 1) + "…" else right
        canvas.drawText(safeLeft, leftX, y, leftPaint)
        val rightCopy = Paint(rightPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(safeRight, rightX, y, rightCopy)
        return y + 12f
    }
}
