package com.lbc.breadcrumb.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Memory::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // v2: hasLink, so a memory can carry a LINK chip beside its primary type
        AutoMigration(from = 1, to = 2, spec = BreadcrumbDatabase.BackfillHasLink::class),
        // v3: sourceAppLabel. No backfill -- a label needs PackageManager, not
        // SQL, and rows without one fall back to showing the package name.
        AutoMigration(from = 2, to = 3),
    ],
)
@TypeConverters(Converters::class)
abstract class BreadcrumbDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    /**
     * Rows saved before v2 start with hasLink = 0. Backfill them, and move text
     * that carries a URL to LINK, which is what the capture rules now produce
     * for the same share. LIKE approximates [com.lbc.breadcrumb.capture.UrlText]
     * closely enough for a one-time pass.
     */
    class BackfillHasLink : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE memories SET hasLink = 1 " +
                    "WHERE rawText LIKE '%http://%' OR rawText LIKE '%https://%' OR rawText LIKE '%www.%'"
            )
            db.execSQL("UPDATE memories SET type = 'LINK' WHERE type = 'TEXT' AND hasLink = 1")
        }
    }

    companion object {
        private const val DB_NAME = "breadcrumb.db"

        @Volatile
        private var instance: BreadcrumbDatabase? = null

        fun get(context: Context): BreadcrumbDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context) =
            Room.databaseBuilder(
                context.applicationContext,
                BreadcrumbDatabase::class.java,
                DB_NAME,
            ).build()
    }
}
