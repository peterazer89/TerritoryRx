package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.BrickEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.DailyPlanEntity
import com.example.data.model.HcpEntity
import com.example.data.model.ImportLogEntity
import com.example.data.model.ImportMappingProfileEntity
import com.example.data.model.PlannedStopEntity
import com.example.data.model.RouteCacheEntity
import com.example.data.model.VisitEntity

@Database(
    entities = [
        HcpEntity::class,
        ClinicEntity::class,
        BrickEntity::class,
        AvailabilityWindowEntity::class,
        VisitEntity::class,
        DailyPlanEntity::class,
        PlannedStopEntity::class,
        ImportLogEntity::class,
        RouteCacheEntity::class,
        ChatMessageEntity::class,
        ImportMappingProfileEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun territoryDao(): TerritoryDao
    abstract fun hcpDao(): HcpDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bricks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalizedName` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_bricks_normalizedName` ON `bricks` (`normalizedName`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `originalExcelScheduleText` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `normalizedDaysCsv` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `exactStartMinute` INTEGER")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `exactEndMinute` INTEGER")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `timeInfoType` TEXT NOT NULL DEFAULT 'CLINIC_AVAILABILITY_RANGE'")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `bookingInstructions` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `aiInterpretedScheduleText` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `availability_windows` ADD COLUMN `endsNextDay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `scheduleClassification` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `excludedDaysCsv` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `parsingMethod` TEXT NOT NULL DEFAULT 'DETERMINISTIC'")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `parsingConfidence` REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `validationStatus` TEXT NOT NULL DEFAULT 'VALID'")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `isManualUserConfirmed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `locationDescription` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `sourceFile` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `sourceSheet` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `clinics` ADD COLUMN `sourceRow` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `clinics` SET `isManualUserConfirmed` = 1, `parsingMethod` = 'USER_CONFIRMED' WHERE `scheduleParseStatus` = 'CONFIRMED'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `clinics_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `hcpId` INTEGER NOT NULL,
                        `clinicIndex` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `zone` TEXT NOT NULL,
                        `originalLocationText` TEXT NOT NULL,
                        `googleMapsUrl` TEXT NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `geocodingStatus` TEXT NOT NULL,
                        `rawScheduleText` TEXT NOT NULL,
                        `originalExcelScheduleText` TEXT NOT NULL,
                        `normalizedDaysCsv` TEXT NOT NULL,
                        `excludedDaysCsv` TEXT NOT NULL,
                        `exactStartMinute` INTEGER,
                        `exactEndMinute` INTEGER,
                        `timeInfoType` TEXT NOT NULL,
                        `scheduleClassification` TEXT NOT NULL,
                        `bookingInstructions` TEXT NOT NULL,
                        `aiInterpretedScheduleText` TEXT NOT NULL,
                        `parsingMethod` TEXT NOT NULL,
                        `parsingConfidence` REAL NOT NULL,
                        `validationStatus` TEXT NOT NULL,
                        `locationDescription` TEXT NOT NULL,
                        `sourceFile` TEXT NOT NULL,
                        `sourceSheet` TEXT NOT NULL,
                        `sourceRow` INTEGER NOT NULL,
                        `sourceColumn` TEXT NOT NULL,
                        `scheduleParseStatus` TEXT NOT NULL,
                        `scheduleAmbiguityReason` TEXT NOT NULL,
                        `userConfirmedSchedule` INTEGER NOT NULL,
                        `isManualUserConfirmed` INTEGER NOT NULL,
                        `lastVerifiedEpochMs` INTEGER,
                        `customVisitDurationMinutes` INTEGER,
                        `isActive` INTEGER NOT NULL,
                        FOREIGN KEY(`hcpId`) REFERENCES `hcps`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `clinics_new` (
                        `id`, `hcpId`, `clinicIndex`, `label`, `zone`, `originalLocationText`, `googleMapsUrl`,
                        `latitude`, `longitude`, `geocodingStatus`, `rawScheduleText`, `originalExcelScheduleText`,
                        `normalizedDaysCsv`, `excludedDaysCsv`, `exactStartMinute`, `exactEndMinute`, `timeInfoType`,
                        `scheduleClassification`, `bookingInstructions`, `aiInterpretedScheduleText`, `parsingMethod`,
                        `parsingConfidence`, `validationStatus`, `locationDescription`, `sourceFile`, `sourceSheet`,
                        `sourceRow`, `sourceColumn`, `scheduleParseStatus`, `scheduleAmbiguityReason`,
                        `userConfirmedSchedule`, `isManualUserConfirmed`, `lastVerifiedEpochMs`,
                        `customVisitDurationMinutes`, `isActive`
                    )
                    SELECT
                        `id`, `hcpId`, `clinicIndex`, `label`, `zone`, `originalLocationText`, `googleMapsUrl`,
                        `latitude`, `longitude`, `geocodingStatus`, `rawScheduleText`, `originalExcelScheduleText`,
                        `normalizedDaysCsv`, `excludedDaysCsv`, `exactStartMinute`, `exactEndMinute`, `timeInfoType`,
                        `scheduleClassification`, `bookingInstructions`, `aiInterpretedScheduleText`, `parsingMethod`,
                        `parsingConfidence`, `validationStatus`, `locationDescription`, `sourceFile`, `sourceSheet`,
                        `sourceRow`, '', `scheduleParseStatus`, `scheduleAmbiguityReason`,
                        `userConfirmedSchedule`, `isManualUserConfirmed`, `lastVerifiedEpochMs`,
                        `customVisitDurationMinutes`, `isActive`
                    FROM `clinics`
                    WHERE `hcpId` IN (SELECT `id` FROM `hcps`)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `clinics`")
                db.execSQL("ALTER TABLE `clinics_new` RENAME TO `clinics`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clinics_hcpId` ON `clinics` (`hcpId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `availability_windows_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `clinicId` INTEGER NOT NULL,
                        `dayOfWeek` INTEGER NOT NULL,
                        `startMinuteOfDay` INTEGER NOT NULL,
                        `endMinuteOfDay` INTEGER NOT NULL,
                        `endsNextDay` INTEGER NOT NULL,
                        `isConfirmed` INTEGER NOT NULL,
                        `isException` INTEGER NOT NULL,
                        `exceptionDateIso` TEXT NOT NULL,
                        `hcpId` INTEGER NOT NULL,
                        `originalExcelText` TEXT NOT NULL,
                        `parsedTimeWindows` TEXT NOT NULL,
                        `sourceSheet` TEXT NOT NULL,
                        `sourceRow` INTEGER NOT NULL,
                        `sourceColumn` TEXT NOT NULL,
                        `parsingStatus` TEXT NOT NULL,
                        FOREIGN KEY(`clinicId`) REFERENCES `clinics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `availability_windows_new` (
                        `id`, `clinicId`, `dayOfWeek`, `startMinuteOfDay`, `endMinuteOfDay`,
                        `endsNextDay`, `isConfirmed`, `isException`, `exceptionDateIso`,
                        `hcpId`, `originalExcelText`, `parsedTimeWindows`, `sourceSheet`,
                        `sourceRow`, `sourceColumn`, `parsingStatus`
                    )
                    SELECT
                        w.`id`, w.`clinicId`, w.`dayOfWeek`, w.`startMinuteOfDay`, w.`endMinuteOfDay`,
                        w.`endsNextDay`, w.`isConfirmed`, w.`isException`, w.`exceptionDateIso`,
                        COALESCE(c.`hcpId`, 0),
                        COALESCE(c.`originalExcelScheduleText`, ''),
                        CAST(w.`dayOfWeek` AS TEXT) || ':' || CAST(w.`startMinuteOfDay` AS TEXT) || '-' || CAST(w.`endMinuteOfDay` AS TEXT),
                        COALESCE(c.`sourceSheet`, ''),
                        COALESCE(c.`sourceRow`, 0),
                        COALESCE(c.`sourceColumn`, ''),
                        COALESCE(c.`scheduleParseStatus`, 'CONFIRMED')
                    FROM `availability_windows` w
                    INNER JOIN `clinics` c ON c.`id` = w.`clinicId`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `availability_windows`")
                db.execSQL("ALTER TABLE `availability_windows_new` RENAME TO `availability_windows`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_availability_windows_clinicId` ON `availability_windows` (`clinicId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_availability_windows_hcpId` ON `availability_windows` (`hcpId`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "territory_rx_database.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
