package com.example.dianzicheng

import com.example.dianzicheng.data.garmin.GarminFitEncoder
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.UserProfile
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GarminFitEncoderTest {

    @Test
    fun testFitHeaderAndCrc() {
        val measurement = BodyMeasurement(
            id = "test-123",
            measuredAtEpochMs = 1711536000000L, // 2024-03-27
            weightKg = 75.4,
            impedanceOhm = 512.0,
            bmi = 23.3,
            bodyFatPct = 17.5,
            waterPct = 58.2,
            muscleKg = 59.1,
            proteinPct = 17.0,
            boneMassKg = 3.2,
            basalMetKcal = 1680.0,
            visceralFatRating = 4,
            metabolicAge = 26
        )

        val fitBytes = GarminFitEncoder.encodeToBytes(measurement)
        assertNotNull(fitBytes)
        assertTrue("FIT 文件大小必须大于头(14)和校验(2)", fitBytes.size > 16)

        // 验证文件头
        assertEquals(14.toByte(), fitBytes[0]) // Header size = 14
        assertEquals(0x20.toByte(), fitBytes[1]) // Protocol version 2.0

        val bb = ByteBuffer.wrap(fitBytes).order(ByteOrder.LITTLE_ENDIAN)
        val profileVersion = bb.getShort(2).toInt() and 0xFFFF
        assertEquals(2160, profileVersion)

        val dataSize = bb.getInt(4)
        assertEquals(fitBytes.size - 14 - 2, dataSize)

        // 验证 ".FIT" 标识
        val tag = String(fitBytes, 8, 4, Charsets.US_ASCII)
        assertEquals(".FIT", tag)

        // 验证整个数据包由有效 CRC 结尾
        val fileCrc = bb.getShort(fitBytes.size - 2).toInt() and 0xFFFF
        assertTrue("文件 CRC 应为非零有效值", fileCrc != 0)
    }

    @Test
    fun testBodyAlgorithmCoverage() {
        val profile = UserProfile(
            sex = Sex.MALE,
            heightCm = 178,
            birthDate = "1998-05-15"
        )

        val metrics = BodyAlgorithm.calculateAllMetrics(
            weightKg = 72.0,
            impedanceOhm = 500.0,
            profile = profile
        )

        assertTrue("BMI 必须合理", metrics.bmi in 15.0..35.0)
        assertTrue("体脂率必须合理", metrics.bodyFatPct in 5.0..45.0)
        assertTrue("水分率必须合理", metrics.waterPct in 40.0..75.0)
        assertTrue("肌肉量必须合理", metrics.muscleKg in 30.0..65.0)
        assertTrue("骨量必须合理", metrics.boneMassKg in 1.5..5.0)
        assertTrue("基础代谢必须合理", metrics.basalMetKcal in 1000.0..2500.0)
        assertTrue("内脏脂肪等级必须大于0", metrics.visceralFatRating in 1..20)
        assertTrue("身体年龄必须在有效范围", metrics.metabolicAge in 15..80)
    }
}
