package com.example.dianzicheng.accessibility

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.dianzicheng.MainActivity
import com.example.dianzicheng.WeightSyncApp
import com.example.dianzicheng.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AfuTileService"

/**
 * 可选的“通知栏快捷开关”。
 *
 * 替代常驻前台通知：
 *  - 用户把「阿福同步」磁贴拖进快捷开关面板后，磁贴只在面板可见时显示状态；
 *  - 点一下即可开启 / 关闭无障碍服务；面板打开时如果发现服务掉线，会自动尝试恢复；
 *  - 不产生任何常驻通知，也不占用前台服务。
 *
 * 这与 GKD 的「无感保活」是同一思路：把“保活”降级为用户可见、可随时点按的磁贴，
 * 而不是长期挂一条通知。
 */
class AfuTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repository get() = (application as WeightSyncApp).container.accessibilityRepository

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            updateTile()
            val status = withContext(Dispatchers.IO) { repository.status() }
            if (status.state == AccessibilityState.ENABLED_NOT_RUNNING &&
                status.privilege.canControlInApp
            ) {
                AppLogger.i(TAG, "快捷开关面板打开，尝试恢复掉线的无障碍服务")
                repository.restart()
                updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val status = withContext(Dispatchers.IO) { repository.status() }
            when {
                !status.privilege.canControlInApp -> openApp()

                status.state == AccessibilityState.RUNNING -> {
                    repository.setEnabled(false)
                    updateTile()
                }

                else -> {
                    repository.setEnabled(true)
                    updateTile()
                }
            }
        }
    }

    private suspend fun updateTile() {
        val running = try {
            withContext(Dispatchers.IO) {
                repository.status().state == AccessibilityState.RUNNING
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "读取无障碍状态失败: ${e.message}")
            false
        }
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pendingIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
