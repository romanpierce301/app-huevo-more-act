package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String,
    val specialPricePerKg: Double? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "egg_purchases")
data class EggPurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseDateMillis: Long,
    val boxes: Double,
    val brand: String,
    val grossWeightKg: Double,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "inventory_brands")
data class InventoryBrandEntity(
    @PrimaryKey val brand: String,
    val availableBoxes: Double = 0.0,
    val deliveredBoxes: Double = 0.0,
) {
    val remainingBoxes: Double
        get() = (java.math.BigDecimal.valueOf(availableBoxes)
            .subtract(java.math.BigDecimal.valueOf(deliveredBoxes))).toDouble()
}

@Entity(tableName = "deliveries")
data class DeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folio: String,
    val clientId: Long,
    val clientName: String,
    val dateMillis: Long,
    val modality: String,
    val brand: String,
    val boxes: Double,
    val grossWeightKg: Double,
    val tareDeductionKg: Double,
    val netWeightKg: Double,
    val pricePerKg: Double,
    val rawAmountMxn: Double,
    val finalAmountMxn: Double,
    val paymentMethod: String,
    val initialPaymentMxn: Double,
    val pendingBalanceMxn: Double,
    val previousDebtSnapshotMxn: Double,
    val totalAccumulatedDebtSnapshotMxn: Double,
    val signaturePath: String? = null,
    val status: String = "ACTIVA",
    val cancelledAtMillis: Long? = null,
    val cancellationReason: String? = null,
    val itemsSnapshotJson: String = "[]",
)

@Entity(tableName = "abonos")
data class AbonoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val deliveryId: Long,
    val deliveryFolio: String,
    val amountMxn: Double,
    val dateMillis: Long = System.currentTimeMillis(),
    val note: String = "",
    val isCancelled: Boolean = false,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val clientName: String,
    val deliveryDateMillis: Long,
    val boxesNote: String = "",
    val note: String = "",
    val isCompleted: Boolean = false,
    val completedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "daily_closings")
data class DailyClosingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folio: String,
    val dateKey: String,
    val createdAtMillis: Long,
    val totalDeliveries: Int,
    val totalBoxesDelivered: Double,
    val totalKgDelivered: Double,
    val cashSalesMxn: Double,
    val creditSalesMxn: Double,
    val abonosReceivedMxn: Double,
    val totalMoneyCollectedMxn: Double,
    val pendingDebtsMxn: Double,
    val clientsServedCount: Int,
    val movementsSnapshotJson: String,
    val pdfPath: String,
    val jsonPath: String,
    val hashSignature: String,
)

@Entity(tableName = "backup_records")
data class BackupRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val format: String,
    val sizeBytes: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "business_settings")
data class BusinessSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val businessName: String = "H y H HUEVOS",
    val slogan: String = "LOS MAS FRESCOS DE LA ZONA",
    val phone: String = "",
    val address: String = "",
    val globalPricePerKg: Double = 0.0,
    val printerType: String = "BLUETOOTH",
    val printerName: String = "",
    val printerAddress: String = "",
    val nextFolioSequence: Int = 1,
    val nextClosingSequence: Int = 1,
)
