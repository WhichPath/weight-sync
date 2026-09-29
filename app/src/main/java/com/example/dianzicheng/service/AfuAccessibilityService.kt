package com.example.dianzicheng.service

import android.accessibilityservice.AccessibilityService
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val TAG = "AfuAccessibilityService"

class AfuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var database: AppDatabase
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var preferenceManager: PreferenceManager

    // 缓存“身体指标记录”弹窗内的节点文本
    private val dialogAccumulatedTexts = LinkedHashSet<String>()
    private var lastProcessedFingerprint: String? = null
    private var lastMatchedFieldsCount = 0
    private var isInsideDialog = false

    companion object {
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _lastCapturedMeasurement = MutableStateFlow<BodyMeasurement?>(null)
        val lastCapturedMeasurement: StateFlow<BodyMeasurement?> = _lastCapturedMeasurement.asStateFlow()

        private val _lastScrapeLog = MutableStateFlow<String>("等待进入阿福【测量详情】面板...")
        val lastScrapeLog: StateFlow<String> = _lastScrapeLog.asStateFlow()

        // 供 UI 排查面板实时查看的原始文本与解析细节
        private val _lastRawInspectionLog = MutableStateFlow<String>("暂无捕获排查数据。在阿福打开测量详情后在此处查看抓取到的原始文本。")
        val lastRawInspectionLog: StateFlow<String> = _lastRawInspectionLog.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        database = AppDatabase.getInstance(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)

        AppLogger.i(TAG, "阿福无障碍服务已启动连接（被动详情监听模式）")
        _lastScrapeLog.value = "无障碍服务已激活，请在阿福点击【测量详情】"
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

            // 严格过滤：只有当用户在阿福中展示“身体指标记录”详情面板时才介入收集！
            val isDetailDialog = currentTexts.any { it.contains("身体指标记录") } ||
                    (currentTexts.any { it.contains("内脏脂肪") } && currentTexts.any { it.contains("体脂率") })

            if (!isDetailDialog) {
                if (isInsideDialog) {
                    // 用户退出了详情面板，清空累加池
                    isInsideDialog = false
                    dialogAccumulatedTexts.clear()
                    lastMatchedFieldsCount = 0
                }
                return@launch
            }

            isInsideDialog = true
            dialogAccumulatedTexts.addAll(currentTexts)

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val rawTextsList = dialogAccumulatedTexts.toList()

            AppLogger.d(TAG, "[$timeStr] 处于阿福详情面板，已累加可见文本 ${rawTextsList.size} 条")

            val profile = preferenceManager.userProfile.first() ?: UserProfile()
            val parseResult = AfuUiParser.parseScreenTexts(rawTextsList, profile)

            // 更新实时排查日志，方便在 UI 上随时查看
            val inspectionBuilder = StringBuilder()
            inspectionBuilder.append("【更新时间】$timeStr\n")
            inspectionBuilder.append("【累加节点数】${rawTextsList.size} 条\n")
            if (parseResult != null) {
                inspectionBuilder.append("【解析状态】成功 (匹配 ${parseResult.matchedFieldsCount} 项)\n")
                inspectionBuilder.append("【核心数据】体重=${parseResult.measurement.weightKg}kg, 体脂=${parseResult.measurement.bodyFatPct}%, 肌肉=${parseResult.measurement.muscleKg}kg\n")
                inspectionBuilder.append("【匹配详情】${parseResult.rawMatches}\n")
            } else {
                inspectionBuilder.append("【解析状态】等待核心字段（体重/体脂），尚未满足或未滑动到位\n")
            }
            inspectionBuilder.append("【抓取到的全部原始文本】:\n")
            rawTextsList.forEachIndexed { idx, txt ->
                inspectionBuilder.append("  [$idx] $txt\n")
            }
            _lastRawInspectionLog.value = inspectionBuilder.toString()

            if (parseResult == null) {
                return@launch
            }

            // 若本次提取到的有效项数没有超过已保存的项数，且指纹相同，则不需要重复写入
            if (parseResult.fingerprint == lastProcessedFingerprint && parseResult.matchedFieldsCount <= lastMatchedFieldsCount) {
                return@launch
            }

            val measurement = parseResult.measurement

            // 检查数据库中是否已存在该条记录（以防重复保存历史旧弹窗）
            val existingRecords = scaleRepository.getAllMeasurements()
            val isDuplicateInDb = existingRecords.any {
                abs(it.measuredAtEpochMs - measurement.measuredAtEpochMs) < 120_000L &&
                        abs(it.weightKg - measurement.weightKg) < 0.05
            }

            if (isDuplicateInDb && parseResult.matchedFieldsCount <= lastMatchedFieldsCount) {
                return@launch
            }

            lastProcessedFingerprint = parseResult.fingerprint
            lastMatchedFieldsCount = parseResult.matchedFieldsCount

            AppLogger.i(TAG, "详情面板提取成功: 体重 ${measurement.weightKg}kg / 体脂 ${measurement.bodyFatPct}% / 骨骼肌 ${measurement.skeletalMuscleKg}kg (${parseResult.matchedFieldsCount}项)")

            val savedRecord = scaleRepository.saveMeasurement(measurement) ?: return@launch
            _lastCapturedMeasurement.value = savedRecord
            _lastScrapeLog.value = "已从详情面板截获: ${savedRecord.weightKg}kg / 体脂 ${savedRecord.bodyFatPct}% (共${parseResult.matchedFieldsCount}项，待确认上传)"

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "已截获详情: ${savedRecord.weightKg}kg / 体脂 ${savedRecord.bodyFatPct}%，请在 App 中确认上传",
                    Toast.LENGTH_LONG
                ).show()
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
        AppLogger.w(TAG, "阿福无障碍服务被中断")
        _isServiceActive.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        serviceScope.cancel()
    }
}
