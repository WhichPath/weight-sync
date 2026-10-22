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
import com.example.dianzicheng.service.AccessibilityController
import com.example.dianzicheng.service.AccessibilityState
import com.example.dianzicheng.service.AccessibilityUtils
import com.example.dianzicheng.service.AfuAccessibilityService
import com.example.dianzicheng.service.PrivilegedChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ScaleVM"

/**
 * 阿福 UI 抓取与同步 ViewModel。
 * 监控无障碍服务的真实状态、特权通道状态及最新抓取的身体成分数据。
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

    private val _events = MutableSharedFlow<ScaleUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ScaleUiEvent> = _events.asSharedFlow()

    init {
        refreshStatus()
        observeUserProfile()
        observeScrapedData()
        loadLatestRecord()
        startStatusWatcher()
        scheduleAutoRepair()
    }

    fun refreshStatus() {
        val state = AccessibilityUtils.currentState(context)
        val privileged = PrivilegedChannel.state()
        val restricted = AccessibilityUtils.isRestrictedBySystem(context)
        val batteryIgnored = AccessibilityUtils.isIgnoringBatteryOptimizations(context)

        _uiState.update {
            it.copy(
                accessibilityState = state,
                privilegedState = privileged,
                isRestrictedBySystem = restricted,
                isBatteryOptimizationIgnored = batteryIgnored
            )
        }
    }

    /**
     * 轮询状态：系统在设置页被改动、或服务被系统回收后，界面能第一时间反映真实状态。
     */
    private fun startStatusWatcher() {
        viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                refreshStatus()
            }
        }
    }

    /**
     * 自动恢复（对齐 GKD 的 fixA11yService）：
     *
     * App 被后台清理后，系统设置里的开关仍是"开"的，但服务并没有被拉起。
     * 这里在打开 App 时检测到这种"开着但没跑"的异常态，就自动把它摘掉再挂回，
     * 强制系统重新绑定，用户不需要再去系统设置手动关开一次。
     */
    private fun scheduleAutoRepair() {
        viewModelScope.launch {
            // 等待系统状态稳定（避免刚开机 / 刚切换设置时误判）
            delay(1_500L)
            val state = AccessibilityUtils.currentState(context)
            if (state != AccessibilityState.ENABLED_NOT_RUNNING) return@launch
            if (PrivilegedChannel.state() != PrivilegedChannel.State.READY) return@launch

            AppLogger.i(TAG, "检测到无障碍服务处于「已开启但未运行」，尝试自动重新拉起")
            val outcome = AccessibilityController.restart(context)
            when (outcome) {
                is AccessibilityController.Outcome.Success ->
                    _events.tryEmit(ScaleUiEvent.Toast("无障碍服务已自动恢复"))

                is AccessibilityController.Outcome.NotRunning ->
                    AppLogger.w(TAG, "自动恢复失败：服务仍未启动")

                else -> Unit
            }
            refreshStatus()
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
            AfuAccessibilityService.isServiceActive.collect {
                refreshStatus()
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

    // ────────────────────── 无障碍开关（对齐 GKD 的应用内操作） ──────────────────────

    /**
     * 用户在应用内拨动无障碍开关。
     */
    fun onAccessibilitySwitchChanged(enabled: Boolean) {
        if (_uiState.value.isAccessibilityBusy) return
        if (enabled) {
            enableAccessibility()
        } else {
            disableAccessibility()
        }
    }

    /**
     * 开启无障碍服务：优先走特权通道在应用内静默完成；
     * 没有特权通道时跳到该服务的系统设置页（只需拨一次滑块）。
     */
    fun enableAccessibility() {
        viewModelScope.launch {
            _uiState.update { it.copy(isAccessibilityBusy = true) }
            val outcome = AccessibilityController.setEnabled(context, true)
            handleOutcome(outcome, successMessage = "无障碍服务已开启")
            _uiState.update { it.copy(isAccessibilityBusy = false) }
            refreshStatus()
        }
    }

    /**
     * 关闭无障碍服务。
     */
    fun disableAccessibility() {
        viewModelScope.launch {
            _uiState.update { it.copy(isAccessibilityBusy = true) }
            val outcome = AccessibilityController.setEnabled(context, false)
            handleOutcome(outcome, successMessage = "无障碍服务已关闭")
            _uiState.update { it.copy(isAccessibilityBusy = false) }
            refreshStatus()
        }
    }

    /**
     * 修复"系统显示已开启、实际无法运行"的掉线状态：
     * 把服务从 enabled_accessibility_services 里摘掉再挂回，强制系统重新绑定。
     */
    fun repairAccessibility() {
        if (_uiState.value.isAccessibilityBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAccessibilityBusy = true) }
            val outcome = AccessibilityController.restart(context)
            handleOutcome(outcome, successMessage = "无障碍服务已重新拉起")
            _uiState.update { it.copy(isAccessibilityBusy = false) }
            refreshStatus()
        }
    }

    /**
     * 申请 Shizuku 授权（授权后即可在应用内直接开关无障碍）。
     */
    fun requestPrivilegedPermission() {
        PrivilegedChannel.requestPermission()
    }

    /**
     * 用户拒绝授权或系统没有特权通道时，退回到系统设置页。
     */
    fun openSystemAccessibilitySettings() {
        _events.tryEmit(ScaleUiEvent.OpenAccessibilityServiceSettings)
    }

    fun openAppDetails() {
        _events.tryEmit(ScaleUiEvent.OpenAppDetails)
    }

    private suspend fun handleOutcome(
        outcome: AccessibilityController.Outcome,
        successMessage: String
    ) {
        when (outcome) {
            is AccessibilityController.Outcome.Success -> {
                _events.tryEmit(ScaleUiEvent.Toast(successMessage))
            }

            is AccessibilityController.Outcome.NotRunning -> {
                AppLogger.w(TAG, "无障碍操作后服务仍未启动")
                if (AccessibilityUtils.isRestrictedBySystem(context)) {
                    _events.tryEmit(ScaleUiEvent.Toast("应用处于受限设置状态，请先允许受限设置"))
                    _events.tryEmit(ScaleUiEvent.OpenAppDetails)
                } else {
                    _events.tryEmit(ScaleUiEvent.Toast("系统未拉起服务，请到系统设置确认开关状态"))
                    _events.tryEmit(ScaleUiEvent.OpenAccessibilityServiceSettings)
                }
            }

            is AccessibilityController.Outcome.NeedSystemSettings -> {
                when (PrivilegedChannel.state()) {
                    PrivilegedChannel.State.NEED_PERMISSION -> {
                        _events.tryEmit(ScaleUiEvent.Toast("授权 Shizuku 后即可在应用内一键开关无障碍"))
                        _events.tryEmit(ScaleUiEvent.RequestShizukuPermission)
                    }

                    else -> {
                        _events.tryEmit(ScaleUiEvent.Toast("请在系统设置中打开本应用的无障碍开关"))
                        _events.tryEmit(ScaleUiEvent.OpenAccessibilityServiceSettings)
                    }
                }
            }

            is AccessibilityController.Outcome.Failed -> {
                AppLogger.e(TAG, "无障碍操作失败: ${outcome.message}")
                _events.tryEmit(ScaleUiEvent.Toast("操作失败：${outcome.message}"))
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

    @Suppress("unused")
    private fun stateLabel(state: AccessibilityState): String = when (state) {
        AccessibilityState.DISABLED -> "未开启"
        AccessibilityState.ENABLED_NOT_RUNNING -> "已开启但未运行"
        AccessibilityState.RUNNING -> "运行中"
    }
}
