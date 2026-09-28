package com.example.dianzicheng.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.dianzicheng.data.garmin.GarminAuthManager
import com.example.dianzicheng.data.garmin.GarminSyncService
import com.example.dianzicheng.data.local.AppDatabase
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs

private const val TAG = "AfuAccessibilityService"

class AfuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var database: AppDatabase
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var garminAuthManager: GarminAuthManager
    private lateinit var garminSyncService: GarminSyncService

    private var lastScrapeTime = 0L
    private var lastRecordedWeight = 0.0
    private var lastRecordedFat = 0.0

    companion object {
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _lastCapturedMeasurement = MutableStateFlow<BodyMeasurement?>(null)
        val lastCapturedMeasurement: StateFlow<BodyMeasurement?> = _lastCapturedMeasurement.asStateFlow()

        private val _lastScrapeLog = MutableStateFlow<String>("等待阿福 App 身体数据详情页...")
        val lastScrapeLog: StateFlow<String> = _lastScrapeLog.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        database = AppDatabase.getInstance(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)
        garminAuthManager = GarminAuthManager(applicationContext, preferenceManager)
        garminSyncService = GarminSyncService(applicationContext, garminAuthManager, database.scaleDao())

        AppLogger.i(TAG, "阿福无障碍服务已启动连接")
        _lastScrapeLog.value = "无障碍服务已激活，监听 com.antgroup.aijk.android"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.antgroup.aijk.android") return

        // 防抖：1 秒内仅触发一次解析
        val now = System.currentTimeMillis()
        if (now - lastScrapeTime < 1000L) return
        lastScrapeTime = now

        serviceScope.launch(Dispatchers.Default) {
            val rootNode = try {
                rootInActiveWindow ?: event.source
            } catch (e: Throwable) {
                null
            } ?: return@launch

            val textList = mutableListOf<String>()
            traverseNodes(rootNode, textList)

            if (textList.isEmpty()) return@launch

            val profile = preferenceManager.userProfile.first() ?: UserProfile()
            val parseResult = AfuUiParser.parseScreenTexts(textList, profile) ?: return@launch

            val measurement = parseResult.measurement

            // 去重逻辑：1分钟内同一重量（相差<0.05kg）且同体脂率（相差<0.1%）不再重复入库与上传
            if (abs(measurement.weightKg - lastRecordedWeight) < 0.05 &&
                abs(measurement.bodyFatPct - lastRecordedFat) < 0.1 &&
                (now - (measurement.syncedToGarminAtEpochMs ?: 0L)) < 60_000L
            ) {
                return@launch
            }

            lastRecordedWeight = measurement.weightKg
            lastRecordedFat = measurement.bodyFatPct

            AppLogger.i(TAG, "成功从阿福 UI 抓取数据: weight=${measurement.weightKg}kg, fat=${measurement.bodyFatPct}%, muscle=${measurement.muscleKg}kg, fields=${parseResult.matchedFieldsCount}")

            // 1. 保存到本地数据库
            val savedRecord = scaleRepository.saveMeasurement(measurement) ?: return@launch
            _lastCapturedMeasurement.value = savedRecord
            _lastScrapeLog.value = "成功提取: ${savedRecord.weightKg}kg / 体脂 ${savedRecord.bodyFatPct}%"

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "已截获阿福数据: ${savedRecord.weightKg}kg / 体脂 ${savedRecord.bodyFatPct}%",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 2. 自动上传 Garmin
            val isGarminLoggedIn = preferenceManager.isGarminLoggedIn.first()
            if (isGarminLoggedIn) {
                _lastScrapeLog.value = "正在上传 Garmin (${savedRecord.weightKg}kg)..."
                val uploadResult = garminSyncService.uploadMeasurement(savedRecord)
                if (uploadResult.isSuccess) {
                    scaleRepository.markSynced(savedRecord.id, System.currentTimeMillis())
                    _lastScrapeLog.value = "已同步至 Garmin (${savedRecord.weightKg}kg)"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "已同步至 Garmin Connect", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val err = uploadResult.exceptionOrNull()?.message ?: "未知错误"
                    _lastScrapeLog.value = "Garmin 上传失败: $err"
                    AppLogger.e(TAG, "Garmin 上传失败: $err")
                }
            } else {
                _lastScrapeLog.value = "数据已保存本地（Garmin 未登录）"
            }
        }
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, outList: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            outList.add(text)
        }
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) {
            outList.add(desc)
        }
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                traverseNodes(child, outList)
            } catch (e: Throwable) {
                // 安全处理子节点跨进程异常
            }
        }
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "阿福无障碍服务被打断")
        _isServiceActive.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        serviceScope.cancel()
    }
}
