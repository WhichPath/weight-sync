package com.example.dianzicheng.accessibility

import android.content.Context
import com.example.dianzicheng.domain.BodyMeasurement
import kotlinx.coroutines.flow.StateFlow

/**
 * 无障碍子系统的统一入口。
 *
 * ViewModel / TileService 只依赖这个仓库，不再直接接触 Context、Settings 或 Shizuku，
 * 这样“状态读取、开关操作、一次性授权、系统页面跳转”就收敛在同一个模块边界内。
 */
class AccessibilityRepository(
    private val context: Context
) {

    private val controller = AccessibilityController(context)

    val isServiceActive: StateFlow<Boolean> = AfuAccessibilityService.isServiceActive
    val lastCapturedMeasurement: StateFlow<BodyMeasurement?> =
        AfuAccessibilityService.lastCapturedMeasurement
    val lastScrapeLog: StateFlow<String> = AfuAccessibilityService.lastScrapeLog
    val lastRawInspectionLog: StateFlow<String> = AfuAccessibilityService.lastRawInspectionLog

    /** 读取当前真实状态（轻量同步查询，调用方应在主线程外或短临界区使用）。 */
    fun status(): AccessibilityStatus = AccessibilityStatus(
        state = AccessibilitySystem.currentState(context),
        privilege = PrivilegedAccess.status(context),
        isRestrictedBySystem = AccessibilitySystem.isRestrictedBySystem(context),
        isBatteryOptimizationIgnored = AccessibilitySystem.isIgnoringBatteryOptimizations(context)
    )

    suspend fun setEnabled(enabled: Boolean): AccessibilityController.Outcome =
        controller.setEnabled(enabled)

    suspend fun restart(): AccessibilityController.Outcome = controller.restart()

    suspend fun provisionPermanentAccess(): PrivilegedAccess.ProvisionResult =
        PrivilegedAccess.provisionPermanentAccess(context)

    fun requestShizukuPermission() = PrivilegedAccess.requestPermission()

    fun adbProvisionCommand(): String = PrivilegedAccess.adbProvisionCommand(context)

    fun isShizukuInstalled(): Boolean = PrivilegedAccess.isShizukuInstalled(context)

    fun openShizuku(): Boolean = PrivilegedAccess.openShizuku(context)

    fun openShizukuDownload(): Boolean = PrivilegedAccess.openShizukuDownload(context)

    fun openServiceSettings(): Boolean = AccessibilitySystem.openServiceSettings(context)

    fun openAppDetails(): Boolean = AccessibilitySystem.openAppDetails(context)

    fun isAccessibilityAccessBlocked(): Boolean =
        AccessibilitySystem.isAccessibilityAccessBlocked(context)
}
