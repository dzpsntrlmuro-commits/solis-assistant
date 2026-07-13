package com.solis.assistant.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

// ---- Entity ----
@Entity(tableName = "audio_records")
data class AudioRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcript: String,
    val analysis: String,
    val suggestions: String,
    val timestamp: Long = System.currentTimeMillis(),
    val date: String // YYYY-MM-DD formatında
)

// ---- DAO ----
@Dao
interface AudioRecordDao {
    @Insert
    suspend fun insert(record: AudioRecord): Long

    @Query("SELECT * FROM audio_records WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): Flow<List<AudioRecord>>

    @Query("SELECT * FROM audio_records WHERE date = :date ORDER BY timestamp ASC")
    suspend fun getByDateSync(date: String): List<AudioRecord>

    @Query("SELECT * FROM audio_records ORDER BY timestamp DESC LIMIT 50")
    fun getRecent(): Flow<List<AudioRecord>>

    @Query("DELETE FROM audio_records WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

// ---- Database ----
@Database(entities = [AudioRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioRecordDao(): AudioRecordDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "solis_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
