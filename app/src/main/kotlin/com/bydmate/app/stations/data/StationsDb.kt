package com.bydmate.app.stations.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * Отдельная база для станций, НЕ bydmate.db: слепок сервера живёт своей
 * жизнью (полная замена на каждый ответ 200) и не должен участвовать в
 * миграциях основной схемы приложения.
 */

@Entity(tableName = "stations", indices = [Index("network")])
data class StationEntity(
    /** «сеть:родной_id» — стабильный ключ сервера. */
    @PrimaryKey val stationId: String,
    val network: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    /** \n-разделитель: список короткий, отдельная таблица — лишняя сущность. */
    val promotions: List<String>,
    /** null — занятость протухла (status_stale), см. Station.isStale. */
    val free: Int?,
    val busy: Int?,
    val offline: Int?,
    val total: Int,
    val statusStale: Boolean,
    val statusUpdatedAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "connectors",
    foreignKeys = [
        ForeignKey(
            entity = StationEntity::class,
            parentColumns = ["stationId"],
            childColumns = ["stationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("stationId")],
)
data class ConnectorEntity(
    /** "<stationId>#<нативный id>" — id коннектора у сервера уникален лишь внутри станции. */
    @PrimaryKey val connectorId: String,
    val stationId: String,
    /** ConnectorType.name с сервера; незнакомое значение станет UNKNOWN при чтении. */
    val type: String,
    val rawType: String?,
    val power: Double,
    val price: Double?,
    val priceText: String?,
)

data class StationWithConnectors(
    @Embedded val station: StationEntity,
    @Relation(parentColumn = "stationId", entityColumn = "stationId")
    val connectors: List<ConnectorEntity>,
)

@Dao
interface StationsDao {

    @Transaction
    @Query("SELECT * FROM stations")
    fun observeAll(): Flow<List<StationWithConnectors>>

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(items: List<StationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectors(items: List<ConnectorEntity>)

    @Query("DELETE FROM stations WHERE stationId NOT IN (:keep)")
    suspend fun deleteStale(keep: List<String>)

    /**
     * Полная замена слепка одной транзакцией. Станции, пропавшие из ответа,
     * удаляются (каскадом — их коннекторы). Пустой список НЕ очищает базу:
     * пустой ответ почти наверняка сбой, а офлайн-данные ценнее.
     */
    @Transaction
    suspend fun replaceAll(stations: List<StationEntity>, connectors: List<ConnectorEntity>) {
        if (stations.isEmpty()) return
        insertStations(stations)
        insertConnectors(connectors)
        deleteStale(stations.map { it.stationId })
    }
}

class StationsConverters {
    @TypeConverter
    fun listToString(value: List<String>?): String = value.orEmpty().joinToString("\n")

    @TypeConverter
    fun stringToList(value: String?): List<String> =
        value?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
}

@Database(
    entities = [StationEntity::class, ConnectorEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(StationsConverters::class)
abstract class StationsDatabase : RoomDatabase() {
    abstract fun dao(): StationsDao

    companion object {
        fun build(context: Context): StationsDatabase =
            Room.databaseBuilder(context, StationsDatabase::class.java, "chargekg.db")
                // Слепок сервера можно пересоздать одним запросом — миграции
                // при смене схемы не нужны.
                .fallbackToDestructiveMigration()
                .build()
    }
}
