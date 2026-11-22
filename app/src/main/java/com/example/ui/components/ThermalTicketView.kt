package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.data.local.entity.DeliveryEntity
import com.example.domain.CalculationEngine
import com.example.domain.DeliveryModality
import com.example.domain.PaymentMethod
import com.example.ui.theme.SpaceMonoFontFamily
import com.example.ui.theme.ThermalAlertRed
import com.example.ui.theme.ThermalDivider
import com.example.ui.theme.ThermalInk
import com.example.ui.theme.ThermalInkDark
import com.example.ui.theme.ThermalInkMuted
import com.example.ui.theme.ThermalPaper
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plantilla visual exacta de Ticket Térmico 48 mm (384 puntos) basada en Vista_previa_ticket_con_logo / Ticket_48mm.
 * Cumple reglas del Documento Maestro:
 * - Logotipo 1-bit con nombre comercial integrado (sin repetir el nombre como texto suelto).
 * - Saldo pendiente y deuda total en negritas.
 * - Contado: sin firma ni leyenda de compromiso.
 * - Crédito: incluye firma digital escalada y leyenda de compromiso de pago.
 * - Cancelado: sello CANCELADO prominente en negritas.
 */
@Composable
fun ThermalTicket48mmView(
    delivery: DeliveryEntity,
    client: ClientEntity?,
    settings: BusinessSettingsEntity,
    signatureBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val dateFmt = SimpleDateFormat("dd 'de' MMMM, yyyy", Locale("es", "MX"))
    val timeFmt = SimpleDateFormat("hh:mm a", Locale.US)
    val isCancelled = delivery.status == "CANCELADA"
    val isCredit = delivery.paymentMethod == PaymentMethod.CREDITO.dbValue
    val currentTotalDebt = delivery.previousDebtSnapshotMxn + delivery.pendingBalanceMxn

    val rawSubtotal = BigDecimal.valueOf(delivery.rawAmountMxn).setScale(2, RoundingMode.HALF_UP)
    val finalBd = BigDecimal.valueOf(delivery.finalAmountMxn).setScale(2, RoundingMode.HALF_UP)
    val roundingDiff = finalBd.subtract(rawSubtotal).setScale(2, RoundingMode.HALF_UP)

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
    val statusText = when {
        isCancelled -> "CANCELADO"
        delivery.pendingBalanceMxn > 0.0 -> "ENTREGADO / PENDIENTE LIQUIDACIÓN"
        else -> "LIQUIDADO"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 350.dp)
            .testTag("thermal_ticket_preview"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SerratedEdge(isTop = true)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ThermalPaper)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo térmico 1-bit enviado por el usuario (incluye H&H Huevos y LOS MÁS FRESCOS DE LA ZONA)
            Image(
                painter = painterResource(R.drawable.img_ticket_logo),
                contentDescription = "Logotipo térmico H y H HUEVOS",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))
            if (settings.address.isNotBlank()) {
                Text(
                    text = settings.address,
                    fontFamily = SpaceMonoFontFamily,
                    fontSize = 9.5.sp,
                    color = ThermalInkDark,
                    textAlign = TextAlign.Center,
                )
            }
            if (settings.phone.isNotBlank()) {
                Text(
                    text = "Tel: ${settings.phone}",
                    fontFamily = SpaceMonoFontFamily,
                    fontSize = 9.5.sp,
                    color = ThermalInkDark,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = ThermalInkDark, thickness = 2.dp)
            Spacer(Modifier.height(8.dp))

            if (isCancelled) {
                Surface(
                    color = ThermalAlertRed.copy(alpha = 0.12f),
                    border = BorderStroke(2.dp, ThermalAlertRed),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ticket_cancelled_banner"),
                ) {
                    Text(
                        text = "CANCELADO",
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = ThermalAlertRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = "COMPROBANTE DE REMISIÓN",
                fontFamily = SpaceMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                color = ThermalInkDark,
                textAlign = TextAlign.Center,
            )
            Text(
                text = delivery.folio,
                fontFamily = SpaceMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = ThermalInkDark,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))
            TicketPairRow("Fecha:", dateFmt.format(Date(delivery.dateMillis)), boldValue = true)
            TicketPairRow("Hora:", timeFmt.format(Date(delivery.dateMillis)), boldValue = true)
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText,
                fontFamily = SpaceMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                color = if (isCancelled) ThermalAlertRed else ThermalInkDark,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))
            DashedTicketDivider()
            Spacer(Modifier.height(8.dp))

            // Bloque CLIENTE
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "CLIENTE",
                    fontFamily = SpaceMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = ThermalInkDark,
                )
                Text(
                    text = delivery.clientName,
                    fontFamily = SpaceMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = ThermalInkDark,
                )
                if (!client?.address.isNullOrBlank()) {
                    Text(
                        text = client?.address.orEmpty(),
                        fontFamily = SpaceMonoFontFamily,
                        fontSize = 9.5.sp,
                        color = ThermalInk,
                    )
                }
                if (!client?.phone.isNullOrBlank()) {
                    Text(
                        text = "Tel: ${client?.phone.orEmpty()}",
                        fontFamily = SpaceMonoFontFamily,
                        fontSize = 9.5.sp,
                        color = ThermalInk,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            DashedTicketDivider()
            Spacer(Modifier.height(8.dp))

            // Producto y tabla de pesaje exacta (Bruto / Tara / Neto)
            Text(
                text = "$headerBrandSummary • $modalityLabel",
                fontFamily = SpaceMonoFontFamily,
                fontSize = 9.5.sp,
                color = ThermalInkDark,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))

            // Encabezado de tabla de 4 columnas
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (weighedItems.isNotEmpty()) "No." else "CANT",
                    fontFamily = SpaceMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = ThermalInkDark,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    secondColHeader,
                    fontFamily = SpaceMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = ThermalInkDark,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text("TARA", fontFamily = SpaceMonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = ThermalInkDark, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                Text("NETO kg", fontFamily = SpaceMonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = ThermalInkDark, textAlign = TextAlign.End, modifier = Modifier.weight(1.1f))
            }
            HorizontalDivider(color = ThermalInkDark, thickness = 1.dp, modifier = Modifier.padding(vertical = 3.dp))
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
                            Text(
                                text = "$effBrand (${CalculationEngine.formatMoney(effPrice)}/kg)",
                                fontFamily = SpaceMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.5.sp,
                                color = ThermalInkDark,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (idx > 0) 3.dp else 0.dp, bottom = 1.dp),
                            )
                        }
                    }
                    val numLabel = buildString {
                        append(String.format(Locale.US, "%02d", idx + 1))
                        if (item.modality != DeliveryModality.CAJA_COMPLETA) {
                            append(" ½")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    ) {
                        Text(
                            text = numLabel,
                            fontFamily = SpaceMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp,
                            color = ThermalInkDark,
                            modifier = Modifier.weight(0.8f),
                        )
                        Text(
                            text = CalculationEngine.formatExactWeight(item.inputWeightKg),
                            fontFamily = SpaceMonoFontFamily,
                            fontSize = 9.5.sp,
                            color = ThermalInkDark,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = CalculationEngine.formatExactWeight(item.tareDeductionKg),
                            fontFamily = SpaceMonoFontFamily,
                            fontSize = 9.5.sp,
                            color = ThermalInkDark,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.9f),
                        )
                        Text(
                            text = CalculationEngine.formatExactWeight(item.netWeightKg),
                            fontFamily = SpaceMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp,
                            color = ThermalInkDark,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1.1f),
                        )
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = CalculationEngine.formatExactBoxes(delivery.boxes),
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = ThermalInkDark,
                        modifier = Modifier.weight(0.8f),
                    )
                    Text(
                        text = CalculationEngine.formatExactWeight(delivery.grossWeightKg),
                        fontFamily = SpaceMonoFontFamily,
                        fontSize = 10.sp,
                        color = ThermalInkDark,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = CalculationEngine.formatExactWeight(delivery.tareDeductionKg),
                        fontFamily = SpaceMonoFontFamily,
                        fontSize = 10.sp,
                        color = ThermalInkDark,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.9f),
                    )
                    Text(
                        text = CalculationEngine.formatExactWeight(delivery.netWeightKg),
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = ThermalInkDark,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            DashedTicketDivider()
            Spacer(Modifier.height(6.dp))

            TicketPairRow("Cajas:", CalculationEngine.formatExactBoxes(delivery.boxes), boldValue = true)
            TicketPairRow("Tara total:", "${CalculationEngine.formatExactWeight(delivery.tareDeductionKg)} kg", boldValue = true)
            TicketPairRow(
                if (secondColHeader == "ING.kg") "Peso ingresado:" else "Bruto total:",
                "${CalculationEngine.formatExactWeight(delivery.grossWeightKg)} kg",
                boldValue = true,
            )

            Spacer(Modifier.height(6.dp))
            // Recuadro destacado KILOS NETOS
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, ThermalInkDark)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "KILOS NETOS",
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = ThermalInkDark,
                    )
                    Text(
                        text = "${CalculationEngine.formatExactWeight(delivery.netWeightKg)} kg",
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = ThermalInkDark,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            if (hasMultiBrandOrPrice) {
                brandBreakdown.forEach { b ->
                    TicketPairRow(
                        label = "${b.brand} (${CalculationEngine.formatExactWeight(b.netWeightKg)} kg × ${CalculationEngine.formatMoney(b.pricePerKg)}):",
                        value = CalculationEngine.formatMoney(b.subtotalMxn.setScale(2, RoundingMode.HALF_UP)),
                        boldValue = true,
                        fontSize = 9,
                    )
                }
            } else {
                TicketPairRow("Precio por kilo:", CalculationEngine.formatMoney(delivery.pricePerKg), boldValue = true)
            }
            TicketPairRow("Subtotal:", CalculationEngine.formatMoney(rawSubtotal), boldValue = true)
            if (roundingDiff.compareTo(BigDecimal.ZERO) != 0) {
                val sign = if (roundingDiff > BigDecimal.ZERO) "+" else "-"
                TicketPairRow(
                    "Redondeo:",
                    "$sign${CalculationEngine.formatMoney(roundingDiff.abs())}",
                    boldValue = true,
                )
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = ThermalInkDark, thickness = 2.dp)
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TOTAL",
                    fontFamily = SpaceMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = ThermalInkDark,
                )
                Text(
                    text = "${CalculationEngine.formatMoney(delivery.finalAmountMxn)} MXN",
                    fontFamily = SpaceMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = ThermalInkDark,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = CalculationEngine.amountInWordsMxn(delivery.finalAmountMxn),
                fontFamily = SpaceMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = ThermalInkDark,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = ThermalInkDark, thickness = 2.dp)
            Spacer(Modifier.height(6.dp))

            TicketPairRow(
                "Pagado / Abono:",
                "${CalculationEngine.formatMoney(delivery.initialPaymentMxn)} MXN",
                boldValue = true,
            )
            Text(
                text = "Forma de pago: ${PaymentMethod.fromDb(delivery.paymentMethod).label}",
                fontFamily = SpaceMonoFontFamily,
                fontSize = 9.5.sp,
                color = ThermalInk,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))
            // Barra negra invertida (RESTA ENTREGA / LIQUIDADO) como en la plantilla HTML/PNG
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ThermalInkDark)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (delivery.pendingBalanceMxn > 0.0) "RESTA ENTREGA" else "LIQUIDADO",
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White,
                    )
                    Text(
                        text = "${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN",
                        fontFamily = SpaceMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            DashedTicketDivider()
            Spacer(Modifier.height(6.dp))

            TicketPairRow(
                "Saldo anterior:",
                "${CalculationEngine.formatMoney(delivery.previousDebtSnapshotMxn)} MXN",
                boldLabel = true,
                boldValue = true,
            )
            TicketPairRow(
                "Esta entrega:",
                "+${CalculationEngine.formatMoney(delivery.pendingBalanceMxn)} MXN",
                boldLabel = true,
                boldValue = true,
            )
            TicketPairRow(
                "SALDO TOTAL",
                "${CalculationEngine.formatMoney(currentTotalDebt)} MXN",
                boldLabel = true,
                boldValue = true,
                fontSize = 12,
                valueColor = if (currentTotalDebt > 0.0 && !isCancelled) ThermalAlertRed else ThermalInkDark,
            )

            // Solo Crédito lleva firma digital y leyenda legal de compromiso (Sección 7.2 y 7.3)
            if (isCredit) {
                Spacer(Modifier.height(10.dp))
                DashedTicketDivider()
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, ThermalDivider, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                        .testTag("ticket_credit_signature_box"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (signatureBitmap != null) {
                        Image(
                            bitmap = signatureBitmap.asImageBitmap(),
                            contentDescription = "Firma digital del cliente",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = "Firma registrada localmente",
                            fontFamily = SpaceMonoFontFamily,
                            fontSize = 9.sp,
                            color = ThermalInkMuted,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = ThermalInkDark, thickness = 0.8.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Recibí de conformidad\n${delivery.clientName}",
                    fontFamily = SpaceMonoFontFamily,
                    fontSize = 9.5.sp,
                    color = ThermalInkDark,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "\"${stringResource(R.string.credit_commitment_legend)}\"",
                    fontFamily = SpaceMonoFontFamily,
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                    color = ThermalInk,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(10.dp))
            DashedTicketDivider()
            Spacer(Modifier.height(6.dp))
            Text(
                text = "¡Gracias por su preferencia!",
                fontFamily = SpaceMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = ThermalInkDark,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Calidad y frescura en cada huevo",
                fontFamily = SpaceMonoFontFamily,
                fontSize = 9.sp,
                color = ThermalInkMuted,
                textAlign = TextAlign.Center,
            )
        }
        SerratedEdge(isTop = false)
    }
}

@Composable
private fun TicketPairRow(
    label: String,
    value: String,
    boldLabel: Boolean = false,
    boldValue: Boolean = false,
    fontSize: Int = 10,
    valueColor: Color = ThermalInkDark,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontFamily = SpaceMonoFontFamily,
            fontWeight = if (boldLabel) FontWeight.Bold else FontWeight.Normal,
            fontSize = fontSize.sp,
            color = ThermalInkDark,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontFamily = SpaceMonoFontFamily,
            fontWeight = if (boldValue) FontWeight.Bold else FontWeight.Normal,
            fontSize = fontSize.sp,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DashedTicketDivider() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
    ) {
        var x = 0f
        val dash = 8f
        val gap = 6f
        while (x < size.width) {
            drawLine(
                color = ThermalInkDark,
                start = androidx.compose.ui.geometry.Offset(x, 1f),
                end = androidx.compose.ui.geometry.Offset((x + dash).coerceAtMost(size.width), 1f),
                strokeWidth = 1.5f,
            )
            x += dash + gap
        }
    }
}

@Composable
private fun SerratedEdge(isTop: Boolean) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val toothW = 14f
        val count = (size.width / toothW).toInt().coerceAtLeast(8)
        val step = size.width / count
        val path = Path()
        if (isTop) {
            path.moveTo(0f, size.height)
            for (i in 0 until count) {
                path.lineTo(i * step + step / 2f, 0f)
                path.lineTo((i + 1) * step, size.height)
            }
            path.close()
        } else {
            path.moveTo(0f, 0f)
            for (i in 0 until count) {
                path.lineTo(i * step + step / 2f, size.height)
                path.lineTo((i + 1) * step, 0f)
            }
            path.close()
        }
        drawPath(path = path, color = ThermalPaper)
    }
}
