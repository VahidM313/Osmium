package com.example.osmium

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.osmium.ui.theme.OsmiumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.gms.location.*
import android.os.Looper
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

class MainActivity : ComponentActivity() {
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var database: AppDatabase
    private var lastLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = AppDatabase.getInstance(this)

        setContent {
            OsmiumTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }

        setupLocationUpdates()
        startCellInfoCollection()
    }

    @Composable
    fun MainScreen() {
        var selectedTabIndex by remember { mutableStateOf(0) }
        val tabs = listOf("Cell Info", "Database Rows")

        Column {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(text = title) },
                        icon = {
                            when (index) {
                                0 -> Icon(Icons.Default.Info, contentDescription = null)
                                1 -> Icon(Icons.Default.List, contentDescription = null)
                            }
                        }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> CellInfoScreen()
                1 -> DatabaseRowsScreen()
            }
        }
    }

    @Composable
    fun CellInfoScreen() {
        var cellInfoList by remember { mutableStateOf<List<CellInfo>>(emptyList()) }

        LaunchedEffect(Unit) {
            while (true) {
                cellInfoList = getCellInfo()
                delay(1000) // Update every second
            }
        }

        LazyColumn {
            items(cellInfoList) { cellInfo ->
                Text(text = formatCellInfo(cellInfo))
            }
        }
    }

    @Composable
    fun DatabaseRowsScreen() {
        val cellInfoEntities by database.cellInfoDao().getAllCellInfo().collectAsState(initial = emptyList())

        LazyColumn {
            items(cellInfoEntities) { entity ->
                Text(text = formatDatabaseRow(entity))
            }
        }
    }

    private fun formatDatabaseRow(entity: CellInfoEntity): String {
        return "ID: ${entity.id}, CID: ${entity.cid}, MNC: ${entity.mnc}, MCC: ${entity.mcc}, " +
                "RSS: ${entity.rss}, Lat: ${entity.latitude}, Long: ${entity.longitude}"
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(2f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    lastLocation = location
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun startCellInfoCollection() {
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val cellInfo = getCellInfo()
                val location = lastLocation
                if (cellInfo.isNotEmpty() && location != null) {
                    saveCellInfo(cellInfo.first(), location)
                }
                delay(1000) // Collect info every second
            }
        }
    }

    private suspend fun saveCellInfo(cellInfo: CellInfo, location: Location) {
        val cellInfoEntity = when (cellInfo) {
            is android.telephony.CellInfoGsm -> {
                val cellIdentity = cellInfo.cellIdentity
                CellInfoEntity(
                    cid = cellIdentity.cid,
                    mnc = cellIdentity.mncString,
                    mcc = cellIdentity.mccString,
                    rss = cellInfo.cellSignalStrength.dbm,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
            is android.telephony.CellInfoLte -> {
                val cellIdentity = cellInfo.cellIdentity
                CellInfoEntity(
                    cid = cellIdentity.ci,
                    mnc = cellIdentity.mncString,
                    mcc = cellIdentity.mccString,
                    rss = cellInfo.cellSignalStrength.dbm,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
            is android.telephony.CellInfoWcdma -> {
                val cellIdentity = cellInfo.cellIdentity
                CellInfoEntity(
                    cid = cellIdentity.cid,
                    mnc = cellIdentity.mncString,
                    mcc = cellIdentity.mccString,
                    rss = cellInfo.cellSignalStrength.dbm,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
            else -> null
        }

        cellInfoEntity?.let {
            database.cellInfoDao().insert(it)
        }
    }

    private fun getCellInfo(): List<CellInfo> {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request permission if not granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return emptyList()
        }

        return telephonyManager.allCellInfo ?: emptyList()
    }

    private fun formatCellInfo(cellInfo: CellInfo): String {
        return when (cellInfo) {
            is android.telephony.CellInfoGsm -> {
                val cellIdentity = cellInfo.cellIdentity
                "GSM (2G): MCC=${cellIdentity.mccString} | MNC=${cellIdentity.mncString} |  LAC=${cellIdentity.lac} | CID=${cellIdentity.cid} | RSS=${cellInfo.cellSignalStrength.dbm} dBm"
            }
            is android.telephony.CellInfoLte -> {
                val cellIdentity = cellInfo.cellIdentity
                "LTE (4G): MCC=${cellIdentity.mccString} | MNC=${cellIdentity.mncString} | TAC=${cellIdentity.tac} | CID=${cellIdentity.ci} | RSS=${cellInfo.cellSignalStrength.dbm} dBm"
            }
            is android.telephony.CellInfoWcdma -> {
                val cellIdentity = cellInfo.cellIdentity
                "WCDMA (3G): MCC=${cellIdentity.mccString} | MNC=${cellIdentity.mncString} | LAC=${cellIdentity.lac} | CID=${cellIdentity.cid} | RSS=${cellInfo.cellSignalStrength.dbm} dBm"
            }
            else -> "Unsupported cell type"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}

@Entity(tableName = "cell_info")
data class CellInfoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val cid: Int,
    val mnc: String?,
    val mcc: String?,
    val rss: Int,
    val latitude: Double,
    val longitude: Double
)

@Dao
interface CellInfoDao {
    @Insert
    suspend fun insert(cellInfo: CellInfoEntity)

    @Query("SELECT * FROM cell_info ORDER BY id DESC")
    fun getAllCellInfo(): Flow<List<CellInfoEntity>>
}

@Database(entities = [CellInfoEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cellInfoDao(): CellInfoDao

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
            ).build()
        }
    }
}