package com.example.dianzicheng.data.garmin

import com.example.dianzicheng.domain.BodyMeasurement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 原生 Garmin FIT 二进制文件编码器。
 *
 * 遵循 Garmin 官方 FIT 协议（FIT Protocol 2.0 / Profile 21.60），
 * 生成标准 Garmin Index Smart Scale (Product 2429) 的 WEIGHT 类型 FIT 文件。
 * 无需任何外部 Python 或重型三方库依赖，100% 纯 Kotlin 原生构建。
 */
object GarminFitEncoder {

    // FIT 纪元时间偏移：1989-12-31 00:00:00 UTC 距 Unix 纪元秒数
    private const val FIT_EPOCH_OFFSET_SECONDS = 631065600L

    // CRC-16 快速计算表（Garmin FIT 官方多项式）
    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    /**
     * 计算数据段的 FIT CRC16 校验码
     */
    fun crc16(bytes: ByteArray, initial: Int = 0): Int {
        var crc = initial
        for (b in bytes) {
            val byteVal = b.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byteVal and 0xF]

            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byteVal shr 4) and 0xF]
        }
        return crc and 0xFFFF
    }

    /**
     * 将单次测量数据编码为标准 Garmin FIT 二进制字节数组。
     */
    fun encodeToBytes(measurement: BodyMeasurement): ByteArray {
        val recordsStream = ByteArrayOutputStream()

        // 1. FileId 定义消息 (Local Msg 0 -> Global Msg 0 FileId)
        recordsStream.write(
            byteArrayOf(
                0x40.toByte(), // 记录头: 0x40 = 定义消息, 本地ID 0
                0x00,          // 保留位
                0x00,          // 架构: 0 = 小端序 (Little Endian)
                0x00, 0x00,    // 全局消息编号: 0 (FileId)
                0x05           // 包含 5 个字段
            )
        )
        // 字段定义: [字段ID, 字节大小, 基础类型]
        recordsStream.write(byteArrayOf(0x00, 0x01, 0x00)) // Field 0: type, 1B, enum(0x00)
        recordsStream.write(byteArrayOf(0x01, 0x02, 0x84.toByte())) // Field 1: manufacturer, 2B, uint16(0x84)
        recordsStream.write(byteArrayOf(0x02, 0x02, 0x84.toByte())) // Field 2: product, 2B, uint16(0x84)
        recordsStream.write(byteArrayOf(0x03, 0x04, 0x8C.toByte())) // Field 3: serial_number, 4B, uint32z(0x8C)
        recordsStream.write(byteArrayOf(0x04, 0x04, 0x86.toByte())) // Field 4: time_created, 4B, uint32(0x86)

        // 2. FileId 数据消息 (Local Msg 0)
        recordsStream.write(0x00) // 记录头: 本地ID 0 数据
        recordsStream.write(0x09) // type = 9 (Weight)
        writeUInt16LE(recordsStream, 1)    // manufacturer = 1 (Garmin)
        writeUInt16LE(recordsStream, 2429) // product = 2429 (Garmin Index Scale)
        writeUInt32LE(recordsStream, 12345L) // serial_number

        val fitCreatedTime = (measurement.measuredAtEpochMs / 1000L - FIT_EPOCH_OFFSET_SECONDS).coerceAtLeast(0L)
        writeUInt32LE(recordsStream, fitCreatedTime)

        // 3. WeightScale 定义消息 (Local Msg 1 -> Global Msg 30 WeightScale)
        recordsStream.write(
            byteArrayOf(
                0x41.toByte(), // 记录头: 0x41 = 定义消息, 本地ID 1
                0x00,          // 保留位
                0x00,          // 小端序
                0x1E, 0x00,    // 全局消息编号: 30 (0x001E WeightScale)
                0x0A           // 包含 10 个字段
            )
        )
        recordsStream.write(byteArrayOf(0xFD.toByte(), 0x04, 0x86.toByte())) // Field 253: timestamp (uint32)
        recordsStream.write(byteArrayOf(0x00, 0x02, 0x84.toByte())) // Field 0: weight (uint16, scale 100)
        recordsStream.write(byteArrayOf(0x01, 0x02, 0x84.toByte())) // Field 1: percent_fat (uint16, scale 100)
        recordsStream.write(byteArrayOf(0x02, 0x02, 0x84.toByte())) // Field 2: percent_hydration (uint16, scale 100)
        recordsStream.write(byteArrayOf(0x04, 0x02, 0x84.toByte())) // Field 4: bone_mass (uint16, scale 100)
        recordsStream.write(byteArrayOf(0x05, 0x02, 0x84.toByte())) // Field 5: muscle_mass (uint16, scale 100)
        recordsStream.write(byteArrayOf(0x07, 0x02, 0x84.toByte())) // Field 7: basal_met (uint16, scale 4)
        recordsStream.write(byteArrayOf(0x0A, 0x01, 0x02))          // Field 10: metabolic_age (uint8, scale 1)
        recordsStream.write(byteArrayOf(0x0B, 0x01, 0x02))          // Field 11: visceral_fat_rating (uint8, scale 1)
        recordsStream.write(byteArrayOf(0x0D, 0x02, 0x84.toByte())) // Field 13: bmi (uint16, scale 10)

        // 4. WeightScale 数据消息 (Local Msg 1)
        recordsStream.write(0x01) // 记录头: 本地ID 1 数据
        writeUInt32LE(recordsStream, fitCreatedTime) // timestamp
        writeUInt16LE(recordsStream, (measurement.weightKg * 100.0).roundToInt().coerceIn(0, 65534))
        writeUInt16LE(recordsStream, (measurement.bodyFatPct * 100.0).roundToInt().coerceIn(0, 65534))
        writeUInt16LE(recordsStream, (measurement.waterPct * 100.0).roundToInt().coerceIn(0, 65534))
        writeUInt16LE(recordsStream, (measurement.boneMassKg * 100.0).roundToInt().coerceIn(0, 65534))
        // Garmin 将 Field 5 (muscle_mass) 映射并展示为骨骼肌质量 (Skeletal Muscle Mass)
        val skeletalMuscle = if (measurement.skeletalMuscleKg > 0.0) {
            measurement.skeletalMuscleKg
        } else {
            measurement.muscleKg
        }
        writeUInt16LE(recordsStream, (skeletalMuscle * 100.0).roundToInt().coerceIn(0, 65534))
        writeUInt16LE(recordsStream, (measurement.basalMetKcal * 4.0).roundToInt().coerceIn(0, 65534))
        recordsStream.write(measurement.metabolicAge.coerceIn(1, 254))
        recordsStream.write(measurement.visceralFatRating.coerceIn(1, 254))
        writeUInt16LE(recordsStream, (measurement.bmi * 10.0).roundToInt().coerceIn(0, 65534))

        val recordsBytes = recordsStream.toByteArray()

        // 5. 组装 14 字节标准 FIT 文件头
        val headerStream = ByteArrayOutputStream()
        headerStream.write(14)        // Header Size: 14 字节
        headerStream.write(0x20)      // Protocol Version: 2.0 (0x20)
        writeUInt16LE(headerStream, 2160) // Profile Version: 21.60
        writeUInt32LE(headerStream, recordsBytes.size.toLong()) // Data Size
        headerStream.write(byteArrayOf(0x2E, 0x46, 0x49, 0x54)) // ".FIT"

        val headerPrefix = headerStream.toByteArray()
        val headerCrc = crc16(headerPrefix)
        writeUInt16LE(headerStream, headerCrc) // Header CRC

        val headerBytes = headerStream.toByteArray()

        // 6. 计算整文件的 File CRC (Header + Records)
        var fileCrc = crc16(headerBytes)
        fileCrc = crc16(recordsBytes, initial = fileCrc)

        val finalStream = ByteArrayOutputStream()
        finalStream.write(headerBytes)
        finalStream.write(recordsBytes)
        writeUInt16LE(finalStream, fileCrc)

        return finalStream.toByteArray()
    }

    /**
     * 将测量数据直接写出为 FIT 目标文件。
     */
    fun encodeToFile(measurement: BodyMeasurement, targetFile: File): File {
        targetFile.parentFile?.mkdirs()
        val bytes = encodeToBytes(measurement)
        FileOutputStream(targetFile).use { it.write(bytes) }
        return targetFile
    }

    private fun writeUInt16LE(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
    }

    private fun writeUInt32LE(stream: ByteArrayOutputStream, value: Long) {
        stream.write((value and 0xFF).toInt())
        stream.write(((value shr 8) and 0xFF).toInt())
        stream.write(((value shr 16) and 0xFF).toInt())
        stream.write(((value shr 24) and 0xFF).toInt())
    }
}
