package com.example.dianzicheng.service

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.dianzicheng.data.local.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "ShizukuManager"
private const val SERVICE_COMPONENT = "com.example.dianzicheng/com.example.dianzicheng.service.AfuAccessibilityService"

object ShizukuManager {

    /**
     * 检查 Shizuku 守护进程是否在运行
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 检查是否已获得 Shizuku 权限
     */
    fun hasPermission(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 请求 Shizuku 授权
     */
    fun requestPermission(requestCode: Int) {
        if (isShizukuRunning() && !hasPermission()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                AppLogger.e(TAG, "请求 Shizuku 权限失败", e)
            }
        }
    }

    /**
     * 检查无障碍服务在系统层面是否已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        return accessibilityEnabled == 1 && (enabledServices.contains("com.example.dianzicheng") && enabledServices.contains("AfuAccessibilityService"))
    }

    /**
     * 执行底层 Shell 命令（通过 Shizuku）
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext Result.failure(IllegalStateException("Shizuku 未授权或未运行"))
        }

        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(RuntimeException("Shell 执行失败(code=$exitCode): $error"))
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "执行 Shizuku 命令异常: $command", e)
            Result.failure(e)
        }
    }

    /**
     * 通过 Shizuku 静默启用无障碍服务
     */
    suspend fun enableAccessibilityServiceSilently(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext Result.failure(IllegalStateException("Shizuku 未授权"))
        }

        try {
            // 1. 获取当前已启用的无障碍服务列表
            val getResult = executeCommand("settings get secure enabled_accessibility_services")
            val currentServices = getResult.getOrNull()?.trim() ?: ""

            // 2. 如果尚未包含本服务组件，则拼接入列表
            val updatedServices = if (currentServices.isBlank() || currentServices == "null") {
                SERVICE_COMPONENT
            } else if (!currentServices.contains(SERVICE_COMPONENT)) {
                "$currentServices:$SERVICE_COMPONENT"
            } else {
                currentServices
            }

            // 3. 写入新的服务列表并激活 accessibility 开关
            val putResult = executeCommand("settings put secure enabled_accessibility_services \"$updatedServices\"")
            if (putResult.isFailure) return@withContext Result.failure(putResult.exceptionOrNull()!!)

            val enableSwitchResult = executeCommand("settings put secure accessibility_enabled 1")
            if (enableSwitchResult.isFailure) return@withContext Result.failure(enableSwitchResult.exceptionOrNull()!!)

            AppLogger.i(TAG, "通过 Shizuku 成功静默激活无障碍服务: $SERVICE_COMPONENT")
            Result.success(Unit)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "静默激活无障碍服务失败", e)
            Result.failure(e)
        }
    }
}
