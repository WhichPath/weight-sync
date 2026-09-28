package com.example.dianzicheng

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var garminAuthManager: GarminAuthManager
    private lateinit var garminSyncService: GarminSyncService

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 通知权限结果，不强制
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求 Android 13+ 通知权限（用于后台截获与上传结果提示）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        database = AppDatabase.getInstance(applicationContext)
        preferenceManager = PreferenceManager(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        garminAuthManager = GarminAuthManager(applicationContext, preferenceManager)
        garminSyncService = GarminSyncService(applicationContext, garminAuthManager, database.scaleDao())
        healthConnectManager = HealthConnectManager(applicationContext)

        enableEdgeToEdge()
        setContent {
            电子秤Theme {
                val scaleViewModel: ScaleViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ScaleViewModel(
                                context = applicationContext,
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
                    profileViewModel = profileViewModel
                )
            }
        }
    }
}
