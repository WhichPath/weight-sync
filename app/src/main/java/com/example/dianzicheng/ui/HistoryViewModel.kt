package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.core.logging.AppLogger
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 测量历史页面的 ViewModel。
 *
 * 提供测量记录列表、删除测量、单条手动重新同步至 Garmin 等能力。
 */
class HistoryViewModel(
    private val repository: ScaleRepository,
    private val garminSyncService: GarminSyncService,
    private val preferenceManager: PreferenceManager,
    private val healthConnectManager: HealthConnectManager
) : ViewModel() {

    val history: StateFlow<List<BodyMeasurement>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun syncToGarmin(measurement: BodyMeasurement) {
        viewModelScope.launch {
            _syncMessage.value = "正在上传至 Garmin..."
            val result = garminSyncService.uploadMeasurement(measurement)
            if (result.isSuccess) {
                _syncMessage.value = "Garmin 同步成功"
            } else {
                _syncMessage.value = "Garmin 同步失败: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun deleteMeasurement(measurement: BodyMeasurement) {
        viewModelScope.launch {
            repository.deleteMeasurement(measurement)
            try {
                if (preferenceManager.healthConnectEnabled.first() &&
                    healthConnectManager.hasAllPermissions()
                ) {
                    healthConnectManager.deleteMeasurement(measurement)
                }
            } catch (e: Exception) {
                AppLogger.e("HistoryViewModel", "Health Connect 删除同步失败: ${e.message}")
            }
        }
    }
}
