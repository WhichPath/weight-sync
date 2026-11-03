package com.example.dianzicheng.accessibility

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.example.dianzicheng.core.logging.AppLogger

private const val TAG = "AccessibilitySystem"

/** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS（SDK 中为 @hide，直接使用字面量） */
private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
    "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

/**
 * 无障碍系统状态的只读查询与系统页面跳转。
 *
 * 写系统设置的动作统一收敛到 [AccessibilityController]，这里只做“读”和“导航”，
 * 从而避免 UI、ViewModel、Service 各自复制一套 Settings 读取逻辑。
 */
object AccessibilitySystem {

    /** AppOps：应用是否被系统的“受限设置”拦截（侧载 APK 在 Android 13+ 的典型问题） */
    private const val OPSTR_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    /** AppOps：系统的无障碍访问是否被拒绝 */
    private const val OPSTR_ACCESS_ACCESSIBILITY = "android:access_accessibility"

    private const val APP_OP_CACHE_MS = 15_000L

    @Volatile
    private var restrictedCachedAtMs = 0L

    @Volatile
    private var restrictedCached = false

    /** 本应用无障碍服务的完整组件名 */
    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, AfuAccessibilityService::class.java)

    /**
     * 系统设置（Secure Settings）中是否已经登记了本服务。
     * 注意：这只能说明“滑块是开的”，不代表服务真的在运行。
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val component = serviceComponent(context)
        val flat = component.flattenToString()
        val short = component.flattenToShortString()
        return enabledServicesInSettings(context).any {
            it.equals(flat, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
    }

    /** 系统设置中登记的全部无障碍服务组件（原始字符串）。 */
    fun enabledServicesInSettings(context: Context): List<String> {
        val raw = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取 enabled_accessibility_services 失败: ${e.message}")
            null
        } ?: return emptyList()
        if (raw.isBlank() || raw == "null") return emptyList()
        return raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 服务实例是否真的在运行——这是唯一可信的“正在工作”判据。 */
    fun isRunning(): Boolean = AfuAccessibilityService.isServiceActive.value

    /** 汇总当前真实状态。 */
    fun currentState(context: Context): AccessibilityState = when {
        isRunning() -> AccessibilityState.RUNNING
        isEnabledInSettings(context) -> AccessibilityState.ENABLED_NOT_RUNNING
        else -> AccessibilityState.DISABLED
    }

    /**
     * 是否处于系统的“受限设置”状态。
     *
     * Android 13+ 侧载安装的 APK 默认受限，此时无障碍开关可以拨开但服务不会被拉起，
     * 表现就是设置里“已打开但无法运行”，必须先到应用信息页允许受限设置。
     */
    fun isRestrictedBySystem(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        // 状态栏每 5 秒刷新一次，AppOps 查询在部分 ROM 上会抛 SecurityException，
        // 这里做一个短 TTL 缓存，避免重复查询和日志刷屏。
        val now = System.currentTimeMillis()
        if (now - restrictedCachedAtMs < APP_OP_CACHE_MS) return restrictedCached
        val restricted = isAppOpBlocked(context, OPSTR_ACCESS_RESTRICTED_SETTINGS)
        restrictedCached = restricted
        restrictedCachedAtMs = now
        return restricted
    }

    /** 系统的无障碍访问是否被拒绝（服务在设置里开着、但拿不到任何窗口内容）。 */
    fun isAccessibilityAccessBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return isAppOpBlocked(context, OPSTR_ACCESS_ACCESSIBILITY)
    }

    private fun isAppOpBlocked(context: Context, opName: String): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(opName, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
        } catch (e: Exception) {
            // 普通应用没有 GET_APP_OPS_STATS 时，部分 ROM 会直接抛 SecurityException。
            // 这属于“查不到”而不是“被限制”，按未限制处理即可。
            AppLogger.d(TAG, "AppOps 查询不可用($opName): ${e.message}")
            false
        }
    }

    /**
     * 检测本应用是否已被加入系统电池优化白名单（允许后台无限制活动）。
     *
     * 这里只负责展示状态；真正的“长期驻留”不依赖前台服务或常驻通知。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 跳转到“本服务”的系统设置详情页（Android 11+ 支持直达），
     * 用户只需拨一次滑块，无需在长长的无障碍列表里翻找。
     */
    fun openServiceSettings(context: Context): Boolean {
        val component = serviceComponent(context)
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

    /** 跳转到应用信息页（用于解除“受限设置”）。 */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
