package com.example.dianzicheng.service

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * 阿福 App (爱健康) 界面文本解析器。
 * 从无障碍服务抓取到的屏幕文本节点列表中提取完整的 17 项身体成分指标，以及日期时间戳。
 */
object AfuUiParser {

    private val NUMBER_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)")

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
     * 例如："2026年9月28日" 与 "共1条记录，更新于08:14" 或 "08:14"。
     */
    fun extractDateTime(texts: List<String>): Pair<String, String> {
        var dateStr: String? = null
        var timeStr: String? = null

        val dateRegex = Regex("(\\d{4})年(\\d{1,2})月(\\d{1,2})日")
        val timeRegex = Regex("(?:更新于)?\\s*([0-2]?\\d:[0-5]\\d)")

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
                val tMatch = timeRegex.find(text)
                if (tMatch != null) {
                    timeStr = tMatch.groupValues[1].padStart(5, '0')
                }
            }
        }

        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val nowTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        return Pair(dateStr ?: todayDate, timeStr ?: nowTime)
    }

    /**
     * 将日期与时间字符串转为绝对毫秒时间戳。
     */
    fun parseEpochMs(dateStr: String, timeStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse("$dateStr $timeStr")?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * 解析文本列表。
     * @param texts 屏幕节点在阅读顺序下提取出的文本列表
     * @param userProfile 当前用户配置（用于辅助计算缺失的 BMI 等）
     */
    fun parseScreenTexts(texts: List<String>, userProfile: UserProfile): ParseResult? {
        val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTexts.isEmpty()) return null

        val matches = mutableMapOf<String, Double>()

        for (i in cleanTexts.indices) {
            val text = cleanTexts[i]

            // 1. 体重 (10.0 ~ 250.0 kg)
            if (matches["weight"] == null && isWeightLabel(text)) {
                findNearbyNumber(cleanTexts, i, 10.0..250.0)?.let { matches["weight"] = it }
            }

            // 2. 内脏脂肪等级 (1.0 ~ 30.0)
            if (matches["visceral"] == null && text.contains("内脏脂肪")) {
                findNearbyNumber(cleanTexts, i, 1.0..30.0)?.let { matches["visceral"] = it }
            }

            // 3. 体脂率 (3.0% ~ 65.0%)，排除“皮下脂肪率”
            if (matches["fat"] == null && text.contains("体脂率") && !text.contains("皮下")) {
                findNearbyNumber(cleanTexts, i, 3.0..65.0)?.let { matches["fat"] = it }
            }

            // 4. 脂肪量 (1.0 ~ 100.0 kg)，排除“皮下脂肪量”
            if (matches["fatMass"] == null && text.contains("脂肪量") && !text.contains("皮下")) {
                findNearbyNumber(cleanTexts, i, 1.0..100.0)?.let { matches["fatMass"] = it }
            }

            // 5. 皮下脂肪率 (1.0% ~ 60.0%)
            if (matches["subFatPct"] == null && text.contains("皮下脂肪率")) {
                findNearbyNumber(cleanTexts, i, 1.0..60.0)?.let { matches["subFatPct"] = it }
            }

            // 6. 皮下脂肪量 (1.0 ~ 60.0 kg)
            if (matches["subFatKg"] == null && text.contains("皮下脂肪量")) {
                findNearbyNumber(cleanTexts, i, 1.0..60.0)?.let { matches["subFatKg"] = it }
            }

            // 7. 骨量占比 (0.5% ~ 15.0%)
            if (matches["bonePct"] == null && text.contains("骨量占比")) {
                findNearbyNumber(cleanTexts, i, 0.5..15.0)?.let { matches["bonePct"] = it }
            }

            // 8. 骨量 (0.5 ~ 10.0 kg)，排除“占比”和“率”
            if (matches["bone"] == null && text.contains("骨量") && !text.contains("占比") && !text.contains("率")) {
                findNearbyNumber(cleanTexts, i, 0.5..10.0)?.let { matches["bone"] = it }
            }

            // 9. 肌肉率 (10.0% ~ 95.0%)，排除“骨骼肌”
            if (matches["musclePct"] == null && text.contains("肌肉率") && !text.contains("骨骼")) {
                findNearbyNumber(cleanTexts, i, 10.0..95.0)?.let { matches["musclePct"] = it }
            }

            // 10. 肌肉量 (10.0 ~ 120.0 kg)，排除“骨骼肌量”
            if (matches["muscle"] == null && text.contains("肌肉量") && !text.contains("骨骼")) {
                findNearbyNumber(cleanTexts, i, 10.0..120.0)?.let { matches["muscle"] = it }
            }

            // 11. 体水分率 (20.0% ~ 85.0%)
            if (matches["water"] == null && (text.contains("体水分率") || (text.contains("水分率") && !text.contains("量")))) {
                findNearbyNumber(cleanTexts, i, 20.0..85.0)?.let { matches["water"] = it }
            }

            // 12. 体水分量 (10.0 ~ 100.0 kg)
            if (matches["waterKg"] == null && (text.contains("体水分量") || (text.contains("水分量") && !text.contains("率")))) {
                findNearbyNumber(cleanTexts, i, 10.0..100.0)?.let { matches["waterKg"] = it }
            }

            // 13. 蛋白量占比 / 蛋白质率 (5.0% ~ 40.0%)
            if (matches["protein"] == null && (text.contains("蛋白量占比") || text.contains("蛋白质率"))) {
                findNearbyNumber(cleanTexts, i, 5.0..40.0)?.let { matches["protein"] = it }
            }

            // 14. 蛋白量含量 / 蛋白质量 (2.0 ~ 30.0 kg)
            if (matches["proteinKg"] == null && (text.contains("蛋白量含量") || text.contains("蛋白质量"))) {
                findNearbyNumber(cleanTexts, i, 2.0..30.0)?.let { matches["proteinKg"] = it }
            }

            // 15. 骨骼肌率 (10.0% ~ 70.0%)
            if (matches["skelMusclePct"] == null && text.contains("骨骼肌率")) {
                findNearbyNumber(cleanTexts, i, 10.0..70.0)?.let { matches["skelMusclePct"] = it }
            }

            // 16. 骨骼肌量 (5.0 ~ 80.0 kg)
            if (matches["skelMuscleKg"] == null && text.contains("骨骼肌量")) {
                findNearbyNumber(cleanTexts, i, 5.0..80.0)?.let { matches["skelMuscleKg"] = it }
            }

            // 17. 基础代谢率 (500 ~ 4000 kcal)
            if (matches["bmr"] == null && text.contains("基础代谢")) {
                findNearbyNumber(cleanTexts, i, 500.0..4000.0)?.let { matches["bmr"] = it }
            }

            // 18. BMI (10.0 ~ 60.0)
            if (matches["bmi"] == null && (text.equals("BMI", ignoreCase = true) || text.contains("体质指数") || text.startsWith("BMI "))) {
                findNearbyNumber(cleanTexts, i, 10.0..60.0)?.let { matches["bmi"] = it }
            }

            // 19. 身体年龄 / 代谢年龄 (10 ~ 100)
            if (matches["age"] == null && (text.contains("身体年龄") || text.contains("代谢年龄"))) {
                findNearbyNumber(cleanTexts, i, 10.0..100.0)?.let { matches["age"] = it }
            }
        }

        val weightKg = matches["weight"] ?: return null
        val bodyFatPct = matches["fat"] ?: return null

        val bmi = matches["bmi"] ?: run {
            val hM = userProfile.heightCm / 100.0
            if (hM > 0) weightKg / (hM * hM) else 22.0
        }

        // 若部分指标由于未滑动到最底部暂时缺失，以医学经典比例兜底
        val muscleKg = matches["muscle"] ?: (weightKg * (1.0 - bodyFatPct / 100.0) * 0.75)
        val waterPct = matches["water"] ?: ((1.0 - bodyFatPct / 100.0) * 0.73 * 100.0)
        val boneMassKg = matches["bone"] ?: (weightKg * 0.045)
        val proteinPct = matches["protein"] ?: 17.0
        val visceralFat = matches["visceral"]?.toInt() ?: 4
        val bmr = matches["bmr"] ?: (10.0 * weightKg + 6.25 * userProfile.heightCm - 5.0 * 25 + 5.0)
        val metabolicAge = matches["age"]?.toInt() ?: 25

        val (dateStr, timeStr) = extractDateTime(cleanTexts)
        val measuredAtEpoch = parseEpochMs(dateStr, timeStr)

        val measurement = BodyMeasurement(
            id = UUID.randomUUID().toString(),
            measuredAtEpochMs = measuredAtEpoch,
            weightKg = (weightKg * 100.0).roundToInt() / 100.0,
            impedanceOhm = 0.0,
            bmi = (bmi * 10.0).roundToInt() / 10.0,
            bodyFatPct = (bodyFatPct * 10.0).roundToInt() / 10.0,
            muscleKg = (muscleKg * 100.0).roundToInt() / 100.0,
            waterPct = (waterPct * 10.0).roundToInt() / 10.0,
            proteinPct = (proteinPct * 10.0).roundToInt() / 10.0,
            boneMassKg = (boneMassKg * 100.0).roundToInt() / 100.0,
            visceralFatRating = visceralFat,
            basalMetKcal = bmr.roundToInt().toDouble(),
            metabolicAge = metabolicAge,
            syncedToGarmin = false,
            syncedToGarminAtEpochMs = null,
            // 完整镜像指标
            fatMassKg = matches["fatMass"] ?: ((weightKg * bodyFatPct / 100.0 * 10.0).roundToInt() / 10.0),
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

    private fun isWeightLabel(text: String): Boolean {
        if (text.contains("较上次") || text.contains("目标") || text.contains("下降") || text.contains("上升")) return false
        return text.contains("体重") || text == "重" || text.startsWith("体重 ") || text.startsWith("体重:") || text.startsWith("体重：")
    }

    /**
     * 在关键字周围（当前节点、后 3 个节点、前 1 个节点）查找符合数值区间的数字
     */
    private fun findNearbyNumber(texts: List<String>, index: Int, range: ClosedFloatingPointRange<Double>): Double? {
        // 1. 先检查当前文本内是否直接包含数字（例如 "内脏脂肪等级 11.0" 或 "体重 79.05 kg"）
        extractNumber(texts[index], range)?.let { return it }

        // 2. 检查后续相邻的 1 ~ 3 个节点
        for (offset in 1..3) {
            val nextIndex = index + offset
            if (nextIndex < texts.size) {
                extractNumber(texts[nextIndex], range)?.let { return it }
            }
        }

        // 3. 检查前一个节点
        if (index - 1 >= 0) {
            extractNumber(texts[index - 1], range)?.let { return it }
        }

        return null
    }

    private fun extractNumber(text: String, range: ClosedFloatingPointRange<Double>): Double? {
        val matcher = NUMBER_PATTERN.matcher(text)
        while (matcher.find()) {
            val value = matcher.group(1)?.toDoubleOrNull()
            if (value != null && value in range) {
                return value
            }
        }
        return null
    }
}
