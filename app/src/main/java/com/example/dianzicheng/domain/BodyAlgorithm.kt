package com.example.dianzicheng.domain

import java.util.Calendar
import kotlin.math.max

/**
 * 身体成分全面计算算法（BIA 生物电阻抗 + 医学标准公式）。
 *
 * 计算生成 Garmin Connect 支持的完整身体指标：
 * 体重、BMI、体脂率、水分率、骨量、肌肉量、蛋白质、内脏脂肪等级、基础代谢率、代谢身体年龄。
 */
object BodyAlgorithm {

    /**
     * 根据称重数值与用户个人身体资料计算完整的指标。
     */
    fun calculate(
        weightKg: Double,
        impedanceOhm: Double,
        sex: Sex,
        heightCm: Double,
        birthDateEpochMs: Long,
        timestampMs: Long = System.currentTimeMillis()
    ): BodyMeasurement {
        val safeHeightCm = heightCm.coerceIn(50.0, 250.0)
        val heightM = safeHeightCm / 100.0
        val bmi = (weightKg / (heightM * heightM)).coerceIn(5.0, 100.0)
        val age = calculateAge(birthDateEpochMs)

        val hasImpedance = impedanceOhm > 0.0

        // 1. 体脂率 (%)
        val fat = if (hasImpedance) {
            val biaFat = if (sex == Sex.MALE) {
                0.18 * bmi + 0.012 * age + 0.018 * impedanceOhm - 3.2
            } else {
                0.26 * bmi + 0.011 * age + 0.020 * impedanceOhm - 2.5
            }
            biaFat.coerceIn(5.0, 55.0)
        } else {
            // 无阻抗时（如穿袜），使用 Deurenberg 经典公式估算，保证称重必定有全维度指标反馈
            val estFat = 1.20 * bmi + 0.23 * age - (if (sex == Sex.MALE) 16.2 else 5.4)
            estFat.coerceIn(5.0, 55.0)
        }

        // 2. 水分率 (%)
        val water = (69.7 - fat * 0.55).coerceIn(35.0, 75.0)

        // 3. 骨量 (kg)
        val bone = (weightKg * (if (sex == Sex.MALE) 0.047 else 0.040)).coerceIn(1.5, 5.5)

        // 4. 肌肉量 (kg)
        val muscle = max(0.0, weightKg * (1 - fat / 100.0) - bone)

        // 5. 蛋白质率 (%)
        val protein = (16.0 + (water - 50.0) * 0.12).coerceIn(10.0, 24.0)

        // 6. 基础代谢率 BMR (kcal/天) - 基于 Mifflin-St Jeor 权威公式
        val bmrBase = 10 * weightKg + 6.25 * safeHeightCm - 5 * age + (if (sex == Sex.MALE) 5.0 else -161.0)
        val bmr = bmrBase.coerceIn(500.0, 4000.0)

        // 7. 内脏脂肪等级 (1 ~ 30 级)
        val vFatBase = (bmi - 18.5) * 0.45 + (fat - (if (sex == Sex.MALE) 15.0 else 22.0)) * 0.25 + (age - 20) * 0.08
        val visceralFat = (vFatBase + 1.0).toInt().coerceIn(1, 30)

        // 8. 代谢身体年龄 (岁)
        val standardBmr = 10 * weightKg + 6.25 * safeHeightCm - 5 * age + (if (sex == Sex.MALE) 5.0 else -161.0)
        val bmrDiff = (bmr - standardBmr) / 25.0
        val metabolicAge = (age - bmrDiff.toInt()).coerceIn(12, 99)

        return BodyMeasurement(
            id = "",
            measuredAtEpochMs = timestampMs,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            bmi = String.format(java.util.Locale.US, "%.1f", bmi).toDouble(),
            bodyFatPct = String.format(java.util.Locale.US, "%.1f", fat).toDouble(),
            muscleKg = String.format(java.util.Locale.US, "%.1f", muscle).toDouble(),
            waterPct = String.format(java.util.Locale.US, "%.1f", water).toDouble(),
            proteinPct = String.format(java.util.Locale.US, "%.1f", protein).toDouble(),
            boneMassKg = String.format(java.util.Locale.US, "%.1f", bone).toDouble(),
            visceralFatRating = visceralFat,
            basalMetKcal = String.format(java.util.Locale.US, "%.0f", bmr).toDouble(),
            metabolicAge = metabolicAge,
            syncedToGarmin = false
        )
    }

    private fun calculateAge(birthDateEpochMs: Long): Int {
        if (birthDateEpochMs <= 0L) return 26
        val today = Calendar.getInstance()
        val birthDate = Calendar.getInstance().apply {
            timeInMillis = birthDateEpochMs
        }
        var age = today.get(Calendar.YEAR) - birthDate.get(Calendar.YEAR)
        val todayMonth = today.get(Calendar.MONTH)
        val todayDay = today.get(Calendar.DAY_OF_MONTH)
        val birthMonth = birthDate.get(Calendar.MONTH)
        val birthDay = birthDate.get(Calendar.DAY_OF_MONTH)
        if (todayMonth < birthMonth || (todayMonth == birthMonth && todayDay < birthDay)) {
            age--
        }
        return age.coerceIn(1, 120)
    }
}
