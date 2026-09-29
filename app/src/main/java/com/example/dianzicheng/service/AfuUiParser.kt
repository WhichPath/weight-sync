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
 * 阿福 App (爱健康) “身体指标记录”详情面板文本解析器。
 * 专门解析用户主动点击【测量详情】后弹出的指标详情面板，精准提取全部 17 项指标。
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
     * 支持例如："2026年9月28日" 与 "共1条记录，更新于08:14" 或 "08:14"。
     */
    fun extractDateTime(texts: List<String>): Pair<String, String> {
        var dateStr: String? = null
        var timeStr: String? = null

        val dateRegex = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")
        val timeRegex = Regex("""(?:更新于)?\s*([0-2]?\d:[0-5]\d)""")

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
     * 精确解析阿福“身体指标记录”详情面板文本。
     */
    fun parseScreenTexts(texts: List<String>, userProfile: UserProfile): ParseResult? {
        val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTexts.isEmpty()) return null

        // 严格模式：只针对详情面板。如果文本中完全没有“身体指标记录”和“内脏脂肪”，说明不在详情面板内，不予解析
        val isInsideDetailPanel = cleanTexts.any { it.contains("身体指标记录") } ||
                (cleanTexts.any { it.contains("内脏脂肪") } && cleanTexts.any { it.contains("体脂率") })
        if (!isInsideDetailPanel) return null

        val matches = mutableMapOf<String, Double>()

        // 辅助提取器：优先在单行文本内匹配正则，若单行内只有标签，则检查相邻下一行是否为纯数值
        fun matchField(
            targetKey: String,
            singleLineRegex: Regex,
            exactLabelPred: (String) -> Boolean,
            validRange: ClosedFloatingPointRange<Double>
        ) {
            if (matches[targetKey] != null) return

            for (i in cleanTexts.indices) {
                val text = cleanTexts[i]

                // 排除干扰文本（例如带有“项异常”、“较上次”、“身材管理”的节点）
                if (text.contains("项异常") || text.contains("较上次") || text.contains("身材管理")) continue

                // 1. 单行内直接匹配出数值
                val m = singleLineRegex.find(text)
                if (m != null) {
                    val num = m.groupValues[1].toDoubleOrNull()
                    if (num != null && num in validRange) {
                        matches[targetKey] = num
                        return
                    }
                }

                // 2. 当前行是标签，下一行为对应数值
                if (exactLabelPred(text)) {
                    if (i + 1 < cleanTexts.size) {
                        val nextText = cleanTexts[i + 1]
                        val nextNum = extractPureNumber(nextText)
                        if (nextNum != null && nextNum in validRange) {
                            matches[targetKey] = nextNum
                            return
                        }
                    }
                }
            }
        }

        // 1. 体重 (kg)：例如 "体重 79.05 kg" 或 "体重 79.05"
        // 排除“骨骼肌量”、“脂肪量”、“骨量”等
        matchField(
            targetKey = "weight",
            singleLineRegex = Regex("""(?:^|\s)体重(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it == "体重" || it.startsWith("体重 ") || it.startsWith("体重:") },
            validRange = 25.0..250.0
        )

        // 2. 体脂率 (%)：例如 "体脂率 24.3%"
        // 排除“皮下脂肪率”
        matchField(
            targetKey = "fat",
            singleLineRegex = Regex("""(?<!皮下)体脂率(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { (it == "体脂率" || it.startsWith("体脂率 ")) && !it.contains("皮下") },
            validRange = 3.0..65.0
        )

        // 3. 内脏脂肪等级：例如 "内脏脂肪等级 11.0" 或 "内脏脂肪 11"
        matchField(
            targetKey = "visceral",
            singleLineRegex = Regex("""内脏脂肪(?:等级)?(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)"""),
            exactLabelPred = { it.contains("内脏脂肪") },
            validRange = 1.0..30.0
        )

        // 4. 脂肪量 (kg)：例如 "脂肪量 19.2kg"
        matchField(
            targetKey = "fatMass",
            singleLineRegex = Regex("""(?<!皮下)脂肪量(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it.contains("脂肪量") && !it.contains("皮下") },
            validRange = 1.0..100.0
        )

        // 5. 皮下脂肪率 (%)：例如 "皮下脂肪率 17.4%"
        matchField(
            targetKey = "subFatPct",
            singleLineRegex = Regex("""皮下脂肪率(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { it.contains("皮下脂肪率") },
            validRange = 1.0..60.0
        )

        // 6. 皮下脂肪量 (kg)：例如 "皮下脂肪量 13.8kg"
        matchField(
            targetKey = "subFatKg",
            singleLineRegex = Regex("""皮下脂肪量(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it.contains("皮下脂肪量") },
            validRange = 1.0..60.0
        )

        // 7. 骨量占比 (%)：例如 "骨量占比 3.9%"
        matchField(
            targetKey = "bonePct",
            singleLineRegex = Regex("""骨量占比(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { it.contains("骨量占比") },
            validRange = 0.5..15.0
        )

        // 8. 骨量 (kg)：例如 "骨量 3.1kg"
        matchField(
            targetKey = "bone",
            singleLineRegex = Regex("""(?<!占比)骨量(?!\s*占比)(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it == "骨量" || (it.contains("骨量") && !it.contains("占比") && !it.contains("率")) },
            validRange = 0.5..10.0
        )

        // 9. 肌肉率 (%)：例如 "肌肉率 71.8%"
        matchField(
            targetKey = "musclePct",
            singleLineRegex = Regex("""(?<!骨骼)肌肉率(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { it.contains("肌肉率") && !it.contains("骨骼") },
            validRange = 10.0..95.0
        )

        // 10. 肌肉量 (kg)：例如 "肌肉量 56.8kg"
        matchField(
            targetKey = "muscle",
            singleLineRegex = Regex("""(?<!骨骼)肌肉量(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it.contains("肌肉量") && !it.contains("骨骼") },
            validRange = 10.0..120.0
        )

        // 11. 体水分率 (%)：例如 "体水分率 51.5%"
        matchField(
            targetKey = "water",
            singleLineRegex = Regex("""体?水分率(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { it.contains("水分率") },
            validRange = 20.0..85.0
        )

        // 12. 体水分量 (kg)：例如 "体水分量 40.7kg"
        matchField(
            targetKey = "waterKg",
            singleLineRegex = Regex("""体?水分量(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it.contains("水分量") },
            validRange = 10.0..100.0
        )

        // 13. 蛋白量占比 (%)：例如 "蛋白量占比 19.5%"
        matchField(
            targetKey = "protein",
            singleLineRegex = Regex("""蛋白(?:量)?占比(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { it.contains("蛋白") && it.contains("占比") },
            validRange = 5.0..40.0
        )

        // 14. 蛋白量含量 (kg)：例如 "蛋白量含量 15.4kg"
        matchField(
            targetKey = "proteinKg",
            singleLineRegex = Regex("""蛋白(?:量)?(?:含量|量)(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it.contains("蛋白") && (it.contains("含量") || (it.contains("量") && !it.contains("占比"))) },
            validRange = 2.0..30.0
        )

        // 15. 骨骼肌率 (%)：例如 "骨骼肌率 37.3%"
        matchField(
            targetKey = "skelMusclePct",
            singleLineRegex = Regex("""骨骼肌率(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*%?"""),
            exactLabelPred = { it.contains("骨骼肌率") },
            validRange = 10.0..70.0
        )

        // 16. 骨骼肌量 (kg)：例如 "骨骼肌量 29.5kg"（之前误认成体重的罪魁祸首！）
        matchField(
            targetKey = "skelMuscleKg",
            singleLineRegex = Regex("""骨骼肌量(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kg)?"""),
            exactLabelPred = { it.contains("骨骼肌量") },
            validRange = 5.0..80.0
        )

        // 17. 基础代谢 (kcal)：例如 "基础代谢 1663.0kcal"
        matchField(
            targetKey = "bmr",
            singleLineRegex = Regex("""基础代谢(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:kcal)?"""),
            exactLabelPred = { it.contains("基础代谢") },
            validRange = 500.0..4000.0
        )

        // 18. BMI
        matchField(
            targetKey = "bmi",
            singleLineRegex = Regex("""BMI(?:\s*[:：])?\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
            exactLabelPred = { it.equals("BMI", ignoreCase = true) },
            validRange = 10.0..60.0
        )

        // 核心必须有体重与体脂率才视为有效数据
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

    private fun extractPureNumber(text: String): Double? {
        val clean = text.trim().replace("kg", "", true).replace("%", "").replace("kcal", "", true).trim()
        return clean.toDoubleOrNull()
    }
}
