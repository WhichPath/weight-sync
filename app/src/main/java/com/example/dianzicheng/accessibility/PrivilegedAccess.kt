package com.example.dianzicheng.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.example.dianzicheng.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

private const val TAG = "PrivilegedAccess"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases"

/**
 * 特权通道：一次性把 WRITE_SECURE_SETTINGS 授予本应用，之后即可直接写 Secure Settings。
 *
 * 这是 GKD「高级授权」的同款模型：
 *  1. 借助 Shizuku（shell 身份）执行
 *     `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow`
 *     `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`
 *  2. 授权写入系统后**永久保留**，即使关闭开发者选项、重启手机、Shizuku 退出也依然有效；
 *  3. 之后 App 直接 `Settings.Secure.putString(...)` 开关无障碍，不再依赖 Shizuku 常驻。
 *
 * 没有 Shizuku 时，UI 会给出同样的 ADB 命令供用户手动执行一次。
 */
object PrivilegedAccess {

    /** 申请 Shizuku 权限时使用的请求码 */
    const val REQUEST_CODE = 0x5A1E

    /**
     * WRITE_SECURE_SETTINGS 在 SDK 中是 @SystemApi 字符串，直接使用字面量，
     * 避免不同 compileSdk 下的可见性差异。
     */
    const val PERMISSION_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    sealed interface ProvisionResult {
        /** 已持有永久授权 */
        data object Success : ProvisionResult

        /** Shizuku 在运行，但本应用还没有获得 Shizuku 授权 */
        data object NeedShizukuPermission : ProvisionResult

        /** Shizuku 未安装 / 未运行 */
        data object ShizukuUnavailable : ProvisionResult

        /** 命令执行失败或授权未生效 */
        data class Failed(val message: String) : ProvisionResult
    }

    /** 是否已持有永久写 Secure Settings 权限。 */
    fun hasPermanentGrant(context: Context): Boolean = try {
        context.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        AppLogger.w(TAG, "检测 WRITE_SECURE_SETTINGS 失败: ${e.message}")
        false
    }

    /** 当前 Shizuku 连接状态。 */
    fun shizukuState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuState.READY

            else -> ShizukuState.NEED_PERMISSION
        }
    } catch (e: Throwable) {
        AppLogger.w(TAG, "Shizuku 状态检测异常: ${e.message}")
        ShizukuState.UNAVAILABLE
    }

    /** 生成完整特权状态快照。 */
    fun status(context: Context): PrivilegeStatus = PrivilegeStatus(
        hasPermanentGrant = hasPermanentGrant(context),
        shizukuState = shizukuState()
    )

    /** 拉起 Shizuku 的授权弹窗（必须在主线程、持有 Activity 上下文时调用）。 */
    fun requestPermission() {
        try {
            AppLogger.i(TAG, "请求 Shizuku 授权")
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "请求 Shizuku 授权失败: ${e.message}")
        }
    }

    /**
     * 一次性永久授权：设置受限设置 AppOp，并调用 pm grant 写入 WRITE_SECURE_SETTINGS。
     *
     * 仅当 Shizuku 已就绪时可用；没有 Shizuku 时请使用 [adbProvisionCommand]。
     */
    suspend fun provisionPermanentAccess(context: Context): ProvisionResult {
        if (hasPermanentGrant(context)) return ProvisionResult.Success

        when (shizukuState()) {
            ShizukuState.NEED_PERMISSION -> return ProvisionResult.NeedShizukuPermission
            ShizukuState.UNAVAILABLE -> return ProvisionResult.ShizukuUnavailable
            ShizukuState.READY -> Unit
        }

        val packageName = context.packageName

        // 这两条 AppOps 用于解除 Android 13+ 侧载应用的“受限设置”限制。
        // 部分 ROM 可能没有某个 op，失败不阻断后面的 pm grant。
        listOf(
            "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow",
            "appops set $packageName ACCESS_ACCESSIBILITY allow"
        ).forEach { command ->
            exec(command).onFailure {
                AppLogger.w(TAG, "AppOps 设置失败（可忽略）: $command -> ${it.message}")
            }
        }

        val grantCommand = "pm grant $packageName $PERMISSION_WRITE_SECURE_SETTINGS"
        val grantResult = exec(grantCommand)
        if (grantResult.isFailure) {
            val error = grantResult.exceptionOrNull()?.message ?: "pm grant 执行失败"
            AppLogger.e(TAG, "永久授权失败: $error")
            return ProvisionResult.Failed(error)
        }

        // pm grant 在部分 ROM 上可能返回 0 但并未真正写入，必须回读权限确认。
        if (!hasPermanentGrant(context)) {
            AppLogger.e(TAG, "pm grant 已执行但权限未生效: ${grantResult.getOrNull()}")
            return ProvisionResult.Failed("pm grant 已执行，但系统未授予 WRITE_SECURE_SETTINGS")
        }

        AppLogger.i(TAG, "永久授权成功：已持有 WRITE_SECURE_SETTINGS，可脱离 Shizuku 运行")
        return ProvisionResult.Success
    }

    /** 手动 ADB 授权命令（复制到电脑终端执行一次即可）。 */
    fun adbProvisionCommand(context: Context): String {
        val packageName = context.packageName
        return "adb shell \"appops set $packageName ACCESS_RESTRICTED_SETTINGS allow; " +
            "appops set $packageName ACCESS_ACCESSIBILITY allow; " +
            "pm grant $packageName $PERMISSION_WRITE_SECURE_SETTINGS\""
    }

    /** 以 Shizuku 的 shell 身份执行一条命令。 */
    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (shizukuState() != ShizukuState.READY) {
            return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        }
        try {
            // Shizuku 13.x 的 newProcess 是私有方法，需要反射调用；
            // 本工程 proguard 规则开启了 -dontobfuscate，方法名不会被改名。
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success(stdout.trim())
            } else {
                Result.failure(RuntimeException("命令失败(code=$exitCode): ${stderr.trim()}"))
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "执行特权命令失败: $command -> ${e.message}")
            Result.failure(e)
        }
    }

    /** 打开 Shizuku App；未安装时退回 GitHub releases。 */
    fun openShizuku(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launchIntent) }.isSuccess) return true
        }
        return openShizukuDownload(context)
    }

    /** 打开 Shizuku 下载页。 */
    fun openShizukuDownload(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASES_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun isShizukuInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private val newProcessMethod: Method by lazy {
        Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            .apply { isAccessible = true }
    }
}
