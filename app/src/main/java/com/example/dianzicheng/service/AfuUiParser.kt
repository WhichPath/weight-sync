package com.example.dianzicheng.service

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 阿福 App (爱健康) 界面文本解析器。
 * 支持从用户点击【测量详情】弹出的“身体指标记录”弹窗中精准定位各指标，
 * 并结合主卡片中的当次测量体重与体脂率。
 */
object AfuUiParser {

    data class ParseResult(
        val measurement: BodyMeasurement,
        val matchedFieldsCount: Int,
        val rawMatches: Map<String, Double>,
        val dateStr: String,
        val timeStr: String,
        val fingerprint: String
    )

    /**
     * 从文本列表中提取测量日期和时间。
     * 优先提取“更新于HH:mm”或“共X条记录，更新于HH:mm”，次选独立时间。
     */
    fun extractDateTime(texts: List<String>): Pair<String, String> {
        var dateStr: String? = null
        var timeStr: String? = null

        val dateRegex = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")
        val updateTimeRegex = Regex("""更新于\s*([0-2]?\d:[0-5]\d)""")
        val standaloneTimeRegex = Regex("""^([0-2]?\d:[0-5]\d)$""")

        for (text in texts) {
            if (dateStr == null) {
                val dMatch = dateRegex.find(text)
                if (dMatch != null) {
                    val y = dMatch.groupValues[1]
                    val m = dMatch.groupValues[2].padStart(2, '0')
                    val d = dMatch.groupValues[3].padStart(2, '0')
                    dateStr = "$y-$m-$d"
                }
            }
            if (timeStr == null) {
                val utMatch = updateTimeRegex.find(text)
                if (utMatch != null) {
                    timeStr = utMatch.groupValues[1].padStart(5, '0')
                }
            }
        }

        // 若没找到“更新于”，再查找独立的 HH:mm（排除基础信息中的更新日期）
        if (timeStr == null) {
            for (text in texts) {
                val stMatch = standaloneTimeRegex.find(text.trim())
                if (stMatch != null) {
                    timeStr = stMatch.groupValues[1].padStart(5, '0')
                    // 只要找到一个合法的当前时间段
                }
            }
        }

        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val nowTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        return Pair(dateStr ?: todayDate, timeStr ?: nowTime)
    }

    fun parseEpochMs(dateStr: String, timeStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse("$dateStr $timeStr")?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * 核心解析逻辑：
     * 阿福界面结构特征：
     * 1. 当用户展开“身体指标记录”弹窗时，包含文本“身体指标记录”或“共X条记录，更新于”。
     * 2. 弹窗内的细项指标为连续列表：
     *    - 12.0 / 偏高 (内脏脂肪)
     *    - 20.0kg (脂肪量)
     *    - 17.8% (皮下脂肪率)
     *    - 14.3kg (皮下脂肪量)
     *    - 骨量占比 / 3.9% / 偏低
     *    - 骨量 / 3.1kg
     *    - 71.2% / 优 (肌肉率)
     *    - 57.1kg (肌肉量)
     *    - 体水分率 / 50.1% / 标准
     *    - 体水分量 / 40.2kg
     *    - 蛋白量占比 / 20.3%
     *    - 蛋白量含量 / 16.3kg
     *    - 36.1% (骨骼肌率)
     *    - 29.0kg (骨骼肌量)
     *    - 基础代谢 / 1671.0kcal
     * 3. 体重与体脂率在卡片头部，例如 "较上次下降... / 08:03" 旁边的 "78.60" 与 "24.2%"。
     */
    fun parseScreenTexts(texts: List<String>, userProfile: UserProfile): ParseResult? {
        val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTexts.isEmpty()) return null

        // 严格模式：只针对详情面板。文本中必须包含详情弹窗的特征
        val hasDetailHeader = cleanTexts.any { it.contains("身体指标记录") }
        val hasDetailMetrics = cleanTexts.any { it.contains("体水分率") } && cleanTexts.any { it.contains("基础代谢") }
        if (!hasDetailHeader && !hasDetailMetrics) return null

        val matches = mutableMapOf<String, Double>()

        // ── 1. 优先解析详情弹窗中的各项细分指标 ──

        // 基础代谢 (kcal)
        for (i in cleanTexts.indices) {
            val t = cleanTexts[i]
            if (t.contains("基础代谢")) {
                // 可能是 "基础代谢 1671.0kcal" 或 下一行 "1671.0kcal" / "1671.0"
                findFirstNumber(cleanTexts, i, 500.0..4000.0)?.let { matches["bmr"] = it }
            }
        }

        // 体水分率 (%)
        for (i in cleanTexts.indices) {
            if (cleanTexts[i].contains("体水分率") || (cleanTexts[i].contains("水分率") && !cleanTexts[i].contains("量"))) {
                findFirstNumber(cleanTexts, i, 20.0..85.0)?.let { matches["water"] = it }
            }
        }

        // 体水分量 (kg)
        for (i in cleanTexts.indices) {
            if (cleanTexts[i].contains("体水分量") || (cleanTexts[i].contains("水分量") && !cleanTexts[i].contains("率"))) {
                findFirstNumber(cleanTexts, i, 10.0..100.0)?.let { matches["waterKg"] = it }
            }
        }

        // 蛋白量占比 (%)
        for (i in cleanTexts.indices) {
            if (cleanTexts[i].contains("蛋白") && cleanTexts[i].contains("占比")) {
                findFirstNumber(cleanTexts, i, 5.0..40.0)?.let { matches["protein"] = it }
            }
        }

        // 蛋白量含量 / 蛋白质量 (kg)
        for (i in cleanTexts.indices) {
            if (cleanTexts[i].contains("蛋白") && (cleanTexts[i].contains("含量") || (cleanTexts[i].contains("量") && !cleanTexts[i].contains("占比")))) {
                findFirstNumber(cleanTexts, i, 2.0..30.0)?.let { matches["proteinKg"] = it }
            }
        }

        // 骨量占比 (%)
        for (i in cleanTexts.indices) {
            if (cleanTexts[i].contains("骨量占比")) {
                findFirstNumber(cleanTexts, i, 0.5..15.0)?.let { matches["bonePct"] = it }
            }
        }

        // 骨量 (kg)
        for (i in cleanTexts.indices) {
            val t = cleanTexts[i]
            if ((t == "骨量" || t.startsWith("骨量 ")) && !t.contains("占比") && !t.contains("率")) {
                findFirstNumber(cleanTexts, i, 0.5..10.0)?.let { matches["bone"] = it }
            }
        }

        // ── 弹窗列表中的位置敏感型指标（部分阿福版本在弹窗内无文字标签，只有纯数值列表）──
        // 弹窗开始位置：从 "身体指标记录" 或 "共X条记录，更新于" 之后开始
        val dialogStartIndex = cleanTexts.indexOfFirst { it.contains("身体指标记录") || it.contains("共") && it.contains("记录") }
        if (dialogStartIndex != -1) {
            val dialogItems = cleanTexts.subList(dialogStartIndex, cleanTexts.size)

            // 内脏脂肪等级：通常在 "更新于..." 之后的第一个纯数字（如 12.0）
            if (matches["visceral"] == null) {
                for (j in 1 until minOf(dialogItems.size, 5)) {
                    val num = extractPureNumber(dialogItems[j])
                    if (num != null && num in 1.0..30.0 && !dialogItems[j].contains("kg") && !dialogItems[j].contains("%")) {
                        matches["visceral"] = num
                        break
                    }
                }
            }

            // 脂肪量：内脏脂肪后面的第一个 kg 值（如 20.0kg）
            if (matches["fatMass"] == null) {
                for (j in 1 until minOf(dialogItems.size, 8)) {
                    val item = dialogItems[j]
                    if (item.endsWith("kg", ignoreCase = true)) {
                        extractPureNumber(item)?.let { if (it in 5.0..80.0) matches["fatMass"] = it }
                        if (matches["fatMass"] != null) break
                    }
                }
            }

            // 皮下脂肪率：第一个 % 值（如 17.8%）
            if (matches["subFatPct"] == null) {
                for (j in 1 until minOf(dialogItems.size, 8)) {
                    val item = dialogItems[j]
                    if (item.contains("%") && !item.contains("骨量")) {
                        extractPureNumber(item)?.let { if (it in 3.0..50.0) matches["subFatPct"] = it }
                        if (matches["subFatPct"] != null) break
                    }
                }
            }

            // 皮下脂肪量：皮下脂肪率后面的 kg 值（如 14.3kg）
            if (matches["subFatKg"] == null) {
                val subPctIdx = dialogItems.indexOfFirst { it.contains("%") && extractPureNumber(it) == matches["subFatPct"] }
                if (subPctIdx != -1 && subPctIdx + 1 < dialogItems.size) {
                    val nextItem = dialogItems[subPctIdx + 1]
                    if (nextItem.endsWith("kg", ignoreCase = true)) {
                        extractPureNumber(nextItem)?.let { if (it in 3.0..50.0) matches["subFatKg"] = it }
                    }
                }
            }

            // 肌肉率与肌肉量：在 "骨量" 之后出现的第一个 % (71.2%) 与 kg (57.1kg)
            val boneIdx = dialogItems.indexOfFirst { it == "骨量" || it.startsWith("骨量") }
            if (boneIdx != -1) {
                for (k in boneIdx + 1 until minOf(dialogItems.size, boneIdx + 8)) {
                    val item = dialogItems[k]
                    if (matches["musclePct"] == null && item.contains("%") && !item.contains("水分")) {
                        extractPureNumber(item)?.let { if (it in 30.0..95.0) matches["musclePct"] = it }
                    }
                    if (matches["muscle"] == null && item.endsWith("kg", ignoreCase = true) && !item.contains("骨量") && !item.contains("水分")) {
                        extractPureNumber(item)?.let { if (it in 20.0..120.0) matches["muscle"] = it }
                    }
                }
            }

            // 骨骼肌率与骨骼肌量：在 "蛋白量含量" 与 "基础代谢" 之间出现的 % (36.1%) 与 kg (29.0kg)
            val proteinKgIdx = dialogItems.indexOfFirst { it.contains("蛋白量含量") || it.contains("蛋白质量") }
            val bmrIdx = dialogItems.indexOfFirst { it.contains("基础代谢") }
            if (proteinKgIdx != -1) {
                val endLimit = if (bmrIdx != -1) bmrIdx else dialogItems.size
                for (k in proteinKgIdx + 1 until endLimit) {
                    val item = dialogItems[k]
                    if (matches["skelMusclePct"] == null && item.contains("%")) {
                        extractPureNumber(item)?.let { if (it in 15.0..60.0) matches["skelMusclePct"] = it }
                    }
                    if (matches["skelMuscleKg"] == null && item.endsWith("kg", ignoreCase = true)) {
                        extractPureNumber(item)?.let { if (it in 10.0..60.0) matches["skelMuscleKg"] = it }
                    }
                }
            }
        }

        // ── 2. 提取体重与体脂率 ──
        // 策略A：单行带有标签（如 "体重 78.60" 或 "体脂率 24.2%"）
        for (text in cleanTexts) {
            if (matches["weight"] == null && (text.startsWith("体重 ") || text.startsWith("体重:"))) {
                Regex("""[0-9]+(?:\.[0-9]+)?""").find(text)?.value?.toDoubleOrNull()?.let {
                    if (it in 30.0..250.0) matches["weight"] = it
                }
            }
            if (matches["fat"] == null && text.contains("体脂率") && !text.contains("皮下") && !text.contains("偏高") && !text.contains("偏低")) {
                Regex("""[0-9]+(?:\.[0-9]+)?""").find(text)?.value?.toDoubleOrNull()?.let {
                    if (it in 3.0..65.0) matches["fat"] = it
                }
            }
            if (matches["bmi"] == null && (text.startsWith("BMI ") || text.startsWith("BMI:"))) {
                Regex("""[0-9]+(?:\.[0-9]+)?""").find(text)?.value?.toDoubleOrNull()?.let {
                    if (it in 10.0..60.0) matches["bmi"] = it
                }
            }
        }

        // 策略B：从“较上次上升/下降”或测量卡片附近提取（如 [78.60, 较上次下降0.45kg, 08:03, 26.0, BMI·偏高, 24.2%, 体脂率·偏高]）
        for (i in cleanTexts.indices) {
            val text = cleanTexts[i]

            // 命中体脂率：例如 text 是 "24.2%" 或 "24.9%" 且紧邻 "体脂率·偏高" 或 "体脂率"
            if (matches["fat"] == null && text.contains("%")) {
                val nextIsFatLabel = (i + 1 < cleanTexts.size && cleanTexts[i + 1].contains("体脂率") && !cleanTexts[i + 1].contains("皮下"))
                val prevIsFatLabel = (i - 1 >= 0 && cleanTexts[i - 1].contains("体脂率") && !cleanTexts[i - 1].contains("皮下"))
                if (nextIsFatLabel || prevIsFatLabel) {
                    extractPureNumber(text)?.let { if (it in 3.0..65.0) matches["fat"] = it }
                }
            }

            // 命中 BMI：例如 text 是 "26.0" 紧邻 "BMI·偏高"
            if (matches["bmi"] == null) {
                val nextIsBmi = (i + 1 < cleanTexts.size && cleanTexts[i + 1].contains("BMI"))
                val prevIsBmi = (i - 1 >= 0 && cleanTexts[i - 1].contains("BMI"))
                if (nextIsBmi || prevIsBmi) {
                    extractPureNumber(text)?.let { if (it in 10.0..50.0) matches["bmi"] = it }
                }
            }

            // 命中体重：紧邻“较上次上升/下降”前后的独立纯数字（如 [78.60, 较上次下降0.45kg]）
            if (matches["weight"] == null && text.contains("较上次")) {
                // 检查前一个节点
                if (i - 1 >= 0) {
                    extractPureNumber(cleanTexts[i - 1])?.let { if (it in 30.0..250.0) matches["weight"] = it }
                }
                // 若前一个不是，检查前两个
                if (matches["weight"] == null && i - 2 >= 0) {
                    extractPureNumber(cleanTexts[i - 2])?.let { if (it in 30.0..250.0) matches["weight"] = it }
                }
            }
        }

        // 策略C：若体重仍未匹配到，但卡片中有身材管理页的当前称重值（位于日期与“较上次”之间的纯数值，如 78.60）
        if (matches["weight"] == null) {
            for (i in 0 until minOf(cleanTexts.size - 1, 20)) {
                val item = cleanTexts[i]
                val num = extractPureNumber(item)
                if (num != null && num in 35.0..200.0 && !item.contains("kg") && !item.contains("%") && !item.contains(":")) {
                    // 查看后一个是否是“较上次”或“BMI”
                    val nextItem = cleanTexts[i + 1]
                    if (nextItem.contains("较上次") || nextItem.contains("kg") || nextItem.contains("BMI")) {
                        matches["weight"] = num
                        break
                    }
                }
            }
        }

        // 策略D：体脂率若仍未找到，在列表前部查找独立的 "XX.X%" 且不在弹窗细项中的
        if (matches["fat"] == null) {
            val endSearch = if (dialogStartIndex != -1) dialogStartIndex else cleanTexts.size
            for (i in 0 until endSearch) {
                val item = cleanTexts[i]
                if (item.endsWith("%")) {
                    extractPureNumber(item)?.let {
                        if (it in 5.0..50.0) {
                            matches["fat"] = it
                        }
                    }
                    if (matches["fat"] != null) break
                }
            }
        }

        val weightKg = matches["weight"] ?: return null
        val bodyFatPct = matches["fat"] ?: return null

        val bmi = matches["bmi"] ?: run {
            val hM = userProfile.heightCm / 100.0
            if (hM > 0) ((weightKg / (hM * hM)) * 10.0).roundToInt() / 10.0 else 22.0
        }

        val muscleKg = matches["muscle"] ?: (weightKg * (1.0 - bodyFatPct / 100.0) * 0.75)
        val waterPct = matches["water"] ?: ((1.0 - bodyFatPct / 100.0) * 0.73 * 100.0)
        val boneMassKg = matches["bone"] ?: (weightKg * 0.045)
        val proteinPct = matches["protein"] ?: 17.0
        val visceralFat = matches["visceral"]?.toInt() ?: 4
        val bmr = matches["bmr"] ?: (10.0 * weightKg + 6.25 * userProfile.heightCm - 5.0 * 25 + 5.0)

        val (dateStr, timeStr) = extractDateTime(cleanTexts)
        val measuredAtEpoch = parseEpochMs(dateStr, timeStr)

        val measurement = BodyMeasurement(
            id = UUID.randomUUID().toString(),
            measuredAtEpochMs = measuredAtEpoch,
            weightKg = (weightKg * 100.0).roundToInt() / 100.0,
            impedanceOhm = 0.0,
            bmi = (bmi * 10.0).roundToInt() / 10.0,
            bodyFatPct = (bodyFatPct * 10.0).roundToInt() / 10.0,
            waterPct = (waterPct * 10.0).roundToInt() / 10.0,
            muscleKg = (muscleKg * 10.0).roundToInt() / 10.0,
            proteinPct = (proteinPct * 10.0).roundToInt() / 10.0,
            boneMassKg = (boneMassKg * 10.0).roundToInt() / 10.0,
            basalMetKcal = bmr,
            visceralFatRating = visceralFat,
            metabolicAge = 25,
            fatMassKg = matches["fatMass"] ?: 0.0,
            subcutaneousFatPct = matches["subFatPct"] ?: 0.0,
            subcutaneousFatKg = matches["subFatKg"] ?: 0.0,
            boneMassPct = matches["bonePct"] ?: 0.0,
            musclePct = matches["musclePct"] ?: 0.0,
            waterKg = matches["waterKg"] ?: 0.0,
            proteinKg = matches["proteinKg"] ?: 0.0,
            skeletalMusclePct = matches["skelMusclePct"] ?: 0.0,
            skeletalMuscleKg = matches["skelMuscleKg"] ?: 0.0
        )

        val fingerprint = "${dateStr}_${timeStr}_${measurement.weightKg}_${measurement.bodyFatPct}_${measurement.muscleKg}"

        return ParseResult(
            measurement = measurement,
            matchedFieldsCount = matches.size,
            rawMatches = matches,
            dateStr = dateStr,
            timeStr = timeStr,
            fingerprint = fingerprint
        )
    }

    private fun findFirstNumber(texts: List<String>, labelIndex: Int, validRange: ClosedFloatingPointRange<Double>): Double? {
        // 先在当前行正则查找
        val curText = texts[labelIndex]
        Regex("""[0-9]+(?:\.[0-9]+)?""").find(curText)?.value?.toDoubleOrNull()?.let {
            if (it in validRange) return it
        }

        // 检查后 1 到 3 个节点
        for (offset in 1..3) {
            if (labelIndex + offset < texts.size) {
                val nextText = texts[labelIndex + offset]
                val num = extractPureNumber(nextText)
                if (num != null && num in validRange) {
                    return num
                }
            }
        }
        return null
    }

    private fun extractPureNumber(text: String): Double? {
        val clean = text.trim()
            .replace("kg", "", true)
            .replace("%", "")
            .replace("kcal", "", true)
            .replace("·偏高", "")
            .replace("·偏低", "")
            .replace("·标准", "")
            .trim()
        return clean.toDoubleOrNull()
    }
}
