package com.example.dianzicheng.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 特权状态是 UI 文案和开关可用性的唯一依据，必须保持纯 Kotlin、可回归。
 */
class PrivilegeStatusTest {

    @Test
    fun `permanent grant always wins over shizuku state`() {
        val status = PrivilegeStatus(
            hasPermanentGrant = true,
            shizukuState = ShizukuState.UNAVAILABLE
        )

        assertEquals(PrivilegeMode.PERMANENT, status.mode)
        assertTrue(status.canControlInApp)
        assertFalse(status.needsPermanentSetup)
    }

    @Test
    fun `shizuku ready can control temporarily but still needs permanent setup`() {
        val status = PrivilegeStatus(
            hasPermanentGrant = false,
            shizukuState = ShizukuState.READY
        )

        assertEquals(PrivilegeMode.SHIZUKU_READY, status.mode)
        assertTrue(status.canControlInApp)
        assertTrue(status.needsPermanentSetup)
    }

    @Test
    fun `shizuku waiting for permission cannot control yet`() {
        val status = PrivilegeStatus(
            hasPermanentGrant = false,
            shizukuState = ShizukuState.NEED_PERMISSION
        )

        assertEquals(PrivilegeMode.SHIZUKU_NEED_PERMISSION, status.mode)
        assertFalse(status.canControlInApp)
        assertTrue(status.needsPermanentSetup)
    }

    @Test
    fun `no privilege falls back to manual settings`() {
        val status = PrivilegeStatus(
            hasPermanentGrant = false,
            shizukuState = ShizukuState.UNAVAILABLE
        )

        assertEquals(PrivilegeMode.NONE, status.mode)
        assertFalse(status.canControlInApp)
        assertTrue(status.needsPermanentSetup)
    }
}
