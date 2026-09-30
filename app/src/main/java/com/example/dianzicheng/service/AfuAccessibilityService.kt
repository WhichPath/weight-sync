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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val TAG = "AfuAccessibilityService"
private const val AFU_PACKAGE = "com.antgroup.aijk.android"

/** 详情弹窗关闭判定：静默超过该时长即认为弹窗已关闭（避免 Toast 等零散窗口误判） */
private const val SHEET_TIMEOUT_MS = 8_000L

/**
 * 阿福 App（蚂蚁阿福）无障碍抓取服务。
 *
 * v2.0.8 架构：
 *  1. **按窗口隔离快照**：每来一次事件，先按窗口分别抓取节点文本，只取包含
 *     “共 N 条记录，更新于 HH:mm”的那个窗口（即“身体指标记录”弹窗），彻底屏蔽主页卡片；
 *  2. **会话化累加**：一次弹窗 = 一个会话（以弹窗表头文本为 key），会话内把各次滚动的快照
 *     按「指标 → 数值」合并，滚动结束后自然收敛到完整 17 项；
 *  3. **不再用 Set 去重**：早期版本用 LinkedHashSet 累加，导致弹窗里与主页重名的标签/数值
 *     被静默吞掉（这正是 2.0.7 拿到 80.2kg / 22.0% 的直接原因），现在改为解析器内部按标签配对；
 *  4. **只认最新一次称重**：由弹窗表头的“更新于 HH:mm”锁定目标记录，越滚动越不会串历史数据。
 */
class AfuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var database: AppDatabase
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var preferenceManager: PreferenceManager
    private val saveMutex = Mutex()

    // ── 会话状态（一次弹窗打开 = 一个会话） ──
    private var sessionKey: String? = null
    private val sessionMetrics = LinkedHashMap<String, Double>()
    private var sessionWeight: Double? = null
    private var sessionEpochMs: Long? = null
    private var lastSeenSheetAt = 0L
    private var lastPublishedFields = 0
    private var lastPublishedFingerprint: String? = null
    private var toastShown = false

    companion object {
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _lastCapturedMeasurement = MutableStateFlow<BodyMeasurement?>(null)
        val lastCapturedMeasurement: StateFlow<BodyMeasurement?> = _lastCapturedMeasurement.asStateFlow()

        private val _lastScrapeLog = MutableStateFlow<String>("等待进入阿福【测量详情】面板...")
        val lastScrapeLog: StateFlow<String> = _lastScrapeLog.asStateFlow()

        /** 供 UI 排查面板实时查看的原始文本与解析细节 */
        private val _lastRawInspectionLog = MutableStateFlow<String>(
            "暂无捕获排查数据。在阿福打开测量详情后在此处查看抓取到的原始文本。"
        )
        val lastRawInspectionLog: StateFlow<String> = _lastRawInspectionLog.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceActive.value = true
        database = AppDatabase.getInstance(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)

        AppLogger.i(TAG, "阿福无障碍服务已启动连接（按窗口隔离 + 会话化累加模式）")
        _lastScrapeLog.value = "无障碍服务已激活，请在阿福点击【测量详情】"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != AFU_PACKAGE) return

        // 主线程即时遍历各窗口节点，避免协程异步执行时 event 被系统回收
        val snapshots = collectWindowTexts()
        val sheetTexts = selectSheetSnapshot(snapshots)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (sheetTexts == null) {
            handleNoSheet(stamp, pkg, snapshots)
            return
        }
        lastSeenSheetAt = System.currentTimeMillis()
        handleSheet(sheetTexts, stamp)
    }

    // ────────────────────────── 弹窗处理 ──────────────────────────

    private fun handleSheet(sheetTexts: List<String>, stamp: String) {
        serviceScope.launch {
            saveMutex.withLock {
                val snapshot = AfuUiParser.parseSheet(sheetTexts)
                if (snapshot == null) {
                    publishLog(stamp, sheetTexts, null, "未识别到时序正确的弹窗内容")
                    return@withLock
                }

                val key = snapshot.sheetKey.ifBlank { "sheet@${snapshot.timeStr}" }
                if (key != sessionKey) startSession(key)

                // ① 记录体重锚点（只认“最新一次称重”那块记录）
                if (snapshot.anchored && snapshot.weightKg != null) {
                    val weight = snapshot.weightKg
                    val previousWeight = sessionWeight
                    if (previousWeight != null && abs(previousWeight - weight) > 0.05) {
                        // 弹窗表头没变却读到另一个体重 → 用户展开了别的记录，重建会话
                        startSession(key)
                    }
                    sessionWeight = weight
                    sessionEpochMs = snapshot.measuredAtEpochMs
                }

                // ② 合并指标
                if (snapshot.metrics.isNotEmpty()) {
                    if (snapshot.anchored) {
                        sessionMetrics.putAll(snapshot.metrics)
                    } else {
                        // 表头已滚出屏幕：用“体重 ↔ 率/量”的物理关系校验，避免混入历史记录
                        val candidate = LinkedHashMap(sessionMetrics).apply { putAll(snapshot.metrics) }
                        if (isPhysicallyConsistent(candidate, sessionWeight)) {
                            sessionMetrics.putAll(snapshot.metrics)
                        }
                    }
                }

                val weight = sessionWeight
                val epoch = sessionEpochMs
                if (weight == null || epoch == null) {
                    publishLog(stamp, sheetTexts, snapshot, "已识别弹窗，但目标记录的体重表头尚未进入视图")
                    return@withLock
                }

                val fat = sessionMetrics[AfuUiParser.Metric.BODY_FAT.key]
                val totalFields = sessionMetrics.size + 1
                if (fat == null || sessionMetrics.size < 5) {
                    publishLog(stamp, sheetTexts, snapshot, "等待更多指标（已捕获 $totalFields/${AfuUiParser.TOTAL_FIELD_COUNT}）")
                    return@withLock
                }

                val fingerprint = "$key|$weight|$fat"
                if (fingerprint == lastPublishedFingerprint && totalFields <= lastPublishedFields) {
                    publishLog(stamp, sheetTexts, snapshot, "数据无变化")
                    return@withLock
                }

                val profile = preferenceManager.userProfile.first() ?: UserProfile()
                val measurement = AfuUiParser.buildMeasurement(weight, epoch, sessionMetrics, profile)

                val allHistory = scaleRepository.getAllMeasurements()

                // 同一分钟内、体重一致 => 认为是同一条记录，用同一主键覆盖更新，避免滚动过程产生多条记录
                val existing = allHistory.firstOrNull {
                    abs(it.measuredAtEpochMs - epoch) < 120_000L && abs(it.weightKg - weight) < 0.05
                }
                if (existing != null && totalFields < countCapturedFields(existing)) {
                    publishLog(stamp, sheetTexts, snapshot, "库中已有更完整记录，跳过覆盖")
                    return@withLock
                }

                // 去重逻辑：与已有记录或上一次保存记录比较，如果所有项完全一致则不保存
                if (existing != null && isAllFieldsIdentical(measurement, existing)) {
                    publishLog(stamp, sheetTexts, snapshot, "所有项与当前已有记录完全一致，跳过重复保存")
                    return@withLock
                }
                val latest = allHistory.firstOrNull()
                if (latest != null && isAllFieldsIdentical(measurement, latest)) {
                    publishLog(stamp, sheetTexts, snapshot, "所有项与上一次数据完全一致，跳过重复保存")
                    return@withLock
                }

                val toSave = if (existing != null) measurement.copy(id = existing.id) else measurement
                val saved = scaleRepository.saveMeasurement(toSave)
                if (saved == null) {
                    publishLog(stamp, sheetTexts, snapshot, "数据无效（体重 < 10kg），已忽略")
                    return@withLock
                }

                lastPublishedFingerprint = fingerprint
                lastPublishedFields = totalFields
                _lastCapturedMeasurement.value = saved
                _lastScrapeLog.value = "已从详情面板截获: ${saved.weightKg}kg / 体脂 ${saved.bodyFatPct}% " +
                        "(共 $totalFields/${AfuUiParser.TOTAL_FIELD_COUNT} 项，待确认上传)"
                publishLog(stamp, sheetTexts, snapshot, null)

                if (!toastShown) {
                    toastShown = true
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "已截获详情: ${saved.weightKg}kg / 体脂 ${saved.bodyFatPct}%（$totalFields/${AfuUiParser.TOTAL_FIELD_COUNT} 项）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun startSession(key: String) {
        if (sessionKey != null && sessionKey != key) {
            AppLogger.i(TAG, "切换弹窗会话: $sessionKey -> $key")
        }
        sessionKey = key
        sessionMetrics.clear()
        sessionWeight = null
        sessionEpochMs = null
        lastPublishedFields = 0
        lastPublishedFingerprint = null
        toastShown = false
    }

    private fun handleNoSheet(stamp: String, pkg: String, snapshots: List<List<String>>) {
        val silentFor = System.currentTimeMillis() - lastSeenSheetAt
        if (sessionKey != null && silentFor > SHEET_TIMEOUT_MS) {
            AppLogger.i(TAG, "详情弹窗已关闭，会话结束（本次已捕获 ${sessionMetrics.size}/${AfuUiParser.Metric.entries.size} 项）")
            sessionKey = null
            sessionMetrics.clear()
            sessionWeight = null
            sessionEpochMs = null
            lastPublishedFields = 0
            lastPublishedFingerprint = null
            toastShown = false
        }
        if (sessionKey == null) {
            val preview = snapshots.flatten().take(10).joinToString(", ")
            _lastRawInspectionLog.value = "【更新时间】$stamp\n" +
                    "【监听状态】已接收到阿福页面事件（包名: $pkg）\n" +
                    "【当前页面】未检测到【身体指标记录】详情弹窗\n" +
                    "【当前节点数】${snapshots.sumOf { it.size }} 条\n" +
                    "【前台预览】$preview\n" +
                    "【操作提示】请在阿福点击【测量详情】展开身体指标弹窗"
        }
    }

    /**
     * 判断两条测量记录的所有身体指标是否完全一致（用于防止重复保存）。
     */
    private fun isAllFieldsIdentical(a: BodyMeasurement, b: BodyMeasurement): Boolean {
        return abs(a.weightKg - b.weightKg) < 0.01 &&
                abs(a.bodyFatPct - b.bodyFatPct) < 0.01 &&
                abs(a.muscleKg - b.muscleKg) < 0.01 &&
                abs(a.waterPct - b.waterPct) < 0.01 &&
                abs(a.proteinPct - b.proteinPct) < 0.01 &&
                abs(a.boneMassKg - b.boneMassKg) < 0.01 &&
                a.visceralFatRating == b.visceralFatRating &&
                abs(a.basalMetKcal - b.basalMetKcal) < 0.1 &&
                abs(a.fatMassKg - b.fatMassKg) < 0.01 &&
                abs(a.subcutaneousFatPct - b.subcutaneousFatPct) < 0.01 &&
                abs(a.subcutaneousFatKg - b.subcutaneousFatKg) < 0.01 &&
                abs(a.boneMassPct - b.boneMassPct) < 0.01 &&
                abs(a.musclePct - b.musclePct) < 0.01 &&
                abs(a.waterKg - b.waterKg) < 0.01 &&
                abs(a.proteinKg - b.proteinKg) < 0.01 &&
                abs(a.skeletalMusclePct - b.skeletalMusclePct) < 0.01 &&
                abs(a.skeletalMuscleKg - b.skeletalMuscleKg) < 0.01
    }

    /**
     * 指标“体重 ↔ 率/量”必须自洽：脂肪量 ≈ 体重 × 体脂率，水分量 ≈ 体重 × 水分率 ……
     * 用于在“表头滚出屏幕”时判断这批数值是否真的属于同一次称重。
     */
    private fun isPhysicallyConsistent(metrics: Map<String, Double>, weightKg: Double?): Boolean {
        if (weightKg == null || weightKg <= 0.0) return true
        val pairs = listOf(
            AfuUiParser.Metric.FAT_MASS.key to AfuUiParser.Metric.BODY_FAT.key,
            AfuUiParser.Metric.SUB_FAT_KG.key to AfuUiParser.Metric.SUB_FAT_PCT.key,
            AfuUiParser.Metric.BONE_KG.key to AfuUiParser.Metric.BONE_PCT.key,
            AfuUiParser.Metric.MUSCLE_KG.key to AfuUiParser.Metric.MUSCLE_PCT.key,
            AfuUiParser.Metric.WATER_KG.key to AfuUiParser.Metric.WATER_PCT.key,
            AfuUiParser.Metric.PROTEIN_KG.key to AfuUiParser.Metric.PROTEIN_PCT.key,
            AfuUiParser.Metric.SKELETAL_KG.key to AfuUiParser.Metric.SKELETAL_PCT.key
        )
        for ((kgKey, pctKey) in pairs) {
            val kg = metrics[kgKey] ?: continue
            val pct = metrics[pctKey] ?: continue
            val expected = weightKg * pct / 100.0
            val tolerance = maxOf(0.8, expected * 0.08)
            if (abs(kg - expected) > tolerance) return false
        }
        return true
    }

    /** 统计一条记录里已捕获的项数（含体重），用于避免“滚动不足的新会话”覆盖更完整的旧记录 */
    private fun countCapturedFields(m: BodyMeasurement): Int {
        var count = 1 // 体重
        val values = listOf(
            m.bodyFatPct, m.muscleKg, m.waterPct, m.proteinPct, m.boneMassKg,
            m.fatMassKg, m.subcutaneousFatPct, m.subcutaneousFatKg, m.boneMassPct,
            m.musclePct, m.waterKg, m.proteinKg, m.skeletalMusclePct, m.skeletalMuscleKg
        )
        for (v in values) if (v > 0.0) count++
        if (m.visceralFatRating > 1) count++
        if (m.basalMetKcal > 0.0) count++
        return count
    }

    // ────────────────────────── 排查日志 ──────────────────────────

    private fun publishLog(
        stamp: String,
        sheetTexts: List<String>,
        snapshot: AfuUiParser.SheetSnapshot?,
        note: String?
    ) {
        val builder = StringBuilder()
        builder.append("【更新时间】$stamp\n")
        builder.append("【解析架构】按窗口隔离 + 记录分块（锁定最新一次称重）\n")
        if (snapshot != null) {
            builder.append("【弹窗锚点】${snapshot.sheetKey.ifBlank { "（未匹配到“共N条记录”）" }}\n")
            builder.append("【最新记录时间】${snapshot.sheetTimeStr ?: "未知"}\n")
            builder.append("【目标记录体重】${snapshot.weightKg?.let { "${it}kg" } ?: "表头未进入视图（沿用会话锚点）"}\n")
        }
        builder.append(
            "【会话累计】${sessionMetrics.size}/${AfuUiParser.Metric.entries.size} 项成分" +
                    "（含体重共 ${sessionMetrics.size + 1}/${AfuUiParser.TOTAL_FIELD_COUNT} 项）\n"
        )
        builder.append("【体重锚点】${sessionWeight?.let { "${it}kg" } ?: "未锚定"}\n")
        if (note != null) builder.append("【状态】$note\n")

        if (sessionMetrics.isNotEmpty()) {
            val detail = AfuUiParser.Metric.entries
                .filter { sessionMetrics.containsKey(it.key) }
                .joinToString(", ") { "${it.labels[0]}=${sessionMetrics[it.key]}" }
            builder.append("【指标明细】$detail\n")
        }
        val fat = sessionMetrics[AfuUiParser.Metric.BODY_FAT.key]
        if (sessionWeight != null && fat != null) {
            builder.append(
                "【核心数据】体重=${sessionWeight}kg, 体脂=${fat}%, " +
                        "肌肉=${sessionMetrics[AfuUiParser.Metric.MUSCLE_KG.key] ?: 0.0}kg\n"
            )
        }
        builder.append("【弹窗窗口全部原始文本（按视图顺序）】:\n")
        sheetTexts.forEachIndexed { index, text -> builder.append("  [$index] $text\n") }
        _lastRawInspectionLog.value = builder.toString()

        val anchorFlag = if (snapshot?.anchored == true) "已锚定目标记录" else "未锚定（滚动中）"
        AppLogger.d(
            TAG,
            "弹窗快照解析：累计 ${sessionMetrics.size}/${AfuUiParser.Metric.entries.size} 项，$anchorFlag" +
                    (note?.let { " - $it" } ?: "")
        )
    }

    // ────────────────────────── 窗口抓取 ──────────────────────────

    private fun collectWindowTexts(): List<List<String>> {
        val snapshots = ArrayList<List<String>>()
        try {
            val currentWindows = windows
            if (!currentWindows.isNullOrEmpty()) {
                for (window in currentWindows) {
                    try {
                        val root = window.root ?: continue
                        val texts = ArrayList<String>()
                        traverseNodes(root, texts)
                        if (texts.isNotEmpty()) snapshots.add(texts)
                    } catch (_: Throwable) {
                        // 忽略跨进程窗口异常
                    }
                }
            }
        } catch (_: Throwable) {
            // 忽略 windows 获取失败
        }
        if (snapshots.isEmpty()) {
            try {
                val root = rootInActiveWindow
                if (root != null) {
                    val texts = ArrayList<String>()
                    traverseNodes(root, texts)
                    if (texts.isNotEmpty()) snapshots.add(texts)
                }
            } catch (_: Throwable) {
                // 忽略根窗口异常
            }
        }
        return snapshots
    }

    /** 只挑出“身体指标记录”弹窗那个窗口（优先认弹窗独有表头，其次降级判断） */
    private fun selectSheetSnapshot(snapshots: List<List<String>>): List<String>? {
        for (texts in snapshots) {
            val index = texts.indexOfFirst { AfuUiParser.isSheetHeader(it) }
            if (index >= 0) return texts.subList(index, texts.size).toList()
        }
        for (texts in snapshots) {
            if (AfuUiParser.looksLikeSheet(texts)) {
                val index = texts.indexOfFirst { it.contains("体重") }
                return if (index > 0) texts.subList(index, texts.size).toList() else texts
            }
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
