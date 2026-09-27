package com.example.dianzicheng

import com.example.dianzicheng.data.ble.AFUPacketParser
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testAFUPacketParser() {
        val rawData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        
        val weightData = AFUPacketParser.parseWeight(rawData)
        assertNotNull(weightData)
        assertEquals(82.05, weightData!!.weightKg, 0.0001)
        assertTrue(weightData.isStable)

        val impedance = AFUPacketParser.parseImpedance(rawData)
        assertNotNull(impedance)
        assertEquals(1344.0, impedance!!, 0.0001)
    }
}