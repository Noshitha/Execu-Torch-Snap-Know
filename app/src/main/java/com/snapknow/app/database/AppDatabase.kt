package com.snapknow.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.snapknow.app.database.dao.FaceMemoryDao
import com.snapknow.app.database.dao.ObjectMemoryDao
import com.snapknow.app.database.entity.FaceMemory
import com.snapknow.app.database.entity.ObjectMemory

@Database(
    entities = [ObjectMemory::class, FaceMemory::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun objectMemoryDao(): ObjectMemoryDao
    abstract fun faceMemoryDao(): FaceMemoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "snapknow_memory.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
