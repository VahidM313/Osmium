package com.example.osmium

import android.Manifest
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
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import kotlin.math.pow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.model.BitmapDescriptorFactory

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
        setupLocationUpdates()
        database = AppDatabase.getInstance(this)
        startCellInfoCollection()
        startCellInfoProcessing()

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

    }

    @Composable
    fun MainScreen() {
        var selectedTabIndex by remember { mutableStateOf(0) }
        val tabs = listOf("Available Cell", "Database")

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
                0 -> AvailableCellScreen()
                1 -> DatabaseRowsScreen()
            }
        }
    }

    @Composable
    fun AvailableCellScreen() {
        var selectedSubTabIndex by remember { mutableStateOf(0) }
        val subTabs = listOf("Cell Info", "Map")

        Column {
            TabRow(selectedTabIndex = selectedSubTabIndex) {
                subTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedSubTabIndex == index,
                        onClick = { selectedSubTabIndex = index },
                        text = { Text(text = title) }
                    )
                }
            }

            when (selectedSubTabIndex) {
                0 -> CellInfoScreen()
                1 -> CellTowerMapScreen()
            }
        }
    }

    @Composable
    fun CellTowerMapScreen() {
        val context = LocalContext.current
        val cellTowers by database.cellTowerDao().getAllCellTowers().collectAsState(initial = emptyList())
        var currentLocation by remember { mutableStateOf<LatLng?>(null) }
        var isMapReady by remember { mutableStateOf(false) }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 10f)
        }

        LaunchedEffect(Unit) {
            while (currentLocation == null) {
                val location = lastLocation
                if (location != null) {
                    currentLocation = LatLng(location.latitude, location.longitude)
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(currentLocation!!, 15f)
                    isMapReady = true
                }
                delay(100) // Wait a bit before checking again
            }
        }

        if (!isMapReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState
            ) {
                currentLocation?.let { location ->
                    Marker(
                        state = MarkerState(position = location),
                        title = "Your Location",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)
                    )
                }

                cellTowers.forEach { tower ->
                    Marker(
                        state = MarkerState(position = LatLng(tower.latitude, tower.longitude)),
                        title = "Cell Tower ${tower.cellId}",
                        snippet = "Operator: ${tower.operator}, Gen: ${tower.gen}"
                    )
                }
            }
        }
    }

    @Composable
    fun CellInfoScreen() {
        var cellInfoList by remember { mutableStateOf<List<CellInfo>>(emptyList()) }

        LaunchedEffect(Unit) {
            while (true) {
                cellInfoList = getCellInfo().filter { cellInfo ->
                    when (cellInfo) {
                        is android.telephony.CellInfoGsm -> getOperatorName(cellInfo.cellIdentity.mobileNetworkOperator) != "Unknown Operator"
                        is android.telephony.CellInfoLte -> getOperatorName(cellInfo.cellIdentity.mobileNetworkOperator) != "Unknown Operator"
                        is android.telephony.CellInfoWcdma -> getOperatorName(cellInfo.cellIdentity.mobileNetworkOperator) != "Unknown Operator"
                        else -> false
                    }
                }
                delay(500) // Update every second
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
        var selectedTabIndex by remember { mutableStateOf(0) }
        val tabs = listOf("Cell Info", "Cell Towers")

        Column {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(text = title) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> CellInfoTab()
                1 -> CellTowerTab()
            }
        }
    }

    @Composable
    fun CellInfoTab() {
        val cellInfoEntities by database.cellInfoDao().getAllCellInfo().collectAsState(initial = emptyList())

        LazyColumn {
            items(cellInfoEntities) { entity ->
                Text(text = formatCellInfoEntity(entity))
            }
        }
    }

    private fun formatCellInfoEntity(entity: CellInfoEntity): String {
        return "ID: ${entity.id}, CID: ${entity.cid}, Operator: ${entity.operator}, Gen: ${entity.gen} " +
                "MNC: ${entity.mnc}, MCC: ${entity.mcc}, RSS: ${entity.rss}, " +
                "Distance: ${String.format("%.2f", entity.distance)}m, " +
                "Lat: ${String.format("%.5f", entity.latitude)}, " +
                "Long: ${String.format("%.5f", entity.longitude)}\n"
    }

    @Composable
    fun CellTowerTab() {
        val cellTowers by database.cellTowerDao().getAllCellTowers().collectAsState(initial = emptyList())

        LazyColumn {
            items(cellTowers) { tower ->
                Text(text = formatCellTower(tower))
            }
        }
    }

    private fun formatCellTower(tower: CellTowerEntity): String {
        return "CID: ${tower.cellId}, Operator: ${tower.operator}, Gen: ${tower.gen}, " +
                "MNC: ${tower.mnc}, MCC: ${tower.mcc}, " +
                "Lat: ${String.format("%.5f", tower.latitude)}, " +
                "Long: ${String.format("%.5f", tower.longitude)}\n"
    }

    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(2000)
            .setMinUpdateDistanceMeters(10f)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
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

    private fun startCellInfoProcessing() {
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                processCellInfo()
                delay(10000) // Run every minute
            }
        }
    }

    private suspend fun processCellInfo() {
        val cellInfoGroups = database.cellInfoDao().getAllCellInfoGrouped()
        for (group in cellInfoGroups) {
            val cellInfoList = database.cellInfoDao().getCellInfoByCid(group.cid)
            if (cellInfoList.size >= 3) {
                performMultilaterationAndSave(cellInfoList)
            }
        }
    }


    private suspend fun saveCellInfo(cellInfo: CellInfo, location: Location) {
        val cellInfoEntity = when (cellInfo) {
            is android.telephony.CellInfoGsm -> {
                val cellIdentity = cellInfo.cellIdentity
                val trss: Int = cellInfo.cellSignalStrength.dbm
                val operator = getOperatorName(cellIdentity.mobileNetworkOperator)
                if (operator != "Unknown Operator") {
                    CellInfoEntity(
                        cid = cellIdentity.cid,
                        operator = operator,
                        gen = "GSM (2G)",
                        mnc = cellIdentity.mncString,
                        mcc = cellIdentity.mccString,
                        rss = trss,
                        distance = calculateDistance(trss,"GSM"),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } else null
            }
            is android.telephony.CellInfoLte -> {
                val cellIdentity = cellInfo.cellIdentity
                val trss: Int = cellInfo.cellSignalStrength.dbm
                val operator = getOperatorName(cellIdentity.mobileNetworkOperator)
                if (operator != "Unknown Operator") {
                    CellInfoEntity(
                        cid = cellIdentity.ci,
                        operator = operator,
                        gen = "LTE (4G)",
                        mnc = cellIdentity.mncString,
                        mcc = cellIdentity.mccString,
                        rss = trss,
                        distance = calculateDistance(trss,"LTE"),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } else null
            }
            is android.telephony.CellInfoWcdma -> {
                val cellIdentity = cellInfo.cellIdentity
                val trss = cellInfo.cellSignalStrength.dbm
                val operator = getOperatorName(cellIdentity.mobileNetworkOperator)
                if (operator != "Unknown Operator") {
                    CellInfoEntity(
                        cid = cellIdentity.cid,
                        operator = operator,
                        gen = "WCDMA (3G)",
                        mnc = cellIdentity.mncString,
                        mcc = cellIdentity.mccString,
                        rss = trss,
                        distance = calculateDistance(trss,"WCDMA"),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                } else null
            }
            else -> null
        }

        cellInfoEntity?.let {
            val existingEntity = database.cellInfoDao().getMostRecentCellInfoByCid(it.cid)

            if (existingEntity == null ||
                !areLocationsEqual(existingEntity.latitude, existingEntity.longitude, it.latitude, it.longitude)) {
                // Insert new entity only if it's a new cell ID or the location has changed
                database.cellInfoDao().insert(it)
            } else {
                // Update the existing entity with new RSS and distance
                existingEntity.rss = it.rss
                existingEntity.distance = it.distance
                database.cellInfoDao().update(existingEntity)
            }
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
                val op = getOperatorName(cellIdentity.mobileNetworkOperator)
                "GSM (2G): Operator=${op} | MCC=${cellIdentity.mccString} | MNC=${cellIdentity.mncString} |  LAC=${cellIdentity.lac} | CID=${cellIdentity.cid} | RSS=${cellInfo.cellSignalStrength.dbm} dBm\n"
            }
            is android.telephony.CellInfoLte -> {
                val cellIdentity = cellInfo.cellIdentity
                val op = getOperatorName(cellIdentity.mobileNetworkOperator)
                "LTE (4G): Operator=${op} | MCC=${cellIdentity.mccString} | MNC=${cellIdentity.mncString} | TAC=${cellIdentity.tac} | CID=${cellIdentity.ci} | RSS=${cellInfo.cellSignalStrength.dbm} dBm\n"
            }
            is android.telephony.CellInfoWcdma -> {
                val cellIdentity = cellInfo.cellIdentity
                val op = getOperatorName(cellIdentity.mobileNetworkOperator)
                "WCDMA (3G): Operator=${op} | MCC=${cellIdentity.mccString} | MNC=${cellIdentity.mncString} | LAC=${cellIdentity.lac} | CID=${cellIdentity.cid} | RSS=${cellInfo.cellSignalStrength.dbm} dBm\n"
            }
            else -> "Unsupported cell type"
        }
    }

    private fun getOperatorName(code: String?): String {
        return when (code) {
            "43235" -> "MTN Irancell"
            "43211" -> "IR-MCI"
            "43220" -> "Rightel"
            else -> "Unknown Operator"
        }
    }

    private fun areLocationsEqual(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        val threshold = 0.0001 // Adjust this value based on the desired precision
        return Math.abs(lat1 - lat2) < threshold && Math.abs(lon1 - lon2) < threshold
    }

    private fun calculateDistance(rssi: Int, tech: String): Double {
        val d0 = 1.0 // Reference distance in meters
        val (pl_d0, pathLossExponent) = when (tech) {
            "GSM" -> Pair(35.0, 3.75)
            "WCDMA" -> Pair(40.0, 4.0)
            "LTE" -> Pair(38.0, 3.95)
            else -> Pair(0.0, 0.0)
        }
        return d0 * 10.0.pow((pl_d0 - rssi) / (10.0 * pathLossExponent))
    }

    private suspend fun performMultilaterationAndSave(cellInfoList: List<CellInfoEntity>) {
        if (cellInfoList.size < 3) return

        val points = cellInfoList.map { MultilaterationUtil.Point(it.latitude, it.longitude) }
        val distances = cellInfoList.map { it.distance }

        val result = MultilaterationUtil.performMultilateration(points, distances)

        if (result != null) {
            val cellTower = CellTowerEntity(
                cellId = cellInfoList.first().cid,
                operator = cellInfoList.first().operator,
                gen = cellInfoList.first().gen,
                mnc = cellInfoList.first().mnc,
                mcc = cellInfoList.first().mcc,
                latitude = result.latitude,
                longitude = result.longitude,
            )

            database.cellTowerDao().insert(cellTower)
        } else {
            Log.e("Multilateration", "Failed to perform multilateration")
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