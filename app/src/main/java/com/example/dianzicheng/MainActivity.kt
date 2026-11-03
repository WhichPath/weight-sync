package com.example.dianzicheng

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dianzicheng.accessibility.PrivilegedAccess
import com.example.dianzicheng.ui.HistoryViewModel
import com.example.dianzicheng.ui.MainScreen
import com.example.dianzicheng.ui.ProfileViewModel
import com.example.dianzicheng.ui.ScaleViewModel
import com.example.dianzicheng.ui.theme.电子秤Theme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity(), Shizuku.OnRequestPermissionResultListener {

    private val container get() = (application as WeightSyncApp).container

    private var scaleViewModelRef: ScaleViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 监听 Shizuku 授权结果：授权后自动继续一次性永久授权流程
        runCatching { Shizuku.addRequestPermissionResultListener(this) }

        // 返回键优化：退回后台而不彻底销毁进程，避免刚授权完就回到桌面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        enableEdgeToEdge()
        setContent {
            电子秤Theme {
                val scaleViewModel: ScaleViewModel =
                    viewModel(factory = container.scaleViewModelFactory())
                val historyViewModel: HistoryViewModel =
                    viewModel(factory = container.historyViewModelFactory())
                val profileViewModel: ProfileViewModel =
                    viewModel(factory = container.profileViewModelFactory())

                scaleViewModelRef = scaleViewModel

                MainScreen(
                    scaleViewModel = scaleViewModel,
                    historyViewModel = historyViewModel,
                    profileViewModel = profileViewModel
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户从系统设置或后台返回时自动刷新状态，并尝试修复掉线服务
        scaleViewModelRef?.onAppResumed()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(this) }
        super.onDestroy()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode != PrivilegedAccess.REQUEST_CODE) return
        scaleViewModelRef?.onShizukuPermissionResult(
            grantResult == PackageManager.PERMISSION_GRANTED
        )
    }
}
