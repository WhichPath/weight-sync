package com.example.dianzicheng.service

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.UserProfile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 阿福 App（蚂蚁阿福）【身体指标记录】弹窗文本解析器。
 *
 * ── v2.0.8 重构说明（对应 2.0.7 实机失败根因）──────────────────────────────
 * 2.0.7 在真机上把 80.05kg 的新测量解析成了 80.2kg / 22.0%（0.15kg 之外的旧数据 + 把 22:32
 * 当成 22.0% 体脂）。根因有三：
 *  1. 抓取层用 `LinkedHashSet` 累加文本，重复字符串被吞掉：弹窗里的“体重 / 体脂率 / 脂肪量 /
 *     内脏脂肪等级 / 皮下脂肪率 / 皮下脂肪量 / 肌肉率 / 肌肉量 / 骨骼肌率 / 骨骼肌量”以及
 *     24.5%、80.05 这些字符串在主页面已经出现过一次，于是从弹窗里被“去重”删除了；
 *  2. 弹窗判定过宽（主页也有“内脏脂肪等级”“体脂率”字样），主页卡片节点混进了同一个池子；
 *  3. 解析器在弹窗内找不到体重/体脂率时，会回溯到弹窗之外，围着主页卡片的“22:32”取数，
 *     于是把时间当成了体脂率。
 *
 * 因此本解析器遵循三条铁律：
 *  A. 只吃「弹窗自身窗口」的顺序快照（由 [AfuAccessibilityService] 负责按窗口隔离），
 *     完全不去看主页卡片；
 *  B. 弹窗内固定为“一次称重 = 17 项（含体重）”，通过【共 N 条记录，更新于 HH:mm】表头
 *     锁定「最新一次称重」，并按“体重”表头切分记录块，绝不会串到历史记录；
 *  C. 数值提取前先把 HH:mm 时间串整段抹掉，时间绝不会再被当成体脂率/数值。
 *
 * 解析顺序：先“标签 → 数值”精确配对；标签缺失时才按界面固定顺序做槽位兜底。
 */
object AfuUiParser {

    // ────────────────────────── 常量与指标定义 ──────────────────────────

    /** 弹窗独有表头：共 N 条记录，更新于 HH:mm */
    private val SHEET_HEADER_REGEX = Regex("""共\s*\d+\s*条记录""")
    private val UPDATE_TIME_REGEX = Regex("""更新于\s*([0-2]?\d:[0-5]\d)""")
    private val DATE_IN_TEXT_REGEX = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
    private val STANDALONE_TIME_REGEX = Regex("""^([0-2]?\d:[0-5]\d)$""")
    private val TIME_IN_TEXT_REGEX = Regex("""[0-2]?\d:[0-5]\d""")
    private val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")

    /** 结论标签（数值后紧跟的状态），不参与取数 */
    private val STATUS_TAGS = listOf(
        "偏高", "偏低", "标准", "正常", "合格", "合理", "健康", "超标", "不足", "优", "高", "低"
    )

    /** 弹窗内与指标无关的按钮/状态文本 */
    private val NOISE_TEXTS = listOf(
        "确认", "取消", "关闭", "数据加载中", "身体指标记录", "测量详情",
        "编辑身高", "编辑年龄", "去记录", "项异常", "反馈与投诉"
    )

    /**
     * 阿福弹窗内每次称重固定展示的 16 项身体成分指标（不含“体重”），顺序与界面一致，
     * 该顺序同时用于“标签缺失时的槽位兜底”。
     */
    enum class Metric(
        val key: String,
        val labels: List<String>,
        val unit: String,
        val min: Double,
        val max: Double
    ) {
        VISCERAL("visceral", listOf("内脏脂肪等级", "内脏脂肪"), "", 1.0, 30.0),
        BODY_FAT("fat", listOf("体脂率"), "%", 2.0, 65.0),
        FAT_MASS("fatMass", listOf("脂肪量"), "kg", 2.0, 110.0),
        SUB_FAT_PCT("subFatPct", listOf("皮下脂肪率"), "%", 1.0, 60.0),
        SUB_FAT_KG("subFatKg", listOf("皮下脂肪量"), "kg", 1.0, 90.0),
        BONE_PCT("bonePct", listOf("骨量占比"), "%", 0.5, 20.0),
        BONE_KG("bone", listOf("骨量"), "kg", 0.5, 15.0),
        MUSCLE_PCT("musclePct", listOf("肌肉率"), "%", 10.0, 95.0),
        MUSCLE_KG("muscle", listOf("肌肉量"), "kg", 3.0, 150.0),
        WATER_PCT("water", listOf("体水分率", "水分率"), "%", 10.0, 85.0),
        WATER_KG("waterKg", listOf("体水分量", "水分量"), "kg", 3.0, 120.0),
        PROTEIN_PCT("protein", listOf("蛋白量占比", "蛋白质占比", "蛋白占比", "蛋白质率"), "%", 3.0, 40.0),
        PROTEIN_KG("proteinKg", listOf("蛋白量含量", "蛋白质含量", "蛋白质量"), "kg", 1.0, 40.0),
        SKELETAL_PCT("skelMusclePct", listOf("骨骼肌率"), "%", 5.0, 70.0),
        SKELETAL_KG("skelMuscleKg", listOf("骨骼肌量"), "kg", 3.0, 80.0),
        BMR("bmr", listOf("基础代谢"), "kcal", 300.0, 6000.0)
    }

    /** 一次称重的完整项目数：16 项成分 + 体重 = 17 项 */
    const val TOTAL_FIELD_COUNT = 17

    // ────────────────────────── 对外数据模型 ──────────────────────────

    /**
     * 一次弹窗快照中可归属于「最新一次称重」的数据。
     *
     * @param anchored 是否已用“体重”表头精确锚定目标记录（未锚定时只能靠标签配对 + 物理校验）
     */
    data class SheetSnapshot(
        val sheetKey: String,
        val sheetTimeStr: String?,
        val weightKg: Double?,
        val metrics: Map<String, Double>,
        val anchored: Boolean,
        val dateStr: String,
        val timeStr: String,
        val measuredAtEpochMs: Long
    )

    // ────────────────────────── 对外入口 ──────────────────────────

    /** 该文本是否是弹窗独有的表头（“共 N 条记录，更新于 HH:mm”） */
    fun isSheetHeader(text: String): Boolean = SHEET_HEADER_REGEX.containsMatchIn(text)

    /** 兜底判断：这组节点是否像“身体指标记录”弹窗（用于弹窗表头被遮挡时的降级识别） */
    fun looksLikeSheet(texts: List<String>): Boolean {
        if (texts.count { matchMetric(it) != null } < 3) return false
        return texts.any { isSheetHeader(it) } || texts.any { it.trim() == "确认" }
    }

    /**
     * 解析「弹窗自身窗口」的顺序文本快照。
     *
     * @param rawTexts 弹窗窗口内的节点文本（保持视图顺序，允许重复）
     * @return null 表示这组文本根本不是弹窗内容
     */
    fun parseSheet(rawTexts: List<String>): SheetSnapshot? {
        val texts = rawTexts.asSequence()
            .map { it.replace('\u00A0', ' ').replace('\u3000', ' ').trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (texts.isEmpty()) return null

        // ⓞ 先证明这确实是弹窗内容：必须带弹窗独有表头，或带“确认”按钮 + 多个指标标签。
        //    否则直接拒绝（主页卡片也会出现“内脏脂肪等级 / 体脂率”等字样，绝不能误判）
        if (texts.none { isSheetHeader(it) } && !looksLikeSheet(texts)) return null

        // ① 弹窗锚点：只有弹窗才有“共 N 条记录”
        val anchorIdx = texts.indexOfFirst { isSheetHeader(it) }
        val content: List<String> = if (anchorIdx >= 0) texts.subList(anchorIdx, texts.size).toList() else texts
        val sheetKey = if (anchorIdx >= 0) texts[anchorIdx] else ""
        val sheetTime = UPDATE_TIME_REGEX.find(sheetKey)?.groupValues?.get(1)?.let { normalizeTime(it) }

        // ② 按“体重”表头切分记录块
        val headers = ArrayList<Int>()
        for (i in content.indices) {
            if (isWeightHeader(content, i)) headers.add(i)
        }
        val firstContentNode = content.indexOfFirst { !isNoiseNode(it) }

        var regionStart = 0
        var regionEnd = content.size
        var targetWeight: Double? = null
        var weightValueIndex = -1
        var anchored = false

        if (headers.isNotEmpty()) {
            val first = headers[0]
            val second = headers.getOrNull(1)
            val block0Time = headerTime(content, first, second ?: content.size)
            val block0AtTop = first == firstContentNode
            val block0IsSheetRecord = sheetTime != null && block0Time == sheetTime
            // 只有当“可见区域内最上面的记录块”就是弹窗表头指向的最新记录时，才认定为本次目标
            val newest = block0IsSheetRecord ||
                    (block0AtTop && (sheetTime == null || block0Time == null || block0Time == sheetTime))
            if (newest) {
                regionStart = first
                regionEnd = second ?: content.size
                headerWeightHit(content, first)?.let { hit ->
                    targetWeight = hit.second
                    weightValueIndex = hit.first
                }
                anchored = true
            } else {
                // 最新记录的表头已滚出屏幕：它上面的可见行仍属于最新记录
                regionStart = 0
                regionEnd = first
            }
        }

        // ③ 指标解析（表头里那个体重数值要排除掉，不能被当成某项成分）
        val metrics = if (regionEnd > regionStart) {
            parseRegion(content, regionStart, regionEnd, weightValueIndex, allowPositional = anchored)
        } else {
            emptyMap()
        }

        // ④ 日期时间：优先弹窗表头时间（即最新一次称重时间）
        val dateStr = texts.firstNotNullOfOrNull { DATE_IN_TEXT_REGEX.find(it) }?.let {
            val y = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            val d = it.groupValues[3].toInt()
            "%04d-%02d-%02d".format(y, m, d)
        } ?: todayStr()
        val timeStr = sheetTime
            ?: headerTime(content, headers.firstOrNull() ?: 0, content.size)
            ?: nowTimeStr()

        return SheetSnapshot(
            sheetKey = sheetKey,
            sheetTimeStr = sheetTime,
            weightKg = targetWeight,
            metrics = metrics,
            anchored = anchored,
            dateStr = dateStr,
            timeStr = timeStr,
            measuredAtEpochMs = computeEpochMs(dateStr, timeStr)
        )
    }

    /**
     * 把「体重 + 已合并的 16 项指标」构建为完整测量记录。
     */
    fun buildMeasurement(
        weightKg: Double,
        measuredAtEpochMs: Long,
        metrics: Map<String, Double>,
        profile: UserProfile
    ): BodyMeasurement {
        fun v(key: String): Double = metrics[key] ?: 0.0

        val heightCm = if (profile.heightCm > 0) profile.heightCm else 175.0
        val heightM = heightCm / 100.0
        val bmi = if (heightM > 0) roundTo1(weightKg / (heightM * heightM)) else 22.0
        val visceral = v(Metric.VISCERAL.key).toInt()
        val bmr = if (v(Metric.BMR.key) > 0) v(Metric.BMR.key) else estimateBmr(weightKg, heightCm, profile)

        return BodyMeasurement(
            id = UUID.randomUUID().toString(),
            measuredAtEpochMs = measuredAtEpochMs,
            weightKg = roundTo2(weightKg),
            impedanceOhm = 0.0,
            bmi = bmi,
            bodyFatPct = roundTo1(v(Metric.BODY_FAT.key)),
            muscleKg = roundTo1(v(Metric.MUSCLE_KG.key)),
            waterPct = roundTo1(v(Metric.WATER_PCT.key)),
            proteinPct = roundTo1(v(Metric.PROTEIN_PCT.key)),
            boneMassKg = roundTo1(v(Metric.BONE_KG.key)),
            visceralFatRating = if (visceral in 1..254) visceral else 1,
            basalMetKcal = bmr,
            metabolicAge = 25,
            fatMassKg = roundTo1(v(Metric.FAT_MASS.key)),
            subcutaneousFatPct = roundTo1(v(Metric.SUB_FAT_PCT.key)),
            subcutaneousFatKg = roundTo1(v(Metric.SUB_FAT_KG.key)),
            boneMassPct = roundTo1(v(Metric.BONE_PCT.key)),
            musclePct = roundTo1(v(Metric.MUSCLE_PCT.key)),
            waterKg = roundTo1(v(Metric.WATER_KG.key)),
            proteinKg = roundTo1(v(Metric.PROTEIN_KG.key)),
            skeletalMusclePct = roundTo1(v(Metric.SKELETAL_PCT.key)),
            skeletalMuscleKg = roundTo1(v(Metric.SKELETAL_KG.key))
        )
    }

    // ────────────────────────── 内部：标签 / 数值识别 ──────────────────────────

    /** 把节点文本匹配到具体指标（精确整词优先，其次取最长标签前缀，避免“骨量”吃掉“骨量占比”） */
    fun matchMetric(text: String): Metric? {
        val base = text.substringBefore('·').trim()
        if (base.isEmpty()) return null
        var best: Metric? = null
        var bestLen = 0
        for (metric in Metric.entries) {
            for (label in metric.labels) {
                if (base == label) return metric
                if (base.startsWith(label) && label.length > bestLen) {
                    best = metric
                    bestLen = label.length
                }
            }
        }
        return best
    }

    private fun isStatusTag(text: String): Boolean {
        val t = text.trim()
        return STATUS_TAGS.any { it == t }
    }

    private fun isNoiseNode(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (SHEET_HEADER_REGEX.containsMatchIn(t)) return true
        if (t.contains("条记录") || t.contains("更新于")) return true
        return NOISE_TEXTS.any { it == t }
    }

    private fun unitOf(text: String): String = when {
        text.contains("%") -> "%"
        text.contains("kcal", ignoreCase = true) -> "kcal"
        text.contains("kg", ignoreCase = true) -> "kg"
        else -> ""
    }

    /** 把时间串、标签、状态词、单位全部剔除后提取数值（保证 HH:mm 不会被当成数字） */
    private fun cleanForNumber(text: String): String {
        var s = text.replace('：', ':')
        s = DATE_IN_TEXT_REGEX.replace(s, " ")
        s = TIME_IN_TEXT_REGEX.replace(s, " ")
        for (tag in STATUS_TAGS) s = s.replace(tag, " ")
        for (metric in Metric.entries) {
            for (label in metric.labels) s = s.replace(label, " ")
        }
        s = s.replace("kg", " ", ignoreCase = true)
        s = s.replace("kcal", " ", ignoreCase = true)
        s = s.replace("%", " ")
        s = s.replace("级", " ").replace("岁", " ").replace("次", " ")
        return s
    }

    private fun numbersOf(text: String): List<Double> =
        NUMBER_REGEX.findAll(cleanForNumber(text)).mapNotNull { it.value.toDoubleOrNull() }.toList()

    // ────────────────────────── 内部：记录块定位 ──────────────────────────

    /** 是否是某条记录的“体重”表头行 */
    private fun isWeightHeader(texts: List<String>, index: Int): Boolean {
        val base = texts[index].substringBefore('·').trim()
        if (!base.startsWith("体重")) return false
        if (base.contains("单位") || base.contains("切换")) return false
        if (numbersOf(texts[index]).any { it in 20.0..300.0 }) return true
        var j = index + 1
        var steps = 0
        while (j < texts.size && steps < 3) {
            if (matchMetric(texts[j]) != null || isNoiseNode(texts[j])) return false
            if (numbersOf(texts[j]).any { it in 20.0..300.0 }) return true
            j++
            steps++
        }
        return false
    }

    /** 取出表头里的体重数值（返回：数值所在节点下标 → 体重） */
    private fun headerWeightHit(texts: List<String>, index: Int): Pair<Int, Double>? {
        numbersOf(texts[index]).firstOrNull { it in 20.0..300.0 }?.let { return index to it }
        var j = index + 1
        var steps = 0
        while (j < texts.size && steps < 3) {
            if (matchMetric(texts[j]) != null || isNoiseNode(texts[j])) break
            numbersOf(texts[j]).firstOrNull { it in 20.0..300.0 }?.let { return j to it }
            j++
            steps++
        }
        return null
    }

    /** 取出表头里的时间（HH:mm） */
    private fun headerTime(texts: List<String>, start: Int, endExclusive: Int): String? {
        var j = start
        var steps = 0
        while (j < endExclusive && j < texts.size && steps < 4) {
            val t = texts[j]
            if (matchMetric(t) != null) break
            STANDALONE_TIME_REGEX.find(t.trim())?.let { return normalizeTime(it.groupValues[1]) }
            val inText = TIME_IN_TEXT_REGEX.find(t)
            if (inText != null) return normalizeTime(inText.value)
            j++
            steps++
        }
        return null
    }

    // ────────────────────────── 内部：指标行解析 ──────────────────────────

    /**
     * 解析一个记录区间内的指标。
     *
     * @param excludeValueIndex 需要排除的孤立数值节点下标（目标记录表头里的体重数值），-1 表示无
     * @param allowPositional 是否允许“标签缺失时按固定顺序兜底”（仅在已锚定目标记录块时开启）
     */
    private fun parseRegion(
        texts: List<String>,
        start: Int,
        end: Int,
        excludeValueIndex: Int,
        allowPositional: Boolean
    ): Map<String, Double> {
        data class Orphan(val index: Int, val value: Double, val unit: String)

        val result = LinkedHashMap<String, Double>()
        val consumed = HashSet<Int>()
        val orphans = ArrayList<Orphan>()

        var i = start
        while (i < end) {
            val text = texts[i]
            if (isNoiseNode(text)) {
                i++
                continue
            }
            val metric = matchMetric(text)
            if (metric != null) {
                consumed.add(i)
                val hit = findValueForMetric(texts, i, metric, end)
                if (hit != null) {
                    consumed.add(hit.first)
                    if (!result.containsKey(metric.key)) result[metric.key] = hit.second
                }
                i++
                continue
            }
            if (isStatusTag(text)) {
                i++
                continue
            }
            val numbers = numbersOf(text)
            if (numbers.isNotEmpty() && i != excludeValueIndex) {
                orphans.add(Orphan(i, numbers[0], unitOf(text)))
            }
            i++
        }

        // 槽位兜底：严格按界面固定顺序为缺失指标补齐数值
        if (allowPositional && orphans.isNotEmpty()) {
            val unused = orphans.filter { it.index !in consumed }
            var cursor = 0
            for (metric in Metric.entries) {
                if (result.containsKey(metric.key)) continue
                var pick = -1
                for (k in cursor until unused.size) {
                    val orphan = unused[k]
                    if (orphan.value < metric.min || orphan.value > metric.max) continue
                    if (!unitCompatible(metric.unit, orphan.unit)) continue
                    pick = k
                    break
                }
                if (pick < 0) continue
                result[metric.key] = unused[pick].value
                cursor = pick + 1
            }
        }
        return result
    }

    /** 为某个指标标签找数值：同节点内优先，其次向后最多 3 个节点（遇到下一个指标标签立即停止） */
    private fun findValueForMetric(
        texts: List<String>,
        labelIndex: Int,
        metric: Metric,
        endExclusive: Int
    ): Pair<Int, Double>? {
        numbersOf(texts[labelIndex]).firstOrNull { it in metric.min..metric.max }?.let {
            return labelIndex to it
        }
        var j = labelIndex + 1
        var steps = 0
        while (j < endExclusive && j < texts.size && steps < 3) {
            val t = texts[j]
            if (matchMetric(t) != null || isNoiseNode(t)) break
            if (!isStatusTag(t)) {
                val value = numbersOf(t).firstOrNull { it in metric.min..metric.max }
                if (value != null) return j to value
            }
            j++
            steps++
        }
        return null
    }

    private fun unitCompatible(metricUnit: String, valueUnit: String): Boolean {
        if (metricUnit.isEmpty()) return valueUnit.isEmpty()
        if (valueUnit.isEmpty()) return true
        return metricUnit == valueUnit
    }

    // ────────────────────────── 内部：时间与数值 ──────────────────────────

    private fun normalizeTime(raw: String): String {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return raw.trim()
        val h = parts[0].toIntOrNull() ?: return raw.trim()
        val m = parts[1].toIntOrNull() ?: return raw.trim()
        return "%02d:%02d".format(h, m)
    }

    private fun computeEpochMs(dateStr: String, timeStr: String): Long {
        val parsed = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("$dateStr $timeStr")?.time
        } catch (e: Exception) {
            null
        }
        var ms = parsed ?: System.currentTimeMillis()
        // 若解析出的时间比现在晚了半小时以上，说明跨天（记录来自昨天），回退一天
        if (ms > System.currentTimeMillis() + 30 * 60 * 1000L) {
            ms -= 24 * 60 * 60 * 1000L
        }
        return ms
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun nowTimeStr(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun ageFrom(birthDateEpochMs: Long): Int {
        if (birthDateEpochMs <= 0L) return 25
        val today = Calendar.getInstance()
        val birth = Calendar.getInstance().apply { timeInMillis = birthDateEpochMs }
        var age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        if (today.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) age--
        return age.coerceIn(1, 120)
    }

    /** 阿福未给出基础代谢时的兜底（Mifflin-St Jeor） */
    private fun estimateBmr(weightKg: Double, heightCm: Double, profile: UserProfile): Double {
        val age = ageFrom(profile.birthDateEpochMs)
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
        val bmr = if (profile.sex == Sex.MALE) base + 5.0 else base - 161.0
        return roundTo1(bmr)
    }

    private fun roundTo1(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private fun roundTo2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
