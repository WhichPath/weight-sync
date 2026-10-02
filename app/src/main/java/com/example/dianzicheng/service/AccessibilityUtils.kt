package com.example.dianzicheng.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务工具类，纯原生实现，不依赖任何第三方提权组件。
 */
object AccessibilityUtils {

    /**
     * 检测本应用的无障碍服务是否已在系统设置中启用。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // 1. 先通过服务运行时的实时状态判断（最优先、最准确）
        if (AfuAccessibilityService.isServiceActive.value) {
            return true
        }

        val myPackage = context.packageName
        val serviceClass = AfuAccessibilityService::class.java
        val simpleName = serviceClass.simpleName

        // 2. 检查系统 AccessibilityManager 已启用的反馈服务列表
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            try {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in enabledServices) {
                    val pkgMatch = service.resolveInfo?.serviceInfo?.packageName == myPackage
                    val idMatch = service.id?.contains(myPackage) == true && service.id?.contains(simpleName) == true
                    if (pkgMatch || idMatch) {
                        return true
                    }
                }
            } catch (e: Exception) {
                // 忽略系统列表读取偶发异常
            }
        }

        // 3. 读取系统 Secure Settings 中的 enabled_accessibility_services（支持多种组件表示形式）
        try {
            val expectedComponent = ComponentName(context, serviceClass).flattenToString()
            val expectedShortComponent = "${myPackage}/.service.${simpleName}"
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = settingValue.split(":")
            for (rawItem in colonSplitter) {
                val item = rawItem.trim()
                if (item.equals(expectedComponent, ignoreCase = true) ||
                    item.equals(expectedShortComponent, ignoreCase = true) ||
                    (item.startsWith("$myPackage/") && item.contains(simpleName))
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            // 权限受限环境静默容错
        }

        return false
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
