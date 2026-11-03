package com.example.dianzicheng.accessibility

import android.content.Context
import android.provider.Settings
import com.example.dianzicheng.core.logging.AppLogger

private const val TAG = "SecureSettingsGateway"

/**
 * 写 Secure Settings 的抽象。
 *
 * 两个实现路径：
 *  - 永久授权：直接调用 [Settings.Secure.putString] / [Settings.Secure.putInt]；
 *  - 仅 Shizuku：退化为 `settings put secure ...` shell 命令。
 *
 * Controller 只依赖这个接口，因此“是否有权限”“走哪条路径”不会散落到业务代码里。
 */
interface SecureSettingsGateway {
    /** 当前是否至少有一种可用的写入方式。 */
    val available: Boolean

    suspend fun putInt(name: String, value: Int): Result<Unit>

    suspend fun putString(name: String, value: String): Result<Unit>
}

class AndroidSecureSettingsGateway(
    private val context: Context
) : SecureSettingsGateway {

    override val available: Boolean
        get() = PrivilegedAccess.hasPermanentGrant(context) ||
            PrivilegedAccess.shizukuState() == ShizukuState.READY

    override suspend fun putInt(name: String, value: Int): Result<Unit> {
        if (PrivilegedAccess.hasPermanentGrant(context)) {
            return runCatching {
                val ok = Settings.Secure.putInt(context.contentResolver, name, value)
                if (!ok) throw SecurityException("Settings.Secure.putInt($name) 返回 false")
            }.onFailure {
                AppLogger.w(TAG, "直接写入 $name 失败: ${it.message}")
            }
        }
        return putViaShizuku(name, value.toString())
    }

    override suspend fun putString(name: String, value: String): Result<Unit> {
        if (PrivilegedAccess.hasPermanentGrant(context)) {
            return runCatching {
                val ok = Settings.Secure.putString(context.contentResolver, name, value)
                if (!ok) throw SecurityException("Settings.Secure.putString($name) 返回 false")
            }.onFailure {
                AppLogger.w(TAG, "直接写入 $name 失败: ${it.message}")
            }
        }
        return putViaShizuku(name, value)
    }

    private suspend fun putViaShizuku(name: String, value: String): Result<Unit> {
        val escaped = value.replace("'", "'\\''")
        return PrivilegedAccess.exec("settings put secure $name '$escaped'").map { }
    }
}
