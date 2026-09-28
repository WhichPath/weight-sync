package com.example.dianzicheng.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
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
        // 1. 先通过服务运行时的实时状态判断
        if (AfuAccessibilityService.isServiceActive.value) {
            return true
        }

        // 2. 检查系统 AccessibilityManager 已启用的反馈服务列表
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val myPackage = context.packageName
            for (service in enabledServices) {
                if (service.resolveInfo?.serviceInfo?.packageName == myPackage) {
                    return true
                }
            }
        }

        // 3. 读取系统 Secure Settings 中的 enabled_accessibility_services
        try {
            val expectedComponent = ComponentName(context, AfuAccessibilityService::class.java).flattenToString()
            val expectedShortComponent = "${context.packageName}/.service.AfuAccessibilityService"
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = settingValue.split(":")
            for (item in colonSplitter) {
                if (item.equals(expectedComponent, ignoreCase = true) || item.equals(expectedShortComponent, ignoreCase = true)) {
                    return true
                }
            }
        } catch (e: Exception) {
            // 权限受限环境静默容错
        }

        return false
    }
}
