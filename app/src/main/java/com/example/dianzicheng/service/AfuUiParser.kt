package com.example.dianzicheng.service

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.UserProfile
import java.util.UUID
import java.util.regex.Pattern
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 阿福 App (爱健康) 界面文本解析器。
 * 从无障碍服务抓取到的屏幕文本节点列表中提取完整的身体成分指标。
 */
object AfuUiParser {

    private val NUMBER_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)")

    data class ParseResult(
        val measurement: BodyMeasurement,
        val matchedFieldsCount: Int,
        val rawMatches: Map<String, Double>
    )

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

            // 2. 体脂率 (3.0% ~ 65.0%)
            if (matches["fat"] == null && (text.contains("体脂率") || text.contains("脂肪率"))) {
                findNearbyNumber(cleanTexts, i, 3.0..65.0)?.let { matches["fat"] = it }
            }

            // 3. 肌肉量 / 骨骼肌 (10.0 ~ 120.0 kg)
            if (matches["muscle"] == null && (text.contains("肌肉量") || text.contains("骨骼肌") || text.contains("肌肉重"))) {
                findNearbyNumber(cleanTexts, i, 10.0..120.0)?.let { matches["muscle"] = it }
            }

            // 4. 水分率 (30.0% ~ 80.0%)
            if (matches["water"] == null && text.contains("水分")) {
                findNearbyNumber(cleanTexts, i, 30.0..80.0)?.let { matches["water"] = it }
            }

            // 5. 骨量 (0.5 ~ 8.0 kg)
            if (matches["bone"] == null && text.contains("骨量")) {
                findNearbyNumber(cleanTexts, i, 0.5..8.0)?.let { matches["bone"] = it }
            }

            // 6. 蛋白质率 (5.0% ~ 35.0%)
            if (matches["protein"] == null && text.contains("蛋白质")) {
                findNearbyNumber(cleanTexts, i, 5.0..35.0)?.let { matches["protein"] = it }
            }

            // 7. 内脏脂肪等级 (1 ~ 30)
            if (matches["visceral"] == null && text.contains("内脏脂肪")) {
                findNearbyNumber(cleanTexts, i, 1.0..30.0)?.let { matches["visceral"] = it }
            }

            // 8. 基础代谢率 (500 ~ 4000 kcal)
            if (matches["bmr"] == null && (text.contains("基础代谢") || text.contains("基代"))) {
                findNearbyNumber(cleanTexts, i, 500.0..4000.0)?.let { matches["bmr"] = it }
            }

            // 9. BMI (10.0 ~ 60.0)
            if (matches["bmi"] == null && (text.equals("BMI", ignoreCase = true) || text.contains("体质指数"))) {
                findNearbyNumber(cleanTexts, i, 10.0..60.0)?.let { matches["bmi"] = it }
            }

            // 10. 身体年龄 / 代谢年龄 (10 ~ 100)
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

        // 若部分指标未在当前可视区域展示，以生理医学经典比例兜底
        val muscleKg = matches["muscle"] ?: (weightKg * (1.0 - bodyFatPct / 100.0) * 0.75)
        val waterPct = matches["water"] ?: ((1.0 - bodyFatPct / 100.0) * 0.73 * 100.0)
        val boneMassKg = matches["bone"] ?: (weightKg * 0.045)
        val proteinPct = matches["protein"] ?: (17.0)
        val visceralFat = matches["visceral"]?.toInt() ?: 4
        val bmr = matches["bmr"] ?: (10.0 * weightKg + 6.25 * userProfile.heightCm - 5.0 * 25 + 5.0)
        val metabolicAge = matches["age"]?.toInt() ?: 25

        val measurement = BodyMeasurement(
            id = UUID.randomUUID().toString(),
            measuredAtEpochMs = System.currentTimeMillis(),
            weightKg = (weightKg * 100.0).roundToInt() / 100.0,
            impedanceOhm = 0.0, // 官方 App 结果无需电阻抗
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
            syncedToGarminAtEpochMs = null
        )

        return ParseResult(
            measurement = measurement,
            matchedFieldsCount = matches.size,
            rawMatches = matches
        )
    }

    private fun isWeightLabel(text: String): Boolean {
        return text == "体重" || text.startsWith("体重 ") || text.startsWith("体重:") || text.startsWith("体重：")
    }

    /**
     * 在关键字周围（当前节点、后 3 个节点、前 1 个节点）查找符合数值区间的数字
     */
    private fun findNearbyNumber(texts: List<String>, index: Int, range: ClosedFloatingPointRange<Double>): Double? {
        // 1. 先检查当前文本内是否直接包含数字（例如 "体重 68.5kg"）
        extractNumber(texts[index], range)?.let { return it }

        // 2. 检查后续相邻的 1 ~ 3 个节点（WebView 中通常是 Label 下一行或下一个元素为 Value）
        for (offset in 1..3) {
            val nextIndex = index + offset
            if (nextIndex < texts.size) {
                extractNumber(texts[nextIndex], range)?.let { return it }
            }
        }

        // 3. 检查前一个节点（极少数排版可能 Value 在前 Label 在后）
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
