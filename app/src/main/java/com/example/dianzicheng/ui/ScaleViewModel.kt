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
        val stableStartTimeMs: Long = System.currentTimeMillis(),
        var lockedWeightKg: Double = 0.0,
        var lockedImpedanceOhm: Double = 0.0,
        var isCommitted: Boolean = false
    )

    private var currentSession: ActiveSession? = null
    private var settlementJob: Job? = null
    private var syncJob: Job? = null

    init {
        purgeInvalidRecords()
        observeUserProfile()
        observeBle()
        observePreferences()
        observeHistory()
    }

    private fun purgeInvalidRecords() {
        viewModelScope.launch {
            try {
                repository.purgeInvalidMeasurements()
                AppLogger.i(TAG, "已启动历史记录自检，成功清理历史遗留的无效/零点记录 (< 10kg)")
            } catch (e: Exception) {
                AppLogger.w(TAG, "清理无效历史记录异常: ${e.message}")
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            repository.getHistory().collect { historyList ->
                val latest = historyList.firstOrNull()
                // 仅在当前没有活跃称重会话且主页未展示当前会话时，加载最新一次历史记录展示
                if (latest != null && currentSession == null && _uiState.value.currentMeasurement == null) {
                    _uiState.update { it.copy(currentMeasurement = latest) }
                }
            }
        }
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
                // 若此前旧版本误记录了云麦/小米等非阿福设备，自动清洗该残留记录
                if (!mac.isNullOrEmpty() && !name.isNullOrEmpty() && !bleClient.isAfuDeviceName(name)) {
                    AppLogger.w(TAG, "检测到历史记录中的非阿福设备: '$name' [$mac]，自动清除配对记忆！")
                    preferenceManager.clearPairedMac()
                    return@collect
                }
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

    /**
     * 处理体脂秤报送的稳定体重事件。
     *
     * 关键保护逻辑：
     * 1. 严格过滤非成人正常体重（< 15.0kg），防止踩踏唤醒、单脚试探等零碎动作产生脏会话。
     * 2. 启动 5.0 秒沉淀窗口（settlementJob）。在沉淀期间，允许后续收到的阻抗（BIA）更新 UI 预览，
     *    但绝不提前落库。
     * 3. 只有当用户稳定站立满 5.0 秒，才正式将最终体重与最终阻抗落库至 Room 数据库并同步 Garmin。
     */
    private fun handleStableWeight(weight: Double) {
        if (weight < 15.0) {
            AppLogger.w(TAG, "忽略低于 15.0kg 的非成人体重稳定信号: ${String.format("%.2f", weight)}kg")
            return
        }

        val existing = currentSession
        if (existing == null || abs(weight - existing.lockedWeightKg) > 1.5) {
            settlementJob?.cancel()

            val newSession = ActiveSession(
                lockedWeightKg = weight,
                stableStartTimeMs = System.currentTimeMillis()
            )
            currentSession = newSession
            _uiState.update { it.copy(impedanceOhm = 0.0) }
            AppLogger.i(TAG, "检测到有效稳定体重: ${String.format("%.2f", weight)} kg (Session ${newSession.id.take(8)})，启动 5.0 秒稳定等待窗口...")
            updateCurrentDisplay(newSession)

            settlementJob = viewModelScope.launch {
                delay(5000L) // 沉淀等待 5 秒，充分保证体脂秤 AC 微电流测完阻抗，并避免偶发短暂停留
                val cur = currentSession
                if (cur != null && cur.id == newSession.id && !cur.isCommitted && cur.lockedWeightKg >= 15.0) {
                    AppLogger.i(TAG, "5 秒稳定测量倒计时完成！锁定读数: ${String.format("%.2f", cur.lockedWeightKg)}kg, 阻抗=${cur.lockedImpedanceOhm.toInt()}Ω，正式持久化落库与同步")
                    cur.isCommitted = true
                    commitSession(cur)
                }
            }
        } else {
            // 在同一会话内的微小体重修正（<= 1.5kg）
            existing.lockedWeightKg = weight
            updateCurrentDisplay(existing)
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

                // 过早锁定作废保护：秤可能在单脚踩秤或体重爬升过程中误报稳定（如 7kg 误报）
                // 若实际体重随后显著超出锁定值（> 3.0kg），说明那次锁定是瞬态误报，必须作废旧会话！
                val session = currentSession
                if (session != null && weight > session.lockedWeightKg + 3.0) {
                    AppLogger.w(TAG, "检测到过早锁定误报 (锁定 ${String.format("%.2f", session.lockedWeightKg)}kg < 实时 ${String.format("%.2f", weight)}kg)，作废旧会话 ${session.id.take(8)}")
                    settlementJob?.cancel()
                    if (session.isCommitted) {
                        val staleId = session.id
                        viewModelScope.launch {
                            try {
                                repository.deleteMeasurementById(staleId)
                                AppLogger.i(TAG, "已成功从数据库中清除过早误锁记录: id=${staleId.take(8)}")
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "清除过早记录失败: ${e.message}")
                            }
                        }
                    }
                    currentSession = null
                    _uiState.update { it.copy(impedanceOhm = 0.0) }
                }

                // 稳定体重会话恢复与兜底保证：若已稳定且有有效体重（>=15.0kg），启动或维护会话
                if (bleClient.isStable.value && weight >= 15.0) {
                    handleStableWeight(weight)
                }

                if (weight <= 0.0) {
                    // 用户离秤处理
                    val cur = currentSession
                    if (cur != null && !cur.isCommitted) {
                        val durationMs = System.currentTimeMillis() - cur.stableStartTimeMs
                        if (durationMs >= 4000L && cur.lockedWeightKg >= 15.0) {
                            // 用户已稳定站立 4 秒以上下秤（秤面已显示结果），此时视为测量完成，提交落库
                            AppLogger.i(TAG, "用户在秤上稳定站立 ${durationMs}ms 后下秤，视为测量完成，提交记录: ${cur.lockedWeightKg}kg, 阻抗=${cur.lockedImpedanceOhm.toInt()}Ω")
                            settlementJob?.cancel()
                            cur.isCommitted = true
                            commitSession(cur)
                        } else {
                            // 站立不足 4 秒（如偶发踩踏、唤醒碰触等瞬态误触），直接作废，不予保存！
                            AppLogger.w(TAG, "站立时间不足 (${durationMs}ms < 4000ms)，判定为偶发踩踏或瞬态误触，作废本次未完成测量，不写入数据库")
                            settlementJob?.cancel()
                            currentSession = null
                            _uiState.update { it.copy(impedanceOhm = 0.0) }
                        }
                    }
                    // 延迟 2 秒清除会话引用，保证已落库的卡片在主界面平稳展示
                    viewModelScope.launch {
                        delay(2000)
                        if (bleClient.weight.value <= 0.0 && currentSession?.isCommitted == true) {
                            currentSession = null
                        }
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
                    if (w >= 15.0) {
                        handleStableWeight(w)
                    }
                }
            }
        }

        // 4. 生物电阻抗 (BIA) 到达
        viewModelScope.launch {
            bleClient.impedance.collect { imp ->
                _uiState.update { it.copy(impedanceOhm = imp) }
                val session = currentSession
                if (imp > 0.0 && session != null && session.lockedWeightKg >= 15.0) {
                    AppLogger.i(TAG, "实时捕获到阻抗更新: ${imp.toInt()} Ω (Session ${session.id.take(8)})，更新实时界面，等待 5s 稳定窗口确认")
                    session.lockedImpedanceOhm = imp
                    // 仅更新实时界面预览，绝对不立即 commit！由 5 秒 settlementJob 或稳定满 4s 下秤提交
                    updateCurrentDisplay(session)
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
        if (session.lockedWeightKg < 15.0) {
            AppLogger.w(TAG, "拦截无效体重记录: ${session.lockedWeightKg}kg < 15.0kg，拒绝落库")
            return
        }

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

    fun startScanning() {
        bleClient.startScan()
    }

    fun startPairingScan() {
        bleClient.startPairingScan()
    }

    fun connectToMac(macAddress: String) {
        viewModelScope.launch {
            preferenceManager.savePairedMac(macAddress)
            bleClient.connectMac(macAddress)
        }
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
            settlementJob?.cancel()
            currentSession = null
            _uiState.update { it.copy(currentMeasurement = null) }
        }
    }
}
