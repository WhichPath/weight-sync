package com.example.dianzicheng.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 数据库实体：体重与多维身体成分测量记录表（measurements）。
 *
 * 移除了多成员外键字段，直接记录每次称重的所有维度指标以及 Garmin 同步状态。
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val measuredAtEpochMs: Long,
    val weightKg: Double,
    val impedanceOhm: Double,
    val bmi: Double,
    val bodyFatPct: Double,
    val muscleKg: Double,
    val waterPct: Double,
    val proteinPct: Double,
    val boneMassKg: Double,
    val visceralFatRating: Int = 1,
    val basalMetKcal: Double = 0.0,
    val metabolicAge: Int = 25,
    val syncedToGarmin: Boolean = false,
    val syncedToGarminAtEpochMs: Long? = null,
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
