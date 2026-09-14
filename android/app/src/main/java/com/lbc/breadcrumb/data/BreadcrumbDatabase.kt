package com.lbc.breadcrumb.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Memory::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class BreadcrumbDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

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
