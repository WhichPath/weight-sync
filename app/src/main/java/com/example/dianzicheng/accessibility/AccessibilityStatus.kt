package com.example.dianzicheng.accessibility

/** Shizuku 特权服务的连接状态。 */
enum class ShizukuState {
    /** 未安装、未启动或 binder 已断开 */
    UNAVAILABLE,

    /** 服务在运行，但本应用还没有获得 Shizuku 授权 */
    NEED_PERMISSION,

    /** 服务在运行，且本应用已获得 Shizuku 授权 */
    READY
}

/**
 * 当前可用的“应用内改系统设置”能力。
 *
 * 注意：[PERMANENT] 与 [SHIZUKU_READY] 是两种不同的授权模型：
 *  - PERMANENT：本应用持有 WRITE_SECURE_SETTINGS，重启手机 / 关闭开发者选项 / Shizuku 退出后依然有效；
 *  - SHIZUKU_READY：只是当前这次 Shizuku 服务在线，可以临时执行 shell 命令。
 *
 * 因此 UI 应优先展示 PERMANENT；Shizuku 只作为“完成一次性永久授权”的入口。
 */
enum class PrivilegeMode {
    PERMANENT,
    SHIZUKU_READY,
    SHIZUKU_NEED_PERMISSION,
    NONE
}

/** 特权通道快照，不含任何 Android 上下文，方便在 JVM 单元测试中直接验证。 */
data class PrivilegeStatus(
    val hasPermanentGrant: Boolean,
    val shizukuState: ShizukuState
) {
    val mode: PrivilegeMode
        get() = when {
            hasPermanentGrant -> PrivilegeMode.PERMANENT
            shizukuState == ShizukuState.READY -> PrivilegeMode.SHIZUKU_READY
            shizukuState == ShizukuState.NEED_PERMISSION -> PrivilegeMode.SHIZUKU_NEED_PERMISSION
            else -> PrivilegeMode.NONE
        }

    /** 是否可以在应用内直接开关 / 重启无障碍服务。 */
    val canControlInApp: Boolean
        get() = hasPermanentGrant || shizukuState == ShizukuState.READY

    /** 是否还需要完成一次性的永久授权引导。 */
    val needsPermanentSetup: Boolean
        get() = !hasPermanentGrant
}

/** 首页与权限向导使用的聚合状态。 */
data class AccessibilityStatus(
    val state: AccessibilityState,
    val privilege: PrivilegeStatus,
    val isRestrictedBySystem: Boolean,
    val isBatteryOptimizationIgnored: Boolean
)
