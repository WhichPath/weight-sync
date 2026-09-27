package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

private const val TAG = "ScaleVM"

/**
 * 称重主界面 ViewModel。
 *
 * 核心优化：
 * 1. 彻底移除多成员分支，使用单用户身体档案，彻底解决“未匹配成员导致体脂等指标全部归零”的缺陷；
 * 2. 引入不可变称重会话（ActiveSession）保护机制，下秤复位（0kg）时绝不冲刷已测得的阻抗与多项身体成分；
 * 3. 称重完成后自动触发 Garmin Connect 与系统健康异步同步。
 */
class ScaleViewModel(
    private val bleClient: BleScaleClient,
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager,
    private val healthConnectManager: HealthConnectManager? = null,
    private val garminSyncService: GarminSyncService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScaleUiState())
    val uiState: StateFlow<ScaleUiState> = _uiState.asStateFlow()

    /**
     * 当前测量会话内部数据类。
     * 一旦锁定重量与阻抗，数据在此对象中被安全留存，下秤时的 0kg 报文无法污染或覆盖它。
     */
    private data class ActiveSession(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        var lockedWeightKg: Double = 0.0,
        var lockedImpedanceOhm: Double = 0.0,
        var isCommitted: Boolean = false
    )

    private var currentSession: ActiveSession? = null
    private var syncJob: Job? = null

    init {
        observeUserProfile()
        observeBle()
        observePreferences()
    }

    private fun observeUserProfile() {
        viewModelScope.launch {
            preferenceManager.userProfile.collect { profile ->
                _uiState.update { it.copy(userProfile = profile) }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            combine(preferenceManager.pairedMac, preferenceManager.pairedDeviceName) { mac, name ->
                Pair(mac, name)
            }.collect { (mac, name) ->
                _uiState.update {
                    it.copy(
                        pairedDeviceMac = mac,
                        pairedDeviceName = name,
                        isDeviceRemembered = !mac.isNullOrEmpty()
                    )
                }
                if (mac.isNullOrEmpty()) {
                    bleClient.disconnectAndReset()
                }
            }
        }
    }

    private fun observeBle() {
        // 1. 连接状态
        viewModelScope.launch {
            bleClient.connectionState.collect { state ->
                _uiState.update { it.copy(connection = state) }
            }
        }

        // 2. 实时体重
        viewModelScope.launch {
            bleClient.weight.collect { weight ->
                _uiState.update { it.copy(liveWeightKg = weight) }

                if (weight <= 0.0) {
                    // 用户离秤：若已有锁定体重但尚未落库（如穿袜无阻抗直接下秤），此时兜底提交
                    val session = currentSession
                    if (session != null && !session.isCommitted && session.lockedWeightKg >= 3.0) {
                        AppLogger.i(TAG, "用户下秤，提交无阻抗称重记录: ${session.lockedWeightKg}kg")
                        session.isCommitted = true
                        commitSession(session)
                    }
                }
            }
        }

        // 3. 稳定锁定标志
        viewModelScope.launch {
            bleClient.isStable.collect { stable ->
                _uiState.update { it.copy(isStable = stable) }
                if (stable) {
                    val w = bleClient.weight.value
                    if (w >= 3.0) {
                        val session = currentSession
                        if (session == null || abs(w - session.lockedWeightKg) > 1.5) {
                            // 开启全新会话
                            val newSession = ActiveSession(lockedWeightKg = w)
                            currentSession = newSession
                            AppLogger.i(TAG, "体重锁定稳定: ${String.format("%.2f", w)} kg (Session ${newSession.id.take(8)})")
                            updateCurrentDisplay(newSession)
                        } else {
                            // 微调已有会话
                            session.lockedWeightKg = w
                            updateCurrentDisplay(session)
                        }
                    }
                }
            }
        }

        // 4. 生物电阻抗 (BIA) 到达
        viewModelScope.launch {
            bleClient.impedance.collect { imp ->
                _uiState.update { it.copy(impedanceOhm = imp) }
                val session = currentSession
                if (imp > 0.0 && session != null && session.lockedWeightKg >= 3.0) {
                    AppLogger.i(TAG, "收到有效阻抗: ${imp.toInt()} Ω，生成完整身体成分并立即保存")
                    session.lockedImpedanceOhm = imp
                    session.isCommitted = true
                    commitSession(session)
                }
            }
        }

        // 5. 发现设备与候选设备列表
        viewModelScope.launch {
            bleClient.discoveredDevice.collect { pair ->
                _uiState.update {
                    it.copy(
                        discoveredDeviceName = pair?.first,
                        discoveredDeviceMac = pair?.second
                    )
                }
            }
        }
        viewModelScope.launch {
            bleClient.discoveredScales.collect { list ->
                _uiState.update { it.copy(discoveredScales = list) }
            }
        }
    }

    /**
     * 更新当前 UI 展示的测量结果
     */
    private fun updateCurrentDisplay(session: ActiveSession) {
        val profile = _uiState.value.userProfile
        val measurement = BodyAlgorithm.calculate(
            weightKg = session.lockedWeightKg,
            impedanceOhm = session.lockedImpedanceOhm,
            sex = profile.sex,
            heightCm = profile.heightCm,
            birthDateEpochMs = profile.birthDateEpochMs,
            timestampMs = session.timestamp
        ).copy(id = session.id)

        _uiState.update { it.copy(currentMeasurement = measurement) }
    }

    /**
     * 将有效测量会话持久化到 Room 数据库，并触发 Garmin 与健康平台自动上传。
     */
    private fun commitSession(session: ActiveSession) {
        val profile = _uiState.value.userProfile
        val measurement = BodyAlgorithm.calculate(
            weightKg = session.lockedWeightKg,
            impedanceOhm = session.lockedImpedanceOhm,
            sex = profile.sex,
            heightCm = profile.heightCm,
            birthDateEpochMs = profile.birthDateEpochMs,
            timestampMs = session.timestamp
        ).copy(id = session.id)

        _uiState.update { it.copy(currentMeasurement = measurement) }

        viewModelScope.launch {
            try {
                repository.saveMeasurement(measurement)
                AppLogger.i(TAG, "测量数据已成功持久化至本地数据库: id=${session.id.take(8)}")

                // 触发 Garmin 自动同步
                scheduleGarminSync(measurement)

                // 触发 Health Connect 自动同步
                scheduleHealthConnectSync(measurement)
            } catch (e: Exception) {
                AppLogger.e(TAG, "数据库保存异常: ${e.message}")
            }
        }
    }

    private fun scheduleGarminSync(measurement: BodyMeasurement) {
        val service = garminSyncService ?: return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(1500) // 防抖
            if (preferenceManager.garminAutoSync.first()) {
                _uiState.update { it.copy(isGarminSyncing = true, garminSyncResult = null) }
                val result = service.uploadMeasurement(measurement)
                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isGarminSyncing = false,
                            garminSyncResult = "Garmin 同步成功",
                            currentMeasurement = it.currentMeasurement?.copy(syncedToGarmin = true)
                        )
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "同步失败"
                    _uiState.update { it.copy(isGarminSyncing = false, garminSyncResult = "Garmin $err") }
                }
            }
        }
    }

    private fun scheduleHealthConnectSync(measurement: BodyMeasurement) {
        val hc = healthConnectManager ?: return
        viewModelScope.launch {
            try {
                if (preferenceManager.healthConnectEnabled.first() && hc.hasAllPermissions()) {
                    hc.writeMeasurement(measurement)
                    AppLogger.i(TAG, "已成功同步至系统 Health Connect")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Health Connect 同步异常: ${e.message}")
            }
        }
    }

    fun startPairingScan() {
        bleClient.startPairingScan()
    }

    fun manualConnect(device: BleScaleClient.DiscoveredScaleDevice) {
        viewModelScope.launch {
            preferenceManager.savePairedDevice(device.address, device.name)
            bleClient.manualConnect(device)
        }
    }

    fun pairAndConnectDevice(device: BleScaleClient.DiscoveredScaleDevice) = manualConnect(device)

    fun disconnectAndReset() {
        viewModelScope.launch {
            preferenceManager.clearPairedMac()
            bleClient.disconnectAndReset()
            currentSession = null
            _uiState.update { it.copy(currentMeasurement = null) }
        }
    }
}
