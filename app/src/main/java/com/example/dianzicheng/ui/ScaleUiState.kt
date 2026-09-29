package com.example.dianzicheng.ui

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile

/**
 * 阿福 UI 抓取主界面的 UI 状态快照。
 */
data class ScaleUiState(
    val isAccessibilityEnabled: Boolean = false,
    val currentMeasurement: BodyMeasurement? = null,
    val lastLogMessage: String = "正在检测无障碍服务状态...",
    val rawInspectionLog: String = "暂无排查日志",
    val userProfile: UserProfile = UserProfile(),
    val isGarminSyncing: Boolean = false,
    val garminSyncResult: String? = null,
    val error: String? = null
)
