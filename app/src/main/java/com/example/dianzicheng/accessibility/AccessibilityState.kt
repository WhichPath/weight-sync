package com.example.dianzicheng.accessibility

/**
 * 无障碍服务的三种真实状态。
 *
 * 系统设置里的“滑块是开的”只代表服务已被登记，真正决定“能不能抓数据”的是
 * 服务实例有没有被系统绑定并回调 [AfuAccessibilityService.onServiceConnected]。
 */
enum class AccessibilityState {
    /** 系统设置里完全没有登记本服务 */
    DISABLED,

    /** 系统设置里已登记（滑块是开的），但服务实例并没有真正运行 */
    ENABLED_NOT_RUNNING,

    /** 服务实例已连接，真正在跑 */
    RUNNING
}
