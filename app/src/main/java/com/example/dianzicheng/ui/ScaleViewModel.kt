package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.accessibility.AccessibilityController
import com.example.dianzicheng.accessibility.AccessibilityRepository
import com.example.dianzicheng.accessibility.AccessibilityState
import com.example.dianzicheng.accessibility.PrivilegeMode
import com.example.dianzicheng.accessibility.PrivilegedAccess
import com.example.dianzicheng.core.logging.AppLogger
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScaleVM"

/**
 * 阿福 UI 抓取与同步 ViewModel。
 *
 * 架构约束：这里不持有 Context，也不直接调用 Settings / Shizuku；
 * 所有无障碍系统操作都通过 [AccessibilityRepository] 完成。
 */
class ScaleViewModel(
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager,
    private val accessibilityRepository: AccessibilityRepository,
    private val garminSyncService: GarminSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScaleUiState())
    val uiState: StateFlow<ScaleUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ScaleUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ScaleUiEvent> = _events.asSharedFlow()

    /** 用户在没有特权时拨动开关，授权成功后自动继续执行的动作 */
    private var pendingSwitchAfterGrant: Boolean? = null

    /** 用户点击“Shizuku 授权”，授权回调成功后自动继续永久授权 */
    private var pendingProvisionAfterShizukuGrant = false

    private var lastAutoRepairAtMs = 0L

    init {
        refreshStatus()
        observeUserProfile()
        observeScrapedData()
        loadLatestRecord()
        startStatusWatcher()
        scheduleAutoRepair()
    }

    fun refreshStatus() {
        viewModelScope.launch { refreshStatusNow() }
    }

    /**
     * 状态读取包含 Settings / Shizuku binder 查询，统一放到 IO 线程，避免轮询卡顿主线程。
     */
    private suspend fun refreshStatusNow() {
        val status = withContext(Dispatchers.IO) { accessibilityRepository.status() }
        _uiState.update {
            it.copy(
                accessibilityState = status.state,
                privilege = status.privilege,
                isRestrictedBySystem = status.isRestrictedBySystem,
                isBatteryOptimizationIgnored = status.isBatteryOptimizationIgnored
            )
        }
    }

    /**
     * Activity 回到前台时调用：刷新状态，并尝试自动修复“开着但没跑”的掉线态。
     */
    fun onAppResumed() {
        refreshStatus()
        scheduleAutoRepair(delayMs = 300L)
    }

    /**
     * 轮询状态：系统设置页被改动、服务被系统回收后，界面能第一时间反映真实状态。
     * 5 秒一次，避免无障碍服务自身的事件流之外还要高频唤醒。
     */
    private fun startStatusWatcher() {
        viewModelScope.launch {
            while (isActive) {
                delay(5_000L)
                refreshStatus()
            }
        }
    }

    /**
     * 自动恢复（对齐 GKD 的 fixA11yService）：
     *
     * App 被后台清理后，系统设置里的开关仍是“开”的，但服务并没有被拉起。
     * 这里检测到这种“开着但没跑”的异常态，就自动把它摘掉再挂回，
     * 强制系统重新绑定，用户不需要再去系统设置手动关开一次。
     */
    private fun scheduleAutoRepair(delayMs: Long = 1_500L) {
        viewModelScope.launch {
            delay(delayMs)
            val now = System.currentTimeMillis()
            if (now - lastAutoRepairAtMs < 10_000L) return@launch

            val status = withContext(Dispatchers.IO) { accessibilityRepository.status() }
            if (status.state != AccessibilityState.ENABLED_NOT_RUNNING) return@launch
            if (!status.privilege.canControlInApp) return@launch

            lastAutoRepairAtMs = now
            AppLogger.i(TAG, "检测到无障碍服务处于「已开启但未运行」，尝试自动重新拉起")
            when (val outcome = accessibilityRepository.restart()) {
                AccessibilityController.Outcome.Success ->
                    _events.tryEmit(ScaleUiEvent.Toast("无障碍服务已自动恢复"))

                AccessibilityController.Outcome.NotRunning ->
                    AppLogger.w(TAG, "自动恢复失败：服务仍未启动")

                AccessibilityController.Outcome.NeedPrivilege -> Unit

                is AccessibilityController.Outcome.Failed ->
                    AppLogger.w(TAG, "自动恢复失败: ${outcome.message}")
            }
            refreshStatus()
        }
    }

    private fun observeUserProfile() {
        viewModelScope.launch {
            preferenceManager.userProfile.collect { profile ->
                _uiState.update { it.copy(userProfile = profile) }
            }
        }
    }

    private fun observeScrapedData() {
        viewModelScope.launch {
            accessibilityRepository.lastCapturedMeasurement.collect { measurement ->
                if (measurement != null) {
                    _uiState.update { it.copy(currentMeasurement = measurement) }
                }
            }
        }

        viewModelScope.launch {
            accessibilityRepository.lastScrapeLog.collect { log ->
                _uiState.update { it.copy(lastLogMessage = log) }
            }
        }

        viewModelScope.launch {
            accessibilityRepository.lastRawInspectionLog.collect { log ->
                _uiState.update { it.copy(rawInspectionLog = log) }
            }
        }

        viewModelScope.launch {
            accessibilityRepository.isServiceActive.collect {
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
     *
     * 没有可用的写入通道时，不直接跳系统设置，而是弹出“一次性永久授权”流程；
     * 授权成功后自动继续执行本次开关动作。
     */
    fun onAccessibilitySwitchChanged(enabled: Boolean) {
        if (_uiState.value.isAccessibilityBusy) return
        if (!_uiState.value.privilege.canControlInApp) {
            pendingSwitchAfterGrant = enabled
            _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
            return
        }
        if (enabled) enableAccessibility() else disableAccessibility()
    }

    /** 开启无障碍服务：优先走永久授权直接写入；仅 Shizuku 时走临时命令。 */
    fun enableAccessibility() {
        if (_uiState.value.isAccessibilityBusy) return
        if (!_uiState.value.privilege.canControlInApp) {
            pendingSwitchAfterGrant = true
            _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAccessibilityBusy = true) }
            val outcome = accessibilityRepository.setEnabled(true)
            handleOutcome(outcome, successMessage = "无障碍服务已开启")
            _uiState.update { it.copy(isAccessibilityBusy = false) }
            refreshStatus()
        }
    }

    /** 关闭无障碍服务。 */
    fun disableAccessibility() {
        if (_uiState.value.isAccessibilityBusy) return
        if (!_uiState.value.privilege.canControlInApp) {
            pendingSwitchAfterGrant = false
            _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAccessibilityBusy = true) }
            val outcome = accessibilityRepository.setEnabled(false)
            handleOutcome(outcome, successMessage = "无障碍服务已关闭")
            _uiState.update { it.copy(isAccessibilityBusy = false) }
            refreshStatus()
        }
    }

    /**
     * 修复“系统显示已开启、实际无法运行”的掉线状态。
     */
    fun repairAccessibility() {
        if (_uiState.value.isAccessibilityBusy) return
        if (!_uiState.value.privilege.canControlInApp) {
            pendingSwitchAfterGrant = true
            _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAccessibilityBusy = true) }
            val outcome = accessibilityRepository.restart()
            handleOutcome(outcome, successMessage = "无障碍服务已重新拉起")
            _uiState.update { it.copy(isAccessibilityBusy = false) }
            refreshStatus()
        }
    }

    /**
     * 统一的一次性永久授权入口：
     *  - 已永久授权：无事发生；
     *  - Shizuku 就绪：直接 pm grant + appops；
     *  - Shizuku 待授权：先拉起 Shizuku 授权弹窗；
     *  - Shizuku 不可用：展示 ADB 手动授权流程。
     */
    fun requestPrivilegedPermission() {
        viewModelScope.launch {
            refreshStatusNow()
            when (_uiState.value.privilege.mode) {
                PrivilegeMode.PERMANENT -> {
                    _events.tryEmit(ScaleUiEvent.Toast("永久授权已生效，无需重复授权"))
                }

                PrivilegeMode.SHIZUKU_READY -> provisionPermanentAccess()

                PrivilegeMode.SHIZUKU_NEED_PERMISSION -> {
                    pendingProvisionAfterShizukuGrant = true
                    accessibilityRepository.requestShizukuPermission()
                }

                PrivilegeMode.NONE -> _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
            }
        }
    }

    /**
     * 执行一次性永久授权。成功后，如果用户此前拨动了开关，会自动继续执行。
     */
    fun provisionPermanentAccess() {
        if (_uiState.value.isProvisioning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProvisioning = true) }
            when (val result = accessibilityRepository.provisionPermanentAccess()) {
                PrivilegedAccess.ProvisionResult.Success -> {
                    AppLogger.i(TAG, "一次性永久授权成功")
                    _events.tryEmit(
                        ScaleUiEvent.Toast("永久授权已生效：重启手机或关闭开发者选项后仍可应用内开关")
                    )
                    // 必须先同步刷新 UI 状态，否则下面的 pending 开关动作会看到旧权限而再次弹窗。
                    refreshStatusNow()

                    val pendingAction = pendingSwitchAfterGrant
                    pendingSwitchAfterGrant = null
                    if (pendingAction != null) {
                        onAccessibilitySwitchChanged(pendingAction)
                    }
                }

                PrivilegedAccess.ProvisionResult.NeedShizukuPermission -> {
                    pendingProvisionAfterShizukuGrant = true
                    _events.tryEmit(ScaleUiEvent.Toast("请在 Shizuku 弹窗中允许本应用"))
                    accessibilityRepository.requestShizukuPermission()
                }

                PrivilegedAccess.ProvisionResult.ShizukuUnavailable -> {
                    _events.tryEmit(
                        ScaleUiEvent.Toast("Shizuku 未运行，可复制 ADB 命令手动完成一次授权")
                    )
                    _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
                }

                is PrivilegedAccess.ProvisionResult.Failed -> {
                    AppLogger.e(TAG, "永久授权失败: ${result.message}")
                    _events.tryEmit(ScaleUiEvent.Toast("永久授权失败：${result.message}"))
                    _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
                }
            }
            _uiState.update { it.copy(isProvisioning = false) }
            refreshStatus()
        }
    }

    /**
     * 用户手动执行 ADB 命令后，在授权面板点“重新检测”时调用。
     * 如果此时已经具备权限，并且之前有被中断的开关动作，则自动继续执行。
     */
    fun onPrivilegeRechecked() {
        viewModelScope.launch {
            refreshStatusNow()
            if (!_uiState.value.privilege.canControlInApp) return@launch

            val pendingAction = pendingSwitchAfterGrant
            pendingSwitchAfterGrant = null
            if (pendingAction != null) {
                onAccessibilitySwitchChanged(pendingAction)
            }
        }
    }

    /** Shizuku 授权结果回调（由 MainActivity 转发）。 */
    fun onShizukuPermissionResult(granted: Boolean) {
        refreshStatus()
        if (!granted) {
            pendingProvisionAfterShizukuGrant = false
            return
        }
        if (pendingProvisionAfterShizukuGrant) {
            pendingProvisionAfterShizukuGrant = false
            provisionPermanentAccess()
        }
    }

    /** 用户拒绝授权或需要手动兜底时，退回到系统设置页。 */
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
                if (_uiState.value.isRestrictedBySystem) {
                    _events.tryEmit(ScaleUiEvent.Toast("应用处于受限设置状态，请先允许受限设置"))
                    _events.tryEmit(ScaleUiEvent.OpenAppDetails)
                } else {
                    _events.tryEmit(ScaleUiEvent.Toast("系统未拉起服务，请到系统设置确认开关状态"))
                    _events.tryEmit(ScaleUiEvent.OpenAccessibilityServiceSettings)
                }
            }

            is AccessibilityController.Outcome.NeedPrivilege -> {
                _events.tryEmit(ScaleUiEvent.ShowPrivilegeDialog)
            }

            is AccessibilityController.Outcome.Failed -> {
                AppLogger.e(TAG, "无障碍操作失败: ${outcome.message}")
                _events.tryEmit(ScaleUiEvent.Toast("操作失败：${outcome.message}"))
            }
        }
    }

    /**
     * 手动触发当前记录同步至 Garmin。
     */
    fun syncCurrentToGarmin() {
        val measurement = _uiState.value.currentMeasurement ?: return

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
