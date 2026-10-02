package com.example.dianzicheng.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile
import com.example.dianzicheng.service.AccessibilityUtils
import com.example.dianzicheng.service.AfuAccessibilityService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "ScaleVM"

/**
 * 阿福 UI 抓取与同步 ViewModel。
 * 监控 Shizuku 授权状态、无障碍服务状态及最新抓取的身体成分数据。
 */
class ScaleViewModel(
    private val context: Context,
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager,
    private val healthConnectManager: HealthConnectManager? = null,
    private val garminSyncService: GarminSyncService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScaleUiState())
    val uiState: StateFlow<ScaleUiState> = _uiState.asStateFlow()

    init {
        refreshStatus()
        observeUserProfile()
        observeScrapedData()
        loadLatestRecord()
    }

    fun refreshStatus() {
        val isAccEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context) || AfuAccessibilityService.isServiceActive.value
        val isBatteryIgnored = AccessibilityUtils.isIgnoringBatteryOptimizations(context)

        _uiState.update {
            it.copy(
                isAccessibilityEnabled = isAccEnabled,
                isBatteryOptimizationIgnored = isBatteryIgnored
            )
        }
    }

    private fun observeUserProfile() {
        viewModelScope.launch {
            preferenceManager.userProfile.collect { profile ->
                _uiState.update { it.copy(userProfile = profile ?: UserProfile()) }
            }
        }
    }

    private fun observeScrapedData() {
        viewModelScope.launch {
            AfuAccessibilityService.lastCapturedMeasurement.collect { measurement ->
                if (measurement != null) {
                    _uiState.update { it.copy(currentMeasurement = measurement) }
                }
            }
        }

        viewModelScope.launch {
            AfuAccessibilityService.lastScrapeLog.collect { log ->
                _uiState.update { it.copy(lastLogMessage = log) }
            }
        }

        viewModelScope.launch {
            AfuAccessibilityService.lastRawInspectionLog.collect { log ->
                _uiState.update { it.copy(rawInspectionLog = log) }
            }
        }

        viewModelScope.launch {
            AfuAccessibilityService.isServiceActive.collect { active ->
                val isAccEnabled = active || AccessibilityUtils.isAccessibilityServiceEnabled(context)
                val isBatteryIgnored = AccessibilityUtils.isIgnoringBatteryOptimizations(context)
                _uiState.update {
                    it.copy(
                        isAccessibilityEnabled = isAccEnabled,
                        isBatteryOptimizationIgnored = isBatteryIgnored
                    )
                }
            }
        }
    }

    private fun loadLatestRecord() {
        viewModelScope.launch {
            val list = repository.getAllMeasurements()
            if (list.isNotEmpty()) {
                _uiState.update { it.copy(currentMeasurement = list.first()) }
            }
        }
    }

    /**
     * 手动触发当前记录同步至 Garmin
     */
    fun syncCurrentToGarmin() {
        val measurement = _uiState.value.currentMeasurement ?: return
        if (garminSyncService == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isGarminSyncing = true, garminSyncResult = null) }
            val result = garminSyncService.uploadMeasurement(measurement)
            if (result.isSuccess) {
                repository.markSynced(measurement.id, System.currentTimeMillis())
                _uiState.update {
                    it.copy(
                        isGarminSyncing = false,
                        garminSyncResult = "Garmin 同步成功！",
                        currentMeasurement = measurement.copy(
                            syncedToGarmin = true,
                            syncedToGarminAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "同步失败"
                _uiState.update {
                    it.copy(
                        isGarminSyncing = false,
                        garminSyncResult = "同步失败: $err"
                    )
                }
            }
        }
    }
}
