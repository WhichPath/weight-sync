package com.example.dianzicheng.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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

    private lateinit var database: AppDatabase
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var preferenceManager: PreferenceManager

    // 缓存“身体指标记录”弹窗生命周期内的所有可见节点文本（处理分页滚动）
    private val dialogAccumulatedTexts = LinkedHashSet<String>()
    private var lastScrollTime = 0L
    private var lastProcessedFingerprint: String? = null
    private var isDialogCurrentlyVisible = false

    companion object {
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _lastCapturedMeasurement = MutableStateFlow<BodyMeasurement?>(null)
        val lastCapturedMeasurement: StateFlow<BodyMeasurement?> = _lastCapturedMeasurement.asStateFlow()

        private val _lastScrapeLog = MutableStateFlow<String>("等待阿福 App 测量详情弹窗（身体指标记录）...")
        val lastScrapeLog: StateFlow<String> = _lastScrapeLog.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        database = AppDatabase.getInstance(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)

        AppLogger.i(TAG, "阿福无障碍服务已启动连接")
        _lastScrapeLog.value = "无障碍服务已激活，监听阿福体成分详情面板"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.antgroup.aijk.android") return

        serviceScope.launch(Dispatchers.Default) {
            val rootNode = try {
                rootInActiveWindow ?: event.source
            } catch (e: Throwable) {
                null
            } ?: return@launch

            val currentTexts = mutableListOf<String>()
            traverseNodes(rootNode, currentTexts)

            if (currentTexts.isEmpty()) return@launch

            // 仅当用户主动打开或进入“身体指标记录”弹窗面板时才进行抓取，避免主页历史残留误抓
            val hasDialogTitle = currentTexts.any { it.contains("身体指标记录") }
            if (!hasDialogTitle) {
                if (isDialogCurrentlyVisible) {
                    // 用户关闭了弹窗，重置累加池
                    isDialogCurrentlyVisible = false
                    dialogAccumulatedTexts.clear()
                }
                return@launch
            }

            isDialogCurrentlyVisible = true
            dialogAccumulatedTexts.addAll(currentTexts)

            val now = System.currentTimeMillis()

            // 如果当前累加的文本尚未包含底部的指标（例如“基础代谢”或“骨骼肌量”），且距离上次滚动超过 1.2 秒，自动轻微向前滚动一次
            val hasBottomMetrics = dialogAccumulatedTexts.any { it.contains("基础代谢") || it.contains("骨骼肌量") }
            if (!hasBottomMetrics && (now - lastScrollTime > 1200L)) {
                lastScrollTime = now
                val scrollable = findScrollableNode(rootNode)
                if (scrollable != null) {
                    AppLogger.d(TAG, "发现指标滚动面板，自动执行向下滑动以加载全部指标")
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }

            val profile = preferenceManager.userProfile.first() ?: UserProfile()
            val parseResult = AfuUiParser.parseScreenTexts(dialogAccumulatedTexts.toList(), profile) ?: return@launch

            // 检查复合指纹去重（日期+时间+体重+体脂+肌肉）
            if (parseResult.fingerprint == lastProcessedFingerprint) {
                return@launch
            }

            val measurement = parseResult.measurement

            // 检查本地数据库中是否已存在该条记录（以防打开的是过去的旧记录弹窗）
            val existingRecords = scaleRepository.getAllMeasurements()
            val isDuplicateInDb = existingRecords.any {
                abs(it.measuredAtEpochMs - measurement.measuredAtEpochMs) < 120_000L &&
                        abs(it.weightKg - measurement.weightKg) < 0.05
            }

            if (isDuplicateInDb) {
                lastProcessedFingerprint = parseResult.fingerprint
                _lastScrapeLog.value = "已存在该测量记录（${parseResult.dateStr} ${parseResult.timeStr}），无需重复记录"
                AppLogger.d(TAG, "识别到已存在的旧记录，跳过: ${parseResult.fingerprint}")
                return@launch
            }

            // 新测量记录入库，不自动上传，等待用户在应用内确认
            lastProcessedFingerprint = parseResult.fingerprint
            AppLogger.i(TAG, "成功提取全新测量数据: ${measurement.weightKg}kg / 体脂 ${measurement.bodyFatPct}% / 肌肉 ${measurement.muscleKg}kg (${parseResult.matchedFieldsCount}项)")

            val savedRecord = scaleRepository.saveMeasurement(measurement) ?: return@launch
            _lastCapturedMeasurement.value = savedRecord
            _lastScrapeLog.value = "已截获数据: ${savedRecord.weightKg}kg / 体脂 ${savedRecord.bodyFatPct}%（请在 App 中确认上传）"

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "已截获阿福数据: ${savedRecord.weightKg}kg，请在 App 中核对并确认上传 Garmin",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
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
        AppLogger.w(TAG, "阿福无障碍服务被中断")
        _isServiceActive.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        serviceScope.cancel()
    }
}
