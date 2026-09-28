package com.example.dianzicheng.data.local

import com.example.dianzicheng.domain.BodyMeasurement

/** 将数据库实体 [MeasurementEntity] 转换为领域模型 [BodyMeasurement] */
fun MeasurementEntity.toDomain() = BodyMeasurement(
    id = id,
    measuredAtEpochMs = measuredAtEpochMs,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPct = bodyFatPct,
    muscleKg = muscleKg,
    waterPct = waterPct,
    proteinPct = proteinPct,
    boneMassKg = boneMassKg,
    visceralFatRating = visceralFatRating,
    basalMetKcal = basalMetKcal,
    metabolicAge = metabolicAge,
    syncedToGarmin = syncedToGarmin,
    syncedToGarminAtEpochMs = syncedToGarminAtEpochMs,
    fatMassKg = fatMassKg,
    subcutaneousFatPct = subcutaneousFatPct,
    subcutaneousFatKg = subcutaneousFatKg,
    boneMassPct = boneMassPct,
    musclePct = musclePct,
    waterKg = waterKg,
    proteinKg = proteinKg,
    skeletalMusclePct = skeletalMusclePct,
    skeletalMuscleKg = skeletalMuscleKg
)

/** 将领域模型 [BodyMeasurement] 转换为数据库实体 [MeasurementEntity]，用于持久化存储 */
fun BodyMeasurement.toEntity() = MeasurementEntity(
    id = id,
    measuredAtEpochMs = measuredAtEpochMs,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPct = bodyFatPct,
    muscleKg = muscleKg,
    waterPct = waterPct,
    proteinPct = proteinPct,
    boneMassKg = boneMassKg,
    visceralFatRating = visceralFatRating,
    basalMetKcal = basalMetKcal,
    metabolicAge = metabolicAge,
    syncedToGarmin = syncedToGarmin,
    syncedToGarminAtEpochMs = syncedToGarminAtEpochMs,
    fatMassKg = fatMassKg,
    subcutaneousFatPct = subcutaneousFatPct,
    subcutaneousFatKg = subcutaneousFatKg,
    boneMassPct = boneMassPct,
    musclePct = musclePct,
    waterKg = waterKg,
    proteinKg = proteinKg,
    skeletalMusclePct = skeletalMusclePct,
    skeletalMuscleKg = skeletalMuscleKg
)
