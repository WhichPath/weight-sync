package com.example.dianzicheng.ui

import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile

/**
 * 称重主界面的 UI 状态快照。
 */
data class ScaleUiState(
    val connection: BleScaleClient.ConnectionState = BleScaleClient.ConnectionState.IDLE,
    val liveWeightKg: Double = 0.0,
    val impedanceOhm: Double = 0.0,
    val isStable: Boolean = false,
    val currentMeasurement: BodyMeasurement? = null,
    val userProfile: UserProfile = UserProfile(),
    val isGarminSyncing: Boolean = false,
    val garminSyncResult: String? = null,
    val error: String? = null,
    val discoveredDeviceName: String? = null,
    val discoveredDeviceMac: String? = null,
    val pairedDeviceMac: String? = null,
    val pairedDeviceName: String? = null,
    val isDeviceRemembered: Boolean = false,
    val discoveredScales: List<BleScaleClient.DiscoveredScaleDevice> = emptyList()
)
