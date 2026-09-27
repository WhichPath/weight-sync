package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.garmin.GarminAuthManager
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.UserProfile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "ProfileVM"

/**
 * 个人资料与设置页面的 ViewModel。
 *
 * 负责：
 * 1. 单人身体档案（性别、身高、生日）保存与更新；
 * 2. Garmin Connect 登录（Ticket 换票）、注销、自动同步开关、一键历史全量同步；
 * 3. Health Connect 开关与批量同步；
 * 4. 蓝牙设备配对管理与日志展示。
 */
class ProfileViewModel(
    private val preferenceManager: PreferenceManager,
    private val scaleRepository: ScaleRepository,
    private val garminAuthManager: GarminAuthManager,
    private val garminSyncService: GarminSyncService,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    // ── 个人身体档案 ──────────────────────────────────────────────────────────
    val userProfile: StateFlow<UserProfile> = preferenceManager.userProfile
        .stateIn(viewModelScope, SharingStarted.Lazily, UserProfile())

    // ── Garmin 设置 ───────────────────────────────────────────────────────────
    val isGarminLoggedIn: StateFlow<Boolean> = preferenceManager.isGarminLoggedIn
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val garminUsername: StateFlow<String?> = preferenceManager.garminUsername
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val garminAutoSync: StateFlow<Boolean> = preferenceManager.garminAutoSync
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    // ── 设备配对设置 ──────────────────────────────────────────────────────────
    val pairedMac: StateFlow<String?> = preferenceManager.pairedMac
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val pairedDeviceName: StateFlow<String?> = preferenceManager.pairedDeviceName
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // ── Health Connect 设置 ───────────────────────────────────────────────────
    val healthConnectEnabled: StateFlow<Boolean> = preferenceManager.healthConnectEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // ── 操作状态 ──────────────────────────────────────────────────────────────
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    val logEntries: StateFlow<List<AppLogger.LogEntry>> = AppLogger.logs

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun saveUserProfile(sex: Sex, heightCm: Double, birthDateEpochMs: Long) {
        viewModelScope.launch {
            preferenceManager.saveUserProfile(sex, heightCm, birthDateEpochMs)
            _statusMessage.value = "个人身体档案已更新"
        }
    }

    /**
     * 处理 Garmin WebView 登录成功捕获的 serviceTicketId
     */
    fun handleGarminTicket(ticket: String) {
        viewModelScope.launch {
            _isOperating.value = true
            _statusMessage.value = "正在连接 Garmin..."
            val result = garminAuthManager.handleServiceTicket(ticket)
            _isOperating.value = false
            if (result.isSuccess) {
                _statusMessage.value = "Garmin 登录成功: ${result.getOrNull()}"
            } else {
                _statusMessage.value = "Garmin 登录失败: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun logoutGarmin() {
        viewModelScope.launch {
            garminAuthManager.logout()
            _statusMessage.value = "已退出 Garmin 账号"
        }
    }

    fun setGarminAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            preferenceManager.setGarminAutoSync(enabled)
        }
    }

    /**
     * 一键同步所有未上传历史记录到 Garmin Connect
     */
    fun syncAllToGarmin() {
        viewModelScope.launch {
            _isOperating.value = true
            _statusMessage.value = "正在同步历史记录至 Garmin..."
            val result = garminSyncService.syncAllUnsynced()
            _isOperating.value = false
            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                _statusMessage.value = if (count > 0) "成功同步 $count 条记录至 Garmin" else "所有记录均已同步"
            } else {
                _statusMessage.value = "同步失败: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun resetPairing() {
        viewModelScope.launch {
            preferenceManager.clearPairedMac()
            _statusMessage.value = "已重置设备配对"
        }
    }

    fun toggleHealthConnect(enabled: Boolean) {
        viewModelScope.launch {
            preferenceManager.setHealthConnectEnabled(enabled)
            _statusMessage.value = if (enabled) "已开启 Health Connect 同步" else "已关闭 Health Connect 同步"
        }
    }

    fun syncAllToHealthConnect() {
        val hc = healthConnectManager ?: run {
            _statusMessage.value = "Health Connect 不可用"
            return
        }
        viewModelScope.launch {
            _isOperating.value = true
            _statusMessage.value = "正在同步记录至系统健康..."
            try {
                val all = scaleRepository.getAllMeasurements()
                var successCount = 0
                for (item in all) {
                    if (hc.writeMeasurement(item)) successCount++
                }
                _statusMessage.value = "成功同步 $successCount/${all.size} 条记录至 Health Connect"
            } catch (e: Exception) {
                _statusMessage.value = "系统健康同步失败: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun clearLogs() {
        AppLogger.clear()
    }
}
