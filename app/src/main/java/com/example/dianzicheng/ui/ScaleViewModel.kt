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
import com.example.dianzicheng.service.AfuAccessibilityService
import com.example.dianzicheng.service.ShizukuManager
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
        observeUserProfile()
        observeScrapedData()
        loadLatestRecord()
    }

    fun refreshStatus() {
        val isShizukuRunning = ShizukuManager.isShizukuRunning()
        val hasShizukuPerm = ShizukuManager.hasPermission()
        val isAccEnabled = ShizukuManager.isAccessibilityServiceEnabled(context) || AfuAccessibilityService.isServiceActive.value

        _uiState.update {
            it.copy(
                isShizukuRunning = isShizukuRunning,
                hasShizukuPermission = hasShizukuPerm,
                isAccessibilityEnabled = isAccEnabled
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
            AfuAccessibilityService.isServiceActive.collect { active ->
                _uiState.update { it.copy(isAccessibilityEnabled = active || ShizukuManager.isAccessibilityServiceEnabled(context)) }
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
     * 通过 Shizuku 静默启用无障碍服务
     */
    fun enableAccessibilityViaShizuku() {
        viewModelScope.launch {
            _uiState.update { it.copy(lastLogMessage = "正在通过 Shizuku 激活无障碍服务...") }
            val result = ShizukuManager.enableAccessibilityServiceSilently()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isAccessibilityEnabled = true,
                        lastLogMessage = "无障碍服务已成功激活！"
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "未知错误"
                _uiState.update {
                    it.copy(lastLogMessage = "激活失败: $err")
                }
            }
            refreshStatus()
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
