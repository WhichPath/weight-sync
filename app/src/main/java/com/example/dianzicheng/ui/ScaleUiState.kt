package com.example.dianzicheng.ui

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile

/**
 * 阿福 UI 抓取主界面的 UI 状态快照。
 */
data class ScaleUiState(
    val isShizukuRunning: Boolean = false,
    val hasShizukuPermission: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val currentMeasurement: BodyMeasurement? = null,
    val lastLogMessage: String = "正在检测服务状态...",
    val userProfile: UserProfile = UserProfile(),
    val isGarminSyncing: Boolean = false,
    val garminSyncResult: String? = null,
    val error: String? = null
)
