package com.example.dianzicheng

import android.app.Application
import com.example.dianzicheng.di.AppContainer

/**
 * 应用入口：在进程启动时装配唯一依赖容器。
 */
class WeightSyncApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
