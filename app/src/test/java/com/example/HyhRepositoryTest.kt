package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.HyhDatabase
import com.example.data.local.repository.HyhRepository
import com.example.domain.DeliveryModality
import com.example.domain.PaymentMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HyhRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: HyhDatabase
    private lateinit var repository: HyhRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HyhDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HyhRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `initial database contains zero preloaded clients deliveries or inventory`() = runTest {
        assertTrue(repository.observeClients().first().isEmpty())
        assertTrue(repository.observeDeliveries().first().isEmpty())
        assertTrue(repository.observePurchases().first().isEmpty())
        assertTrue(repository.observeInventoryBrands().first().isEmpty())
        assertTrue(repository.observeReminders().first().isEmpty())
        assertTrue(repository.observeDailyClosings().first().isEmpty())
        assertTrue(repository.observeBackups().first().isEmpty())
    }

    @Test
    fun `fractional inventory abonos and folio-preserving cancellation work atomically`() = runTest {
        repository.updateBusinessSettings(
            phone = "5512345678",
            address = "Bodega 1",
            slogan = "LOS MAS FRESCOS DE LA ZONA",
            globalPricePerKg = 40.0,
        )
        val clientId = repository.saveClient(
            name = "Abarrotes Test",
            phone = "",
            address = "Calle Hidalgo 10",
            specialPricePerKg = 38.0,
        )
        repository.registerPurchase(
            purchaseDateMillis = System.currentTimeMillis(),
            boxes = 40.0,
            brand = "San Juan",
            grossWeightKg = 920.0,
        )

        val sigBitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        val deliveryId = repository.registerDelivery(
            clientId = clientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "San Juan",
            boxes = 12.5,
            grossWeightKg = 275.0,
            tareDeductionKg = 25.0,
            netWeightKg = 250.0,
            pricePerKg = 38.0,
            rawAmountMxn = 9500.0,
            finalAmountMxn = 9500.0,
            paymentMethod = PaymentMethod.CREDITO,
            initialPaymentMxn = 1500.0,
            signatureBitmap = sigBitmap,
        )

        // Verify fractional inventory: 40.0 - 12.5 = 27.5
        val stockAfterDelivery = repository.observeInventoryBrands().first().first { it.brand == "San Juan" }
        assertEquals(27.5, stockAfterDelivery.remainingBoxes, 0.0001)

        val savedDelivery = repository.observeDeliveryById(deliveryId).first()
        assertNotNull(savedDelivery)
        assertEquals(8000.0, savedDelivery!!.pendingBalanceMxn, 0.01)
        assertNotNull(savedDelivery.signaturePath)
        assertTrue(File(savedDelivery.signaturePath!!).exists())

        // Record abono
        repository.recordAbono(clientId, deliveryId, 2000.0, "Abono en ruta")
        val afterAbono = repository.observeDeliveryById(deliveryId).first()!!
        assertEquals(6000.0, afterAbono.pendingBalanceMxn, 0.01)

        // Cancel delivery -> reverts inventory back to 40.0, preserves folio, zeroes debt
        repository.cancelDelivery(deliveryId, "Error de captura")
        val afterCancel = repository.observeDeliveryById(deliveryId).first()!!
        assertEquals("CANCELADA", afterCancel.status)
        assertEquals(savedDelivery.folio, afterCancel.folio)
        assertEquals(0.0, afterCancel.pendingBalanceMxn, 0.001)

        val stockAfterCancel = repository.observeInventoryBrands().first().first { it.brand == "San Juan" }
        assertEquals(40.0, stockAfterCancel.remainingBoxes, 0.0001)

        // Next delivery must NOT reuse cancelled folio
        val secondDeliveryId = repository.registerDelivery(
            clientId = clientId,
            modality = DeliveryModality.MEDIA_CAJA_PESADA,
            brand = "San Juan",
            boxes = 0.5,
            grossWeightKg = 15.0,
            tareDeductionKg = 0.5,
            netWeightKg = 14.5,
            pricePerKg = 38.0,
            rawAmountMxn = 551.0,
            finalAmountMxn = 551.0,
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = 551.0,
            signatureBitmap = null,
        )
        val secondDelivery = repository.observeDeliveryById(secondDeliveryId).first()!!
        assertTrue(secondDelivery.folio != savedDelivery.folio)
    }

    @Test
    fun `restore rejects zip slip traversal entries`() = runTest {
        val maliciousZip = File(context.cacheDir, "malicious.zip")
        ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
            zos.putNextEntry(ZipEntry("firmas/../../evil.png"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("datos_generales.json"))
            zos.write("""{"clients":[],"deliveries":[]}""".toByteArray())
            zos.closeEntry()
        }
        val result = repository.restoreBackupFromUri(Uri.fromFile(maliciousZip))
        // Either rejected with SecurityException or safely stripped to filename inside signatures dir without escaping
        val escaped = File(context.filesDir, "evil.png")
        assertTrue(!escaped.exists())
        maliciousZip.delete()
        if (result.isSuccess) {
            File(context.filesDir, "signatures/.._.._evil.png").delete()
        }
    }

    @Test
    fun `ticket pdf and escpos bytes generate cleanly for credit and cash deliveries`() = runTest {
        val clientId = repository.saveClient(
            name = "Tienda El Sol",
            phone = "5598765432",
            address = "Av. Juárez 22",
            specialPricePerKg = null,
        )
        repository.updateBusinessSettings(
            phone = "5500001111",
            address = "Nave 1 Bodega 5",
            slogan = "LOS MAS FRESCOS DE LA ZONA",
            globalPricePerKg = 45.0,
        )
        repository.registerPurchase(
            purchaseDateMillis = System.currentTimeMillis(),
            boxes = 20.0,
            brand = "Tehuacán",
            grossWeightKg = 480.0,
        )
        val sig = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        val creditId = repository.registerDelivery(
            clientId = clientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "Tehuacán",
            boxes = 2.0,
            grossWeightKg = 48.0,
            tareDeductionKg = 4.0,
            netWeightKg = 44.0,
            pricePerKg = 45.0,
            rawAmountMxn = 1980.0,
            finalAmountMxn = 1980.0,
            paymentMethod = PaymentMethod.CREDITO,
            initialPaymentMxn = 500.0,
            signatureBitmap = sig,
        )
        val creditDelivery = repository.observeDeliveryById(creditId).first()!!
        val settings = repository.getSettingsOnce()
        val client = repository.getClientOnce(clientId)
        val estHeight = com.example.system.PdfDocumentGenerator.measureTicketHeight(
            context = context,
            delivery = creditDelivery,
            settings = settings,
            client = client,
        )
        val pdfCanvasBmp = Bitmap.createBitmap(220, estHeight, Bitmap.Config.ARGB_8888)
        val drawnHeight = com.example.system.PdfDocumentGenerator.renderTicketToCanvas(
            context = context,
            canvas = android.graphics.Canvas(pdfCanvasBmp),
            delivery = creditDelivery,
            settings = settings,
            client = client,
        )
        assertTrue("Ticket height ($drawnHeight) must fit within measured height ($estHeight)", drawnHeight <= estHeight)

        val escPosBytes = com.example.system.ThermalPrinterManager(context)
            .buildEscPosTicketBytes(creditDelivery, settings, client)
        val asciiDump = String(escPosBytes, Charsets.US_ASCII)
        assertTrue(asciiDump.contains("COMPROBANTE DE REMISION"))
        assertTrue(asciiDump.contains("KILOS NETOS"))
        assertTrue(asciiDump.contains("RESTA ENTREGA"))
        assertTrue(asciiDump.contains("SALDO TOTAL"))
        assertTrue(asciiDump.contains("Recibi de conformidad"))
        assertTrue(asciiDump.contains("Tienda El Sol"))

        // Contado must NOT contain the credit commitment legend
        val cashId = repository.registerDelivery(
            clientId = clientId,
            modality = DeliveryModality.MEDIA_CAJA_PESADA,
            brand = "Tehuacán",
            boxes = 0.5,
            grossWeightKg = 11.5,
            tareDeductionKg = 0.5,
            netWeightKg = 11.0,
            pricePerKg = 45.0,
            rawAmountMxn = 495.0,
            finalAmountMxn = 495.0,
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = 495.0,
            signatureBitmap = null,
        )
        val cashDelivery = repository.observeDeliveryById(cashId).first()!!
        val cashEscPos = String(
            com.example.system.ThermalPrinterManager(context)
                .buildEscPosTicketBytes(cashDelivery, settings, client),
            Charsets.US_ASCII,
        )
        assertTrue(cashEscPos.contains("LIQUIDADO"))
        assertTrue(!cashEscPos.contains("Recibi de conformidad"))
        assertTrue(!cashEscPos.contains("fecha limite de pago"))
    }

    @Test
    fun `multi-box weighedItems persist render per-row on tickets and survive json backup restore`() = runTest {
        val clientId = repository.saveClient(
            name = "Distribuidora Norte",
            phone = "5511223344",
            address = "Calle Central 88",
            specialPricePerKg = 43.0,
        )
        repository.registerPurchase(
            purchaseDateMillis = System.currentTimeMillis(),
            boxes = 10.0,
            brand = "Blanco Selecto",
            grossWeightKg = 240.0,
        )

        val items = listOf(
            com.example.domain.CalculationEngine.calculateSingleBoxItem(1L, DeliveryModality.CAJA_COMPLETA, "24.250").getOrThrow(),
            com.example.domain.CalculationEngine.calculateSingleBoxItem(2L, DeliveryModality.MEDIA_CAJA, "22.000").getOrThrow(),
            com.example.domain.CalculationEngine.calculateSingleBoxItem(3L, DeliveryModality.MEDIA_CAJA_PESADA, "15.000").getOrThrow(),
        )
        val calc = com.example.domain.CalculationEngine.calculateDeliveryFromItems(
            items = items,
            pricePerKg = java.math.BigDecimal("43.00"),
        )
        val deliveryId = repository.registerDelivery(
            clientId = clientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "Blanco Selecto",
            boxes = calc.boxesDecimal.toDouble(),
            grossWeightKg = calc.inputWeightKg.toDouble(),
            tareDeductionKg = calc.tareDeductionKg.toDouble(),
            netWeightKg = calc.netWeightKg.toDouble(),
            pricePerKg = 43.0,
            rawAmountMxn = calc.rawAmountMxn.toDouble(),
            finalAmountMxn = calc.finalAmountMxn.toDouble(),
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = calc.finalAmountMxn.toDouble(),
            signatureBitmap = null,
            weighedItems = items,
        )

        val saved = repository.observeDeliveryById(deliveryId).first()!!
        assertEquals("MIXTA", saved.modality)
        val parsed = com.example.domain.CalculationEngine.parseWeighedItemsJson(saved.itemsSnapshotJson)
        assertEquals(3, parsed.size)

        val settings = repository.getSettingsOnce()
        val client = repository.getClientOnce(clientId)
        val estHeight = com.example.system.PdfDocumentGenerator.measureTicketHeight(
            context = context,
            delivery = saved,
            settings = settings,
            client = client,
        )
        val bmp = Bitmap.createBitmap(220, estHeight, Bitmap.Config.ARGB_8888)
        val drawn = com.example.system.PdfDocumentGenerator.renderTicketToCanvas(
            context = context,
            canvas = android.graphics.Canvas(bmp),
            delivery = saved,
            settings = settings,
            client = client,
        )
        assertTrue("Multi-row PDF height ($drawn) must fit ($estHeight)", drawn <= estHeight)

        val escPos = String(
            com.example.system.ThermalPrinterManager(context)
                .buildEscPosTicketBytes(saved, settings, client),
            Charsets.US_ASCII,
        )
        assertTrue(escPos.contains("01"))
        assertTrue(escPos.contains("02*"))
        assertTrue(escPos.contains("03*"))
        assertTrue(escPos.contains("Mixta"))

        // Export JSON backup and restore into DB cleanly
        val backupRecord = repository.exportFullJsonBackup()
        val restoreRes = repository.restoreFromLocalBackup(backupRecord)
        assertTrue(restoreRes.isSuccess)
        val restored = repository.observeDeliveryById(deliveryId).first()!!
        assertEquals(3, com.example.domain.CalculationEngine.parseWeighedItemsJson(restored.itemsSnapshotJson).size)
        File(backupRecord.filePath).delete()
    }

    @Test
    fun `migration 1 to 2 adds itemsSnapshotJson cleanly`() {
        val dbFile = File(context.cacheDir, "migration_v1_test.db").apply { delete() }
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS deliveries (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                folio TEXT NOT NULL,
                                clientId INTEGER NOT NULL,
                                clientName TEXT NOT NULL,
                                dateMillis INTEGER NOT NULL,
                                modality TEXT NOT NULL,
                                brand TEXT NOT NULL,
                                boxes REAL NOT NULL,
                                grossWeightKg REAL NOT NULL,
                                tareDeductionKg REAL NOT NULL,
                                netWeightKg REAL NOT NULL,
                                pricePerKg REAL NOT NULL,
                                rawAmountMxn REAL NOT NULL,
                                finalAmountMxn REAL NOT NULL,
                                paymentMethod TEXT NOT NULL,
                                initialPaymentMxn REAL NOT NULL,
                                pendingBalanceMxn REAL NOT NULL,
                                previousDebtSnapshotMxn REAL NOT NULL,
                                totalAccumulatedDebtSnapshotMxn REAL NOT NULL,
                                signaturePath TEXT,
                                status TEXT NOT NULL,
                                cancelledAtMillis INTEGER,
                                cancellationReason TEXT
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )
        val v1Db = helper.writableDatabase
        HyhDatabase.MIGRATION_1_2.migrate(v1Db)
        val cursor = v1Db.query("PRAGMA table_info(deliveries)")
        var foundCol = false
        while (cursor.moveToNext()) {
            val nameIdx = cursor.getColumnIndex("name")
            if (nameIdx >= 0 && cursor.getString(nameIdx) == "itemsSnapshotJson") {
                foundCol = true
            }
        }
        cursor.close()
        v1Db.close()
        dbFile.delete()
        assertTrue(foundCol)
    }

    @Test
    fun `registerDelivery persists egg price per kg across deliveries until edited`() = runTest {
        val regularClientId = repository.saveClient(
            name = "Cliente Regular",
            phone = "",
            address = "Calle 1",
            specialPricePerKg = null,
        )
        val specialClientId = repository.saveClient(
            name = "Cliente Especial",
            phone = "",
            address = "Calle 2",
            specialPricePerKg = 39.50,
        )

        // Initially 0.0 -> first delivery with 44.50 registers 44.50 in settings
        assertEquals(0.0, repository.getSettingsOnce().globalPricePerKg, 0.001)
        repository.registerDelivery(
            clientId = regularClientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "General",
            boxes = 1.0,
            grossWeightKg = 22.0,
            tareDeductionKg = 2.0,
            netWeightKg = 20.0,
            pricePerKg = 44.50,
            rawAmountMxn = 890.0,
            finalAmountMxn = 890.0,
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = 890.0,
            signatureBitmap = null,
        )
        assertEquals(44.50, repository.getSettingsOnce().globalPricePerKg, 0.001)

        // Delivery to client with special price keeps global 44.50 intact
        repository.registerDelivery(
            clientId = specialClientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "General",
            boxes = 1.0,
            grossWeightKg = 22.0,
            tareDeductionKg = 2.0,
            netWeightKg = 20.0,
            pricePerKg = 39.50,
            rawAmountMxn = 790.0,
            finalAmountMxn = 790.0,
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = 790.0,
            signatureBitmap = null,
        )
        assertEquals(44.50, repository.getSettingsOnce().globalPricePerKg, 0.001)

        // Editing price on a subsequent regular delivery updates registered price to 46.00
        repository.registerDelivery(
            clientId = regularClientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "General",
            boxes = 1.0,
            grossWeightKg = 22.0,
            tareDeductionKg = 2.0,
            netWeightKg = 20.0,
            pricePerKg = 46.00,
            rawAmountMxn = 920.0,
            finalAmountMxn = 920.0,
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = 920.0,
            signatureBitmap = null,
        )
        assertEquals(46.00, repository.getSettingsOnce().globalPricePerKg, 0.001)
    }

    @Test
    fun `multi-brand multi-price delivery deducts per-brand stock renders all prices and reverts both on cancel`() = runTest {
        val clientId = repository.saveClient(
            name = "Super La Esquina",
            phone = "5522334455",
            address = "Av. Principal 99",
            specialPricePerKg = null,
        )
        repository.registerPurchase(
            purchaseDateMillis = System.currentTimeMillis(),
            boxes = 10.0,
            brand = "Marca Alpha",
            grossWeightKg = 240.0,
        )
        repository.registerPurchase(
            purchaseDateMillis = System.currentTimeMillis(),
            boxes = 8.0,
            brand = "Marca Beta",
            grossWeightKg = 192.0,
        )

        val item1 = com.example.domain.CalculationEngine.calculateSingleBoxItem(
            id = 1L,
            modality = DeliveryModality.CAJA_COMPLETA,
            weightInputKg = "24.000",
            brand = "Marca Alpha",
            pricePerKg = java.math.BigDecimal("42.00"),
        ).getOrThrow()
        val item2 = com.example.domain.CalculationEngine.calculateSingleBoxItem(
            id = 2L,
            modality = DeliveryModality.MEDIA_CAJA_PESADA,
            weightInputKg = "15.500",
            brand = "Marca Beta",
            pricePerKg = java.math.BigDecimal("46.50"),
        ).getOrThrow()
        val items = listOf(item1, item2)
        val calc = com.example.domain.CalculationEngine.calculateDeliveryFromItems(
            items = items,
            pricePerKg = java.math.BigDecimal("46.50"),
        )

        val deliveryId = repository.registerDelivery(
            clientId = clientId,
            modality = DeliveryModality.CAJA_COMPLETA,
            brand = "Marca Alpha, Marca Beta",
            boxes = calc.boxesDecimal.toDouble(),
            grossWeightKg = calc.inputWeightKg.toDouble(),
            tareDeductionKg = calc.tareDeductionKg.toDouble(),
            netWeightKg = calc.netWeightKg.toDouble(),
            pricePerKg = calc.pricePerKg.toDouble(),
            rawAmountMxn = calc.rawAmountMxn.toDouble(),
            finalAmountMxn = calc.finalAmountMxn.toDouble(),
            paymentMethod = PaymentMethod.CONTADO,
            initialPaymentMxn = calc.finalAmountMxn.toDouble(),
            signatureBitmap = null,
            weighedItems = items,
        )

        val alphaStock = repository.observeInventoryBrands().first().first { it.brand == "Marca Alpha" }
        val betaStock = repository.observeInventoryBrands().first().first { it.brand == "Marca Beta" }
        assertEquals(9.0, alphaStock.remainingBoxes, 0.0001)
        assertEquals(7.5, betaStock.remainingBoxes, 0.0001)

        val saved = repository.observeDeliveryById(deliveryId).first()!!
        assertEquals("Marca Alpha, Marca Beta", saved.brand)
        val settings = repository.getSettingsOnce()
        val client = repository.getClientOnce(clientId)
        val estHeight = com.example.system.PdfDocumentGenerator.measureTicketHeight(
            context = context,
            delivery = saved,
            settings = settings,
            client = client,
        )
        val bmp = Bitmap.createBitmap(220, estHeight, Bitmap.Config.ARGB_8888)
        val drawn = com.example.system.PdfDocumentGenerator.renderTicketToCanvas(
            context = context,
            canvas = android.graphics.Canvas(bmp),
            delivery = saved,
            settings = settings,
            client = client,
        )
        assertTrue("Multi-brand PDF height ($drawn) must fit ($estHeight)", drawn <= estHeight)

        val escPos = String(
            com.example.system.ThermalPrinterManager(context)
                .buildEscPosTicketBytes(saved, settings, client),
            Charsets.US_ASCII,
        )
        assertTrue(escPos.contains("Marca Alpha"))
        assertTrue(escPos.contains("\$42.00/kg"))
        assertTrue(escPos.contains("Marca Beta"))
        assertTrue(escPos.contains("\$46.50/kg"))

        // Cancel delivery must revert BOTH brands accurately
        repository.cancelDelivery(deliveryId, "Ajuste multi-marca")
        val alphaAfterCancel = repository.observeInventoryBrands().first().first { it.brand == "Marca Alpha" }
        val betaAfterCancel = repository.observeInventoryBrands().first().first { it.brand == "Marca Beta" }
        assertEquals(10.0, alphaAfterCancel.remainingBoxes, 0.0001)
        assertEquals(8.0, betaAfterCancel.remainingBoxes, 0.0001)
    }
}
