package com.example.dianzicheng.domain

/** 性别枚举，用于体脂算法及身体成分计算 */
enum class Sex { FEMALE, MALE }

/**
 * 个人身体资料（单用户，避免多成员导致匹配失败和指标清零的问题）。
 *
 * @param sex 性别
 * @param heightCm 身高（cm）
 * @param birthDateEpochMs 出生日期毫秒时间戳
 */
data class UserProfile(
    val sex: Sex = Sex.MALE,
    val heightCm: Double = 175.0,
    val birthDateEpochMs: Long = 946684800000L // 2000-01-01
)

/**
 * 单次测量完整身体数据模型。
 *
 * 包含阿福体脂秤硬件测量的体重、阻抗，以及算法推导的 Garmin 支持的全部多项身体成分。
 *
 * @param id 测量唯一标识（会话 sessionId）
 * @param measuredAtEpochMs 测量发生时的绝对毫秒时间戳
 * @param weightKg 体重（kg）
 * @param impedanceOhm 生物电阻抗（Ω），0.0 表示未赤脚或未测得
 * @param bmi 体质指数 (kg/m²)
 * @param bodyFatPct 体脂率 (%)
 * @param muscleKg 肌肉量 (kg)
 * @param waterPct 水分率 (%)
 * @param proteinPct 蛋白质率 (%)
 * @param boneMassKg 骨量 (kg)
 * @param visceralFatRating 内脏脂肪等级 (1~30)
 * @param basalMetKcal 基础代谢率 (kcal/天)
 * @param metabolicAge 代谢身体年龄 (岁)
 * @param syncedToGarmin 是否已成功同步至 Garmin Connect
 * @param syncedToGarminAtEpochMs 上传至 Garmin 的时间戳
 */
data class BodyMeasurement(
    val id: String,
    val measuredAtEpochMs: Long,
    val weightKg: Double,
    val impedanceOhm: Double = 0.0,
    val bmi: Double = 0.0,
    val bodyFatPct: Double = 0.0,
    val muscleKg: Double = 0.0,
    val waterPct: Double = 0.0,
    val proteinPct: Double = 0.0,
    val boneMassKg: Double = 0.0,
    val visceralFatRating: Int = 1,
    val basalMetKcal: Double = 0.0,
    val metabolicAge: Int = 25,
    val syncedToGarmin: Boolean = false,
    val syncedToGarminAtEpochMs: Long? = null,
    // 阿福 App 全量指标镜像
    val fatMassKg: Double = 0.0,
    val subcutaneousFatPct: Double = 0.0,
    val subcutaneousFatKg: Double = 0.0,
    val boneMassPct: Double = 0.0,
    val musclePct: Double = 0.0,
    val waterKg: Double = 0.0,
    val proteinKg: Double = 0.0,
    val skeletalMusclePct: Double = 0.0,
    val skeletalMuscleKg: Double = 0.0
)
