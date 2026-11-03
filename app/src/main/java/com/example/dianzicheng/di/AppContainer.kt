package com.example.dianzicheng.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.dianzicheng.accessibility.AccessibilityRepository
import com.example.dianzicheng.data.garmin.GarminAuthManager
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppDatabase
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.ui.HistoryViewModel
import com.example.dianzicheng.ui.ProfileViewModel
import com.example.dianzicheng.ui.ScaleViewModel

/**
 * 应用级依赖容器（轻量 Service Locator）。
 *
 * 项目规模不需要引入 Hilt；这里集中创建数据库、仓库、平台服务与 ViewModel 工厂，
 * 避免 Activity / Service 各自 new 一套依赖。依赖方向保持单向：
 *
 *   ui / accessibility(Service) → domain / data → core
 *
 * AppContainer 是唯一的装配点，新增依赖时只改这里。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: AppDatabase = AppDatabase.getInstance(appContext)

    val scaleRepository: ScaleRepository = ScaleRepository(database.scaleDao())
    val preferenceManager: PreferenceManager = PreferenceManager(appContext)
    val garminAuthManager: GarminAuthManager = GarminAuthManager(appContext, preferenceManager)
    val garminSyncService: GarminSyncService =
        GarminSyncService(appContext, garminAuthManager, database.scaleDao())
    val healthConnectManager: HealthConnectManager = HealthConnectManager(appContext)
    val accessibilityRepository: AccessibilityRepository = AccessibilityRepository(appContext)

    // ────────────────────────── ViewModel 工厂 ──────────────────────────

    fun scaleViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ScaleViewModel(
                repository = scaleRepository,
                preferenceManager = preferenceManager,
                accessibilityRepository = accessibilityRepository,
                garminSyncService = garminSyncService
            )
        }
    }

    fun historyViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            HistoryViewModel(
                repository = scaleRepository,
                garminSyncService = garminSyncService,
                preferenceManager = preferenceManager,
                healthConnectManager = healthConnectManager
            )
        }
    }

    fun profileViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ProfileViewModel(
                preferenceManager = preferenceManager,
                scaleRepository = scaleRepository,
                garminAuthManager = garminAuthManager,
                garminSyncService = garminSyncService,
                healthConnectManager = healthConnectManager
            )
        }
    }
}
