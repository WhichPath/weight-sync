package com.example.dianzicheng

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.data.garmin.GarminAuthManager
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppDatabase
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.ui.HistoryViewModel
import com.example.dianzicheng.ui.MainScreen
import com.example.dianzicheng.ui.ProfileViewModel
import com.example.dianzicheng.ui.ScaleViewModel
import com.example.dianzicheng.ui.theme.电子秤Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var bleClient: BleScaleClient
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var garminAuthManager: GarminAuthManager
    private lateinit var garminSyncService: GarminSyncService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                "蓝牙权限被拒绝，请前往「设置 → 应用 → 权限」手动开启蓝牙权限，否则无法搜索连接体脂秤",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkPermissions()
        
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "scale-db"
        ).fallbackToDestructiveMigration().build()
        
        preferenceManager = PreferenceManager(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        garminAuthManager = GarminAuthManager(applicationContext, preferenceManager)
        garminSyncService = GarminSyncService(applicationContext, garminAuthManager, database.scaleDao())
        bleClient = BleScaleClient(applicationContext)
        healthConnectManager = HealthConnectManager(applicationContext)

        bleClient.onMacDiscovered = { mac ->
            lifecycleScope.launch {
                preferenceManager.savePairedMac(mac)
            }
        }

        lifecycleScope.launch {
            preferenceManager.pairedMac.collect { mac ->
                bleClient.lastPairedMac = mac
                if (mac.isNullOrEmpty()) {
                    bleClient.disconnectAndReset()
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            val isPairingComplete by preferenceManager.isPairingComplete.collectAsState(initial = false)
            
            电子秤Theme {
                val scaleViewModel: ScaleViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ScaleViewModel(
                                bleClient = bleClient,
                                repository = scaleRepository,
                                preferenceManager = preferenceManager,
                                healthConnectManager = healthConnectManager,
                                garminSyncService = garminSyncService
                            ) as T
                        }
                    }
                )
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return HistoryViewModel(
                                repository = scaleRepository,
                                garminSyncService = garminSyncService,
                                preferenceManager = preferenceManager,
                                healthConnectManager = healthConnectManager
                            ) as T
                        }
                    }
                )
                val profileViewModel: ProfileViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ProfileViewModel(
                                preferenceManager = preferenceManager,
                                scaleRepository = scaleRepository,
                                garminAuthManager = garminAuthManager,
                                garminSyncService = garminSyncService,
                                healthConnectManager = healthConnectManager
                            ) as T
                        }
                    }
                )

                MainScreen(
                    scaleViewModel = scaleViewModel,
                    historyViewModel = historyViewModel,
                    profileViewModel = profileViewModel,
                    isPairingComplete = isPairingComplete,
                    onPairingComplete = {
                        lifecycleScope.launch {
                            preferenceManager.setPairingComplete(true)
                        }
                    }
                )
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        // 回到前台：如果已记住设备且当前为空闲状态，自动恢复扫描以保持踏秤即连
        if (!bleClient.lastPairedMac.isNullOrEmpty() && bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE) {
            bleClient.startScan()
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入后台：暂停 BLE 低延迟扫描，节约电量并符合 Android 后台规范
        bleClient.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnectAndReset()
    }
}
