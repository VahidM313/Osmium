package com.example.osmium

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
@Entity(
    tableName = "cell_info",
    indices = [Index(value = ["cid", "operator", "gen", "mnc", "mcc","latitude", "longitude"], unique = true)]
)
data class CellInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val cid: Int,
    val operator: String,
    val gen: String,
    val mnc: String?,
    val mcc: String?,
    var rss: Int,
    var distance: Double,
    val latitude: Double,
    val longitude: Double
)

@Dao
interface CellInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cellInfo: CellInfoEntity): Long

    @Query("SELECT * FROM cell_info ORDER BY id DESC")
    fun getAllCellInfo(): Flow<List<CellInfoEntity>>

    @Query("SELECT * FROM cell_info WHERE cid = :cid AND operator = :operator AND gen = :gen AND mnc = :mnc AND mcc = :mcc AND rss = :rss AND distance = :distance AND latitude = :latitude AND longitude = :longitude LIMIT 1")
    suspend fun findExistingCellInfo(cid: Int, operator: String, gen: String, mnc: String?, mcc: String?, rss: Int, distance: Double, latitude: Double, longitude: Double): CellInfoEntity?

    @Update
    suspend fun update(cellInfo: CellInfoEntity)

    @Query("SELECT * FROM cell_info GROUP BY cid HAVING COUNT(*) >= 3")
    suspend fun getAllCellInfoGrouped(): List<CellInfoEntity>

    @Query("SELECT * FROM cell_info WHERE cid = :cid")
    suspend fun getCellInfoByCid(cid: Int): List<CellInfoEntity>

    @Query("SELECT * FROM cell_info WHERE cid = :cid ORDER BY id DESC LIMIT 1")
    suspend fun getMostRecentCellInfoByCid(cid: Int): CellInfoEntity?
}

@Entity(tableName = "cell_towers")
data class CellTowerEntity(
    @PrimaryKey val cellId: Int,
    val operator: String,
    val gen: String,
    val mnc: String?,
    val mcc: String?,
    val latitude: Double,
    val longitude: Double
)

@Dao
interface CellTowerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cellTower: CellTowerEntity)

    @Query("SELECT * FROM cell_towers")
    fun getAllCellTowers(): Flow<List<CellTowerEntity>>

    @Query("SELECT * FROM cell_towers WHERE cellId = :cellId LIMIT 1")
    suspend fun getCellTowerById(cellId: Int): CellTowerEntity?
}

@Database(entities = [CellInfoEntity::class, CellTowerEntity::class], version = 9)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cellInfoDao(): CellInfoDao
    abstract fun cellTowerDao(): CellTowerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "cell_info_database"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}