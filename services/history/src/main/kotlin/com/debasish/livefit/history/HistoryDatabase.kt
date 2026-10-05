package com.debasish.livefit.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, DeltaEntity::class, SampleEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): HistoryDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries()
            else Room.databaseBuilder(context, HistoryDatabase::class.java, "livefit-history.db")).build()

        @Volatile private var instance: HistoryDatabase? = null

        /** One Room instance per process (two instances on one file break change notifications). */
        fun shared(context: Context): HistoryDatabase =
            instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }
    }
}
