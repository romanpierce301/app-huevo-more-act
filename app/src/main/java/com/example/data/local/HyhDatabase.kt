package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.HyhDao
import com.example.data.local.entity.AbonoEntity
import com.example.data.local.entity.BackupRecordEntity
import com.example.data.local.entity.BusinessSettingsEntity
import com.example.data.local.entity.ClientEntity
import com.example.data.local.entity.DailyClosingEntity
import com.example.data.local.entity.DeliveryEntity
import com.example.data.local.entity.EggPurchaseEntity
import com.example.data.local.entity.InventoryBrandEntity
import com.example.data.local.entity.ReminderEntity

@Database(
    entities = [
        ClientEntity::class,
        EggPurchaseEntity::class,
        InventoryBrandEntity::class,
        DeliveryEntity::class,
        AbonoEntity::class,
        ReminderEntity::class,
        DailyClosingEntity::class,
        BackupRecordEntity::class,
        BusinessSettingsEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class HyhDatabase : RoomDatabase() {
    abstract fun hyhDao(): HyhDao

    companion object {
        @Volatile
        private var INSTANCE: HyhDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE deliveries ADD COLUMN itemsSnapshotJson TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clients ADD COLUMN creditTermDays INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE business_settings ADD COLUMN creditTermDays INTEGER NOT NULL DEFAULT 7",
                )
                db.execSQL(
                    "ALTER TABLE business_settings ADD COLUMN creditMoratoriumInterestPercent REAL NOT NULL DEFAULT 0.0",
                )
                db.execSQL(
                    "ALTER TABLE business_settings ADD COLUMN creditPaymentPlace TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE business_settings ADD COLUMN creditLegendExtra TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE business_settings ADD COLUMN creditLegendClosing TEXT NOT NULL DEFAULT 'Válido con firma digital.'",
                )
            }
        }

        fun getInstance(context: Context): HyhDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HyhDatabase::class.java,
                    "hyh_huevos_local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
