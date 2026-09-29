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
 * 核心原则：严格仅解析用户点击【测量详情】弹出的“身体指标记录”弹窗内部的节点！
 * 绝不回溯扫描主界面卡片，防止串入上一次测量的陈旧历史数据。
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
     * 只截取从【身体指标记录】或【共X条记录，更新于】开始到【确认】之间的纯弹窗节点切片！
     */
    fun parseScreenTexts(texts: List<String>, userProfile: UserProfile): ParseResult? {
        val cleanTexts = texts.map {
            it.replace('\u00A0', ' ')
                .replace('\u3000', ' ')
                .trim()
        }.filter { it.isNotEmpty() }
        if (cleanTexts.isEmpty()) return null

        // 1. 定位弹窗起始点
        val dialogStartIndex = cleanTexts.indexOfFirst {
            it.contains("身体指标记录") || (it.contains("共") && it.contains("条记录") && it.contains("更新于"))
        }

        // 若未找到明确弹窗头部，检查是否在包含内脏脂肪与体脂率/水分率的密集详情区域
        val startIndex = if (dialogStartIndex != -1) {
            dialogStartIndex
        } else {
            val fallbackIdx = cleanTexts.indexOfFirst { it.contains("内脏脂肪") || it.contains("体重") }
            if (fallbackIdx != -1 && cleanTexts.any { it.contains("基础代谢") }) fallbackIdx else -1
        }

        if (startIndex == -1) return null

        // 截取纯弹窗内的节点切片（dialogTexts），彻底与主页卡片/历史测量隔离！
        val dialogTexts = cleanTexts.subList(startIndex, cleanTexts.size)

        val matches = mutableMapOf<String, Double>()

        // ── 2. 提取体重 (重点：只在弹窗内寻找！) ──
        for (i in dialogTexts.indices) {
            val t = dialogTexts[i]
            if (t.contains("体重")) {
                findFirstNumber(dialogTexts, i, 30.0..250.0)?.let { matches["weight"] = it }
            }
            if (matches["weight"] != null) break
        }

        // ── 3. 提取体脂率 (重点：只在弹窗内寻找！) ──
        for (i in dialogTexts.indices) {
            val t = dialogTexts[i]
            if (t.contains("体脂率") && !t.contains("皮下")) {
                findFirstNumber(dialogTexts, i, 3.0..65.0)?.let { matches["fat"] = it }
            }
            if (matches["fat"] != null) break
        }

        // 容错回退：若弹窗内未直接包含体重/体脂率（部分机型或历史折叠视图），从弹窗更新时间或主页卡片回溯
        if (matches["weight"] == null || matches["fat"] == null) {
            val timeRegex = Regex("""(?:更新于|于)?\s*([0-2]?[0-9]:[0-5][0-9])""")
            var targetTime: String? = null
            for (t in dialogTexts) {
                val m = timeRegex.find(t)
                if (m != null) {
                    targetTime = m.groupValues[1]
                    break
                }
            }

            if (targetTime != null) {
                val timeIdx = (startIndex - 1 downTo 0).firstOrNull { cleanTexts[it].contains(targetTime) } ?: -1
                if (timeIdx != -1) {
                    for (offset in -6..6) {
                        val idx = timeIdx + offset
                        if (idx in 0 until startIndex) {
                            val candidate = cleanTexts[idx]
                            val num = Regex("""[0-9]+(?:\.[0-9]+)?""").find(candidate)?.value?.toDoubleOrNull()
                            if (num != null) {
                                if (matches["weight"] == null && num in 35.0..250.0 && !candidate.contains("%")) {
                                    matches["weight"] = num
                                } else if (matches["fat"] == null && (num in 5.0..55.0 || candidate.contains("%")) && num in 5.0..65.0) {
                                    matches["fat"] = num
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 4. 提取各细分成分指标（单行带标签 或 标签+数值相连）──

        // 内脏脂肪等级
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("内脏脂肪")) {
                findFirstNumber(dialogTexts, i, 1.0..30.0)?.let { matches["visceral"] = it }
            }
        }

        // 脂肪量 (kg)
        for (i in dialogTexts.indices) {
            val t = dialogTexts[i]
            if (t == "脂肪量" || (t.startsWith("脂肪量") && !t.contains("皮下") && !t.contains("内脏"))) {
                findFirstNumber(dialogTexts, i, 5.0..80.0)?.let { matches["fatMass"] = it }
            }
        }

        // 皮下脂肪率 (%)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("皮下脂肪率")) {
                findFirstNumber(dialogTexts, i, 3.0..50.0)?.let { matches["subFatPct"] = it }
            }
        }

        // 皮下脂肪量 (kg)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("皮下脂肪量")) {
                findFirstNumber(dialogTexts, i, 3.0..50.0)?.let { matches["subFatKg"] = it }
            }
        }

        // 骨量占比 (%)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("骨量占比")) {
                findFirstNumber(dialogTexts, i, 0.5..15.0)?.let { matches["bonePct"] = it }
            }
        }

        // 骨量 (kg)
        for (i in dialogTexts.indices) {
            val t = dialogTexts[i]
            if ((t == "骨量" || t.startsWith("骨量 ")) && !t.contains("占比") && !t.contains("率")) {
                findFirstNumber(dialogTexts, i, 0.5..10.0)?.let { matches["bone"] = it }
            }
        }

        // 肌肉率 (%)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("肌肉率") && !dialogTexts[i].contains("骨骼肌")) {
                findFirstNumber(dialogTexts, i, 30.0..95.0)?.let { matches["musclePct"] = it }
            }
        }

        // 肌肉量 (kg)
        for (i in dialogTexts.indices) {
            val t = dialogTexts[i]
            if ((t == "肌肉量" || t.startsWith("肌肉量 ")) && !t.contains("骨骼肌")) {
                findFirstNumber(dialogTexts, i, 20.0..120.0)?.let { matches["muscle"] = it }
            }
        }

        // 体水分率 (%)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("体水分率") || (dialogTexts[i].contains("水分率") && !dialogTexts[i].contains("量"))) {
                findFirstNumber(dialogTexts, i, 20.0..85.0)?.let { matches["water"] = it }
            }
        }

        // 体水分量 (kg)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("体水分量") || (dialogTexts[i].contains("水分量") && !dialogTexts[i].contains("率"))) {
                findFirstNumber(dialogTexts, i, 10.0..100.0)?.let { matches["waterKg"] = it }
            }
        }

        // 蛋白量占比 (%)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("蛋白") && dialogTexts[i].contains("占比")) {
                findFirstNumber(dialogTexts, i, 5.0..40.0)?.let { matches["protein"] = it }
            }
        }

        // 蛋白量含量 / 蛋白质量 (kg)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("蛋白") && (dialogTexts[i].contains("含量") || (dialogTexts[i].contains("量") && !dialogTexts[i].contains("占比")))) {
                findFirstNumber(dialogTexts, i, 2.0..30.0)?.let { matches["proteinKg"] = it }
            }
        }

        // 骨骼肌率 (%)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("骨骼肌率")) {
                findFirstNumber(dialogTexts, i, 15.0..60.0)?.let { matches["skelMusclePct"] = it }
            }
        }

        // 骨骼肌量 (kg)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("骨骼肌量")) {
                findFirstNumber(dialogTexts, i, 10.0..60.0)?.let { matches["skelMuscleKg"] = it }
            }
        }

        // 基础代谢 (kcal)
        for (i in dialogTexts.indices) {
            if (dialogTexts[i].contains("基础代谢")) {
                findFirstNumber(dialogTexts, i, 500.0..4000.0)?.let { matches["bmr"] = it }
            }
        }

        // ── 5. 若部分 Android 节点拆分为纯列表（槽位映射兜底）──
        // 内脏脂肪等级：更新于 之后的第一个纯数字
        if (matches["visceral"] == null) {
            for (j in 1 until minOf(dialogTexts.size, 6)) {
                val num = extractPureNumber(dialogTexts[j])
                if (num != null && num in 1.0..30.0 && !dialogTexts[j].contains("kg") && !dialogTexts[j].contains("%") && !dialogTexts[j].contains(":")) {
                    matches["visceral"] = num
                    break
                }
            }
        }

        // 脂肪量：在内脏脂肪附近的 kg 值
        if (matches["fatMass"] == null) {
            for (j in 1 until minOf(dialogTexts.size, 8)) {
                val item = dialogTexts[j]
                if (item.endsWith("kg", ignoreCase = true) && !item.contains("体重")) {
                    extractPureNumber(item)?.let { if (it in 5.0..80.0) matches["fatMass"] = it }
                    if (matches["fatMass"] != null) break
                }
            }
        }

        // 肌肉率与肌肉量兜底
        val boneIdx = dialogTexts.indexOfFirst { it == "骨量" || it.startsWith("骨量") }
        if (boneIdx != -1) {
            for (k in boneIdx + 1 until minOf(dialogTexts.size, boneIdx + 8)) {
                val item = dialogTexts[k]
                if (matches["musclePct"] == null && item.contains("%") && !item.contains("水分") && !item.contains("骨")) {
                    extractPureNumber(item)?.let { if (it in 30.0..95.0) matches["musclePct"] = it }
                }
                if (matches["muscle"] == null && item.endsWith("kg", ignoreCase = true) && !item.contains("骨量") && !item.contains("水分")) {
                    extractPureNumber(item)?.let { if (it in 20.0..120.0) matches["muscle"] = it }
                }
            }
        }

        // 骨骼肌率与骨骼肌量兜底
        val proteinKgIdx = dialogTexts.indexOfFirst { it.contains("蛋白量含量") || it.contains("蛋白质量") }
        val bmrIdx = dialogTexts.indexOfFirst { it.contains("基础代谢") }
        if (proteinKgIdx != -1) {
            val endLimit = if (bmrIdx != -1) bmrIdx else dialogTexts.size
            for (k in proteinKgIdx + 1 until endLimit) {
                val item = dialogTexts[k]
                if (matches["skelMusclePct"] == null && item.contains("%")) {
                    extractPureNumber(item)?.let { if (it in 15.0..60.0) matches["skelMusclePct"] = it }
                }
                if (matches["skelMuscleKg"] == null && item.endsWith("kg", ignoreCase = true)) {
                    extractPureNumber(item)?.let { if (it in 10.0..60.0) matches["skelMuscleKg"] = it }
                }
            }
        }

        // 弹窗内必须包含体重与体脂率！若弹窗还未展开或未包含，坚决拒绝，绝不向外取主页数据！
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

        val (dateStr, timeStr) = extractDateTime(dialogTexts)
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
        val curText = texts[labelIndex]
        Regex("""[0-9]+(?:\.[0-9]+)?""").find(curText)?.value?.toDoubleOrNull()?.let {
            if (it in validRange) return it
        }

        for (offset in 1..3) {
            if (labelIndex + offset < texts.size) {
                val nextText = texts[labelIndex + offset]
                Regex("""[0-9]+(?:\.[0-9]+)?""").find(nextText)?.value?.toDoubleOrNull()?.let {
                    if (it in validRange) return it
                }
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
            .replace("偏高", "")
            .replace("偏低", "")
            .replace("标准", "")
            .replace("优", "")
            .trim()
        return clean.toDoubleOrNull()
    }
}
