package com.example.dianzicheng.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.dianzicheng.data.local.AppLogger
import kotlinx.coroutines.delay

private const val TAG = "AccessibilityController"

/** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS（SDK 中为 @hide，直接使用字面量） */
private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

/**
 * 无障碍服务开关控制器（交互与实现对齐 GKD）。
 *
 * GKD 的核心做法是：
 *   1. `accessibility_enabled = 1`
 *   2. 如果本服务已经在列表里（说明它此刻处于"开着但没跑"的异常态），
 *      先把它从 `enabled_accessibility_services` 里摘掉，等 1 秒让系统感知到变更；
 *   3. 再把它加回去，系统就会重新绑定并拉起服务；
 *   4. 等 2 秒确认服务真的起来了。
 *
 * 这样用户在 App 内拨一下开关就能恢复被清理 / 受限 / 崩溃导致掉线的服务，
 * 完全不需要跳到系统设置里手动关开一次。
 */
object AccessibilityController {

    /** 摘除后等待系统感知变更的时间（GKD 经验值：太短系统不会重新绑定） */
    private const val REMOVE_SETTLE_MS = 1_000L

    /** 写回后等待系统完成绑定的时间 */
    private const val BIND_SETTLE_MS = 2_000L

    sealed interface Outcome {
        /** 操作成功，且服务已经真正在运行 */
        data object Success : Outcome

        /** 命令下发成功，但服务没有在预期时间内被拉起 */
        data object NotRunning : Outcome

        /** 没有特权通道，需要用户去系统设置里拨一次开关 */
        data object NeedSystemSettings : Outcome

        /** 执行失败 */
        data class Failed(val message: String) : Outcome
    }

    /**
     * 打开（或关闭）无障碍服务。
     */
    suspend fun setEnabled(context: Context, enabled: Boolean): Outcome {
        if (!enabled) return disable(context)

        val component = AccessibilityUtils.serviceComponent(context)
        val flat = component.flattenToShortString()
        val current = AccessibilityUtils.enabledServicesInSettings(context)
        val alreadyListed = current.any {
            it.equals(flat, ignoreCase = true) ||
                it.equals(component.flattenToString(), ignoreCase = true)
        }

        if (PrivilegedChannel.state() != PrivilegedChannel.State.READY) {
            return Outcome.NeedSystemSettings
        }

        // ① 全局开关打开
        execOrFail("settings put secure accessibility_enabled 1")?.let { return it }

        // ② 已经在列表里 = 当前处于异常态（开着但没跑），先摘除再挂回，强制系统重新绑定
        if (alreadyListed) {
            val without = current.filterNot { isSameComponent(it, component.flattenToString(), flat) }
            execOrFail(writeCommand(without))?.let { return it }
            delay(REMOVE_SETTLE_MS)
        }

        // ③ 挂回本服务
        val target = (current.filterNot { isSameComponent(it, component.flattenToString(), flat) } + flat)
        execOrFail(writeCommand(target))?.let { return it }
        delay(BIND_SETTLE_MS)

        return if (AccessibilityUtils.isRunning()) {
            AppLogger.i(TAG, "无障碍服务已在应用内开启成功")
            Outcome.Success
        } else {
            AppLogger.w(TAG, "无障碍服务写入成功，但服务未在预期时间内启动")
            Outcome.NotRunning
        }
    }

    /**
     * 重启无障碍服务：即使系统设置里显示"已开启"，也能把掉线的服务重新拉起来。
     */
    suspend fun restart(context: Context): Outcome {
        if (PrivilegedChannel.state() != PrivilegedChannel.State.READY) {
            return Outcome.NeedSystemSettings
        }
        val component = AccessibilityUtils.serviceComponent(context)
        val flat = component.flattenToShortString()
        val current = AccessibilityUtils.enabledServicesInSettings(context)

        execOrFail("settings put secure accessibility_enabled 1")?.let { return it }

        val without = current.filterNot { isSameComponent(it, component.flattenToString(), flat) }
        execOrFail(writeCommand(without))?.let { return it }
        delay(REMOVE_SETTLE_MS)

        execOrFail(writeCommand(without + flat))?.let { return it }
        delay(BIND_SETTLE_MS)

        return if (AccessibilityUtils.isRunning()) {
            AppLogger.i(TAG, "无障碍服务重启成功")
            Outcome.Success
        } else {
            AppLogger.w(TAG, "无障碍服务重启后仍未启动（可能被系统受限设置拦截）")
            Outcome.NotRunning
        }
    }

    /**
     * 关闭无障碍服务。
     */
    private suspend fun disable(context: Context): Outcome {
        if (PrivilegedChannel.state() != PrivilegedChannel.State.READY) {
            return Outcome.NeedSystemSettings
        }
        val component = AccessibilityUtils.serviceComponent(context)
        val flat = component.flattenToShortString()
        val current = AccessibilityUtils.enabledServicesInSettings(context)
        val without = current.filterNot { isSameComponent(it, component.flattenToString(), flat) }
        execOrFail(writeCommand(without))?.let { return it }
        delay(REMOVE_SETTLE_MS)
        AppLogger.i(TAG, "无障碍服务已在应用内关闭")
        return Outcome.Success
    }

    /**
     * 跳转到"本服务"的系统设置详情页（Android 11+ 支持直达），
     * 用户只需拨一次滑块，无需在长长的无障碍列表里翻找。
     *
     * 注意：ACTION_ACCESSIBILITY_DETAILS_SETTINGS 在 SDK 里是 @hide，
     * 这里直接使用字面量；设备若不支持则自动回退到无障碍列表页。
     */
    fun openServiceSettings(context: Context): Boolean {
        val component = AccessibilityUtils.serviceComponent(context)
        val detailIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(detailIntent) }.isSuccess) {
            return true
        }
        val listIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(listIntent) }.isSuccess
    }

    /**
     * 跳转到应用信息页（用于解除"受限设置"）。
     */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun writeCommand(services: List<String>): String {
        val value = services.joinToString(":")
        return "settings put secure enabled_accessibility_services '$value'"
    }

    private fun isSameComponent(entry: String, flat: String, short: String): Boolean =
        entry.equals(flat, ignoreCase = true) || entry.equals(short, ignoreCase = true)

    /**
     * 执行命令；返回 null 表示成功，否则返回失败结果。
     */
    private suspend fun execOrFail(command: String): Outcome.Failed? {
        val result = PrivilegedChannel.exec(command)
        result.exceptionOrNull()?.let { return Outcome.Failed(it.message ?: "命令执行失败") }
        return null
    }
}
