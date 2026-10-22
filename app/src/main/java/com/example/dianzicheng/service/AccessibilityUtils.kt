package com.example.dianzicheng.service

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.example.dianzicheng.data.local.AppLogger

private const val TAG = "AccessibilityUtils"

/**
 * 无障碍服务的三种真实状态。
 *
 * 系统设置里的"滑块是开的"只代表 [AccessibilityState.ENABLED_NOT_RUNNING] 之前的
 * 登记动作，真正决定"能不能抓数据"的是服务实例有没有被系统绑定并回调
 * [AfuAccessibilityService.onServiceConnected]。
 */
enum class AccessibilityState {
    /** 系统设置里完全没有登记本服务 */
    DISABLED,

    /** 系统设置里已登记（滑块是开的），但服务实例并没有真正运行 */
    ENABLED_NOT_RUNNING,

    /** 服务实例已连接，真正在跑 */
    RUNNING
}

/**
 * 无障碍服务状态工具类，纯原生实现，不依赖任何第三方提权组件。
 *
 * 与旧版的区别：旧版把"设置里已登记"直接当成"已开启"，导致 App 显示运行中，
 * 而系统设置里其实着"已打开但无法运行"。这里把两者彻底拆开。
 */
object AccessibilityUtils {

    /** AppOps：应用是否被系统的"受限设置"拦截（侧载 APK 在 Android 13+ 的典型问题） */
    private const val OPSTR_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    /** AppOps：系统的无障碍访问是否被拒绝 */
    private const val OPSTR_ACCESS_ACCESSIBILITY = "android:access_accessibility"

    /** 本应用无障碍服务的完整组件名 */
    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, AfuAccessibilityService::class.java)

    /**
     * 系统设置（Secure Settings）中是否已经登记了本服务。
     * 注意：这只能说明"滑块是开的"，不代表服务真的在运行。
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val raw = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取 enabled_accessibility_services 失败: ${e.message}")
            null
        } ?: return false
        if (raw.isBlank() || raw == "null") return false

        val component = serviceComponent(context)
        val flat = component.flattenToString()
        val short = component.flattenToShortString()
        return raw.split(':').any { item ->
            val entry = item.trim()
            entry.equals(flat, ignoreCase = true) || entry.equals(short, ignoreCase = true)
        }
    }

    /**
     * 系统设置中登记的全部无障碍服务组件（原始字符串）。
     */
    fun enabledServicesInSettings(context: Context): List<String> {
        val raw = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        if (raw.isBlank() || raw == "null") return emptyList()
        return raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 服务实例是否真的在运行——这是唯一可信的"正在工作"判据。
     */
    fun isRunning(): Boolean = AfuAccessibilityService.isServiceActive.value

    /**
     * 汇总当前真实状态。
     */
    fun currentState(context: Context): AccessibilityState = when {
        isRunning() -> AccessibilityState.RUNNING
        isEnabledInSettings(context) -> AccessibilityState.ENABLED_NOT_RUNNING
        else -> AccessibilityState.DISABLED
    }

    /**
     * 是否处于系统的"受限设置"状态。
     *
     * Android 13+ 侧载安装的 APK 默认受限，此时无障碍开关可以拨开但服务不会被拉起，
     * 表现就是设置里"已打开但无法运行"，必须先到应用信息页允许受限设置。
     */
    fun isRestrictedBySystem(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return isAppOpBlocked(context, OPSTR_ACCESS_RESTRICTED_SETTINGS)
    }

    /**
     * 系统的无障碍访问是否被拒绝（服务在设置里开着、但拿不到任何窗口内容）。
     */
    fun isAccessibilityAccessBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return isAppOpBlocked(context, OPSTR_ACCESS_ACCESSIBILITY)
    }

    private fun isAppOpBlocked(context: Context, opName: String): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.checkOpNoThrow(opName, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
        } catch (e: Exception) {
            AppLogger.w(TAG, "AppOps 查询失败($opName): ${e.message}")
            false
        }
    }

    /**
     * 检测本应用是否已被加入系统电池优化白名单（允许后台无限制活动）。
     * 加入白名单后，Android 14/15 及 ColorOS 将不会在锁屏或退出时重置无障碍权限。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            true
        }
    }
}
