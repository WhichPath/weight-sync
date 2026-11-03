package com.example.dianzicheng.accessibility

import android.content.Context
import android.provider.Settings
import com.example.dianzicheng.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AccessibilityController"

/**
 * 无障碍服务开关控制器（交互与实现对齐 GKD 的「高级授权」模式）。
 *
 * 核心流程：
 *   1. 先把 `accessibility_enabled` 置 1；
 *   2. 如果本服务已经在列表里（说明它此刻处于“开着但没跑”的异常态），
 *      先把它从 `enabled_accessibility_services` 里摘掉，等待系统感知变更；
 *   3. 再把它加回去，系统会重新绑定并拉起服务；
 *   4. 轮询 [AccessibilitySystem.isRunning]，而不是无脑等待固定时长。
 *
 * 写设置优先走 WRITE_SECURE_SETTINGS（永久有效）；只有 Shizuku 时退化为 shell 命令。
 */
class AccessibilityController(
    private val context: Context
) {

    private val gateway = AndroidSecureSettingsGateway(context)
    private val mutex = Mutex()

    sealed interface Outcome {
        /** 操作成功，且服务已经真正在运行（或已停止） */
        data object Success : Outcome

        /** 命令下发成功，但服务没有在预期时间内被拉起 */
        data object NotRunning : Outcome

        /** 没有可用的写入通道，需要用户完成一次性授权或去系统设置 */
        data object NeedPrivilege : Outcome

        /** 执行失败 */
        data class Failed(val message: String) : Outcome
    }

    /** 打开（或关闭）无障碍服务。 */
    suspend fun setEnabled(enabled: Boolean): Outcome = mutex.withLock {
        // 摘除 -> 等待 -> 挂回 必须原子完成；否则调用方（ViewModel / 快捷磁贴）
        // 被取消时可能停在“已摘除但没挂回”的半状态，导致无障碍直接掉线。
        withContext(Dispatchers.IO + NonCancellable) {
            if (enabled) enable() else disable()
        }
    }

    /**
     * 重启无障碍服务：即使系统设置里显示“已开启”，也能把掉线的服务重新拉起来。
     */
    suspend fun restart(): Outcome = mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) { restartInternal() }
    }

    private suspend fun enable(): Outcome {
        if (AccessibilitySystem.isRunning()) return Outcome.Success
        if (!gateway.available) return Outcome.NeedPrivilege

        val component = AccessibilitySystem.serviceComponent(context)
        val flat = component.flattenToShortString()
        val current = AccessibilitySystem.enabledServicesInSettings(context)
        val alreadyListed = current.any { isSameComponent(it, component.flattenToString(), flat) }
        val without = current.filterNot {
            isSameComponent(it, component.flattenToString(), flat)
        }

        writeAccessibilityEnabled()?.let { return it }

        // 已经在列表里 = 当前处于异常态（开着但没跑），先摘除再挂回，强制系统重新绑定
        if (alreadyListed) {
            writeServices(without)?.let { return it }
            delay(REMOVE_SETTLE_MS)
        }

        writeServices(without + flat)?.let { return it }

        return if (awaitRunning()) {
            AppLogger.i(TAG, "无障碍服务已在应用内开启成功")
            Outcome.Success
        } else {
            AppLogger.w(TAG, "无障碍服务写入成功，但服务未在预期时间内启动")
            Outcome.NotRunning
        }
    }

    private suspend fun restartInternal(): Outcome {
        if (!gateway.available) return Outcome.NeedPrivilege

        val component = AccessibilitySystem.serviceComponent(context)
        val flat = component.flattenToShortString()
        val current = AccessibilitySystem.enabledServicesInSettings(context)
        val without = current.filterNot {
            isSameComponent(it, component.flattenToString(), flat)
        }

        writeAccessibilityEnabled()?.let { return it }

        writeServices(without)?.let { return it }
        delay(REMOVE_SETTLE_MS)

        writeServices(without + flat)?.let { return it }

        return if (awaitRunning()) {
            AppLogger.i(TAG, "无障碍服务重启成功")
            Outcome.Success
        } else {
            AppLogger.w(TAG, "无障碍服务重启后仍未启动（可能被系统受限设置拦截）")
            Outcome.NotRunning
        }
    }

    private suspend fun disable(): Outcome {
        if (!gateway.available) return Outcome.NeedPrivilege

        val component = AccessibilitySystem.serviceComponent(context)
        val flat = component.flattenToShortString()
        val current = AccessibilitySystem.enabledServicesInSettings(context)
        val without = current.filterNot {
            isSameComponent(it, component.flattenToString(), flat)
        }

        if (without.size == current.size && !AccessibilitySystem.isRunning()) {
            return Outcome.Success
        }

        writeServices(without)?.let { return it }

        return if (awaitStopped()) {
            AppLogger.i(TAG, "无障碍服务已在应用内关闭")
            Outcome.Success
        } else {
            Outcome.Failed("系统未在预期时间内停止无障碍服务，请稍后重试")
        }
    }

    private suspend fun writeAccessibilityEnabled(): Outcome.Failed? =
        gateway.putInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            .exceptionOrNull()
            ?.let { Outcome.Failed(it.message ?: "写入 accessibility_enabled 失败") }

    private suspend fun writeServices(services: List<String>): Outcome.Failed? =
        gateway.putString(
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(":")
        ).exceptionOrNull()
            ?.let { Outcome.Failed(it.message ?: "写入 enabled_accessibility_services 失败") }

    private suspend fun awaitRunning(): Boolean = withTimeoutOrNull(BIND_TIMEOUT_MS) {
        while (!AccessibilitySystem.isRunning()) delay(POLL_INTERVAL_MS)
        true
    } ?: false

    private suspend fun awaitStopped(): Boolean = withTimeoutOrNull(STOP_TIMEOUT_MS) {
        while (AccessibilitySystem.isRunning()) delay(POLL_INTERVAL_MS)
        true
    } ?: false

    private fun isSameComponent(entry: String, flat: String, short: String): Boolean =
        entry.equals(flat, ignoreCase = true) || entry.equals(short, ignoreCase = true)

    private companion object {
        /** 摘除后等待系统感知变更的时间（GKD 经验值：太短系统不会重新绑定） */
        const val REMOVE_SETTLE_MS = 600L

        /** 写回后等待服务连接的最长时间 */
        const val BIND_TIMEOUT_MS = 2_500L

        /** 关闭后等待服务断开的最长时间 */
        const val STOP_TIMEOUT_MS = 1_500L

        const val POLL_INTERVAL_MS = 100L
    }
}
