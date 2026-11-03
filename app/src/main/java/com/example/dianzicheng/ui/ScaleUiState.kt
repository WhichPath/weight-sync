package com.example.dianzicheng.ui

import com.example.dianzicheng.accessibility.AccessibilityState
import com.example.dianzicheng.accessibility.PrivilegeStatus
import com.example.dianzicheng.accessibility.ShizukuState
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile

/**
 * 阿福 UI 抓取主界面的 UI 状态快照。
 */
data class ScaleUiState(
    /** 无障碍服务的真实状态（区分“已开启但没运行”） */
    val accessibilityState: AccessibilityState = AccessibilityState.DISABLED,
    /** 特权通道：永久授权 / Shizuku 临时授权 / 未接入 */
    val privilege: PrivilegeStatus = PrivilegeStatus(
        hasPermanentGrant = false,
        shizukuState = ShizukuState.UNAVAILABLE
    ),
    /** 应用是否被系统的“受限设置”拦截 */
    val isRestrictedBySystem: Boolean = false,
    /** 正在执行无障碍开关操作 */
    val isAccessibilityBusy: Boolean = false,
    /** 正在执行一次性永久授权 */
    val isProvisioning: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val currentMeasurement: BodyMeasurement? = null,
    val lastLogMessage: String = "正在检测服务状态...",
    val rawInspectionLog: String = "暂无排查日志",
    val userProfile: UserProfile = UserProfile(),
    val isGarminSyncing: Boolean = false,
    val garminSyncResult: String? = null,
    val error: String? = null
) {
    /** 兼容旧字段：服务是否真的在运行 */
    val isAccessibilityEnabled: Boolean
        get() = accessibilityState == AccessibilityState.RUNNING
}

/**
 * 需要 Activity / Compose 执行的一次性动作。
 */
sealed interface ScaleUiEvent {
    /** 打开系统里“本无障碍服务”的详情页 */
    data object OpenAccessibilityServiceSettings : ScaleUiEvent

    /** 打开应用信息页（用于解除受限设置） */
    data object OpenAppDetails : ScaleUiEvent

    /** 展示“一次性永久授权”详细流程 */
    data object ShowPrivilegeDialog : ScaleUiEvent

    /** 顶部提示 */
    data class Toast(val message: String) : ScaleUiEvent
}
