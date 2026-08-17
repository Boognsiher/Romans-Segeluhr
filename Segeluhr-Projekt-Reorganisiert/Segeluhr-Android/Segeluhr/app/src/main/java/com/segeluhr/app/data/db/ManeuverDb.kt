package com.segeluhr.app.data.db

import androidx.room.*
import com.segeluhr.app.core.Constants
import com.segeluhr.app.data.model.ManeuverRecord
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "maneuvers")
data class ManeuverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val isTack: Boolean,
    val durationS: Double,
    val speedLossPct: Double,
    val score: Double,
    val timestampMs: Long,
)

fun ManeuverEntity.toRecord() = ManeuverRecord(id, isTack, durationS, speedLossPct, score, timestampMs)
fun ManeuverRecord.toEntity() = ManeuverEntity(id, isTack, durationS, speedLossPct, score, timestampMs)

@Dao
interface ManeuverDao {
    // Neueste zuerst, begrenzt auf MANEUVER_LOG_SIZE (Abschnitt 6.3: Ringpuffer)
    @Query("SELECT * FROM maneuvers ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = Constants.MANEUVER_LOG_SIZE): Flow<List<ManeuverEntity>>

    @Insert
    suspend fun insert(entity: ManeuverEntity)

    // Ringpuffer-Verhalten: älteste Einträge über das Limit hinaus entfernen
    @Query(
        """DELETE FROM maneuvers WHERE id NOT IN
           (SELECT id FROM maneuvers ORDER BY timestampMs DESC LIMIT :limit)"""
    )
    suspend fun trimTo(limit: Int = Constants.MANEUVER_LOG_SIZE)

    @Query("DELETE FROM maneuvers")
    suspend fun clearAll()
}

// Version 2 (17.08.2026, siehe docs/Erweiterung_Tages_Auswertung.md): neue
// "sessions"-Tabelle für die Tages-/Wettfahrt-Auswertung. Keine echte
// Migration geschrieben (fallbackToDestructiveMigration unten) - App ist
// noch in der Hardware-Testphase, ein einmaliger Verlust des bisherigen
// Manöver-Logs beim ersten Start nach diesem Update ist bewusst in Kauf
// genommen statt einer für ein einzelnes Gerät unnötigen Migration.
@Database(entities = [ManeuverEntity::class, SessionEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun maneuverDao(): ManeuverDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "segeluhr.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
