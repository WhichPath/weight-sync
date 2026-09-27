package com.example.dianzicheng

import com.example.dianzicheng.data.ble.AFUPacketParser
import org.junit.Assert.*
import org.junit.Test

class AFUPacketParserTest {

    @Test
    fun testAfuProtocolNotification() {
        // 经典的 AFU 协议数据包 (82.05kg, 1344Ω, 稳定)
        val rawData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )

        val weightData = AFUPacketParser.parseWeight(rawData)
        assertNotNull(weightData)
        assertEquals(82.05, weightData!!.weightKg, 0.001)
        assertTrue(weightData.isStable)

        val impedance = AFUPacketParser.parseImpedance(rawData)
        assertNotNull(impedance)
        assertEquals(1344.0, impedance!!, 0.1)
    }

    @Test
    fun testAfuStepOffZeroWeightPacket() {
        // AFU 下秤 0.00kg 离秤数据包 (w3 < 0x68)
        val zeroData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x02.toByte(), 0x00.toByte(), 0x01.toByte(),
            0xE7.toByte(), 0x01.toByte(), 0xB2.toByte(), 0x01.toByte(), 0x80.toByte()
        )

        val result = AFUPacketParser.parseWeight(zeroData)
        assertNotNull("下秤数据包应被识别为 0.0kg", result)
        assertEquals(0.0, result!!.weightKg, 0.001)
        assertFalse(result.isStable)
    }

    @Test
    fun testAfuOutofRangeRejected() {
        // 超出合理范围的包 (如 w3 > 0x6C，产生 2820kg) 应被彻底拦截
        val ghostData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x93.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte()
        )

        val result = AFUPacketParser.parseWeight(ghostData)
        assertNull("异常范围报文必须被拦截", result)
    }

    @Test
    fun testAfuDynamicClimbingWeight() {
        // 动态爬升报文 (7.00kg，isStable = false)
        // 7000 = 0x68 * 65536 + 0x1B * 256 + 0x58 (7000g = 7.0kg)
        val climbingData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x68.toByte(), 0x1B.toByte(),
            0x58.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )

        val result = AFUPacketParser.parseWeight(climbingData)
        assertNotNull(result)
        assertEquals(7.0, result!!.weightKg, 0.001)
        assertFalse("爬升中不得标记为稳定", result.isStable)
    }
}
