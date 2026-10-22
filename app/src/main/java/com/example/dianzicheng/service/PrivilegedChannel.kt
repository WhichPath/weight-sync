package com.example.dianzicheng.service

import android.content.pm.PackageManager
import com.example.dianzicheng.data.local.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "PrivilegedChannel"

/**
 * 特权通道：通过 Shizuku 以 shell 身份执行命令，从而获得写入 Secure Settings 的能力。
 *
 * 有了它就能在 App 内直接改写 `enabled_accessibility_services`，
 * 完成"开启 / 关闭 / 重启无障碍服务"——和 GKD 的做法一致，用户不需要再跳转到系统设置。
 *
 * 没有 Shizuku 时一切功能照常工作，只是开关动作退化为"跳到该服务的系统设置页拨一次滑块"。
 */
object PrivilegedChannel {

    /** 申请 Shizuku 权限时使用的请求码 */
    const val REQUEST_CODE = 0x5A1E

    enum class State {
        /** Shizuku 未安装或未运行 */
        UNAVAILABLE,

        /** Shizuku 正在运行，但还没有授权本应用 */
        NEED_PERMISSION,

        /** 可以执行特权命令 */
        READY
    }

    /**
     * 当前特权通道状态。
     */
    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NEED_PERMISSION
        }
    } catch (e: Throwable) {
        AppLogger.w(TAG, "Shizuku 状态检测异常: ${e.message}")
        State.UNAVAILABLE
    }

    /**
     * 拉起 Shizuku 的授权弹窗（必须在主线程、持有 Activity 上下文时调用）。
     */
    fun requestPermission() {
        try {
            AppLogger.i(TAG, "请求 Shizuku 授权")
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "请求 Shizuku 授权失败: ${e.message}")
        }
    }

    /**
     * 执行一条 shell 命令（以 Shizuku 的身份，即 shell uid）。
     */
    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (state() != State.READY) {
            return@withContext Result.failure(IllegalStateException("Shizuku 未就绪"))
        }
        try {
            // Shizuku 13.x 的 newProcess 是私有方法，需要反射调用；
            // 本工程 proguard 规则开启了 -dontobfuscate，方法名不会被改名。
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
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
                Result.failure(RuntimeException("命令失败(code=$exitCode): $stderr"))
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "执行特权命令失败: $command -> ${e.message}")
            Result.failure(e)
        }
    }
}
