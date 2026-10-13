package com.example.autocall.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNamingTest {

    @Test
    fun `sanitize converts plus to p`() {
        assertEquals("p86_13800138000", RecordingNaming.sanitizeToken("+86 13800138000"))
    }

    @Test
    fun `sanitize strips illegal characters`() {
        assertEquals("13800138000", RecordingNaming.sanitizeToken("138/0013*8000"))
    }

    @Test
    fun `sanitize blank becomes unknown`() {
        assertEquals("unknown", RecordingNaming.sanitizeToken("   "))
        assertEquals("unknown", RecordingNaming.sanitizeToken(null))
    }

    @Test
    fun `sanitize truncates to 32 chars`() {
        assertTrue(RecordingNaming.sanitizeToken("1".repeat(50)).length <= 32)
    }

    @Test
    fun `sanitize keeps Chinese and hyphen`() {
        assertEquals("张-三", RecordingNaming.sanitizeToken("张-三"))
    }

    @Test
    fun `build file name matches spec`() {
        val name = RecordingNaming.buildFileName(
            seq = 1,
            direction = CallDirection.OUT,
            simSlot = 1,
            caller = "13800138000",
            callee = "13900139000",
            startedAtMs = 1749367852317L,
            durationSec = 46,
            result = CallResult.OK
        )
        assertTrue(name.startsWith("00001_O1_13800138000_13900139000_"))
        assertTrue(name.endsWith("_46s_ok.m4a"))
    }

    @Test
    fun `build file name sanitizes unknown callee`() {
        val name = RecordingNaming.buildFileName(
            seq = 2,
            direction = CallDirection.OUT,
            simSlot = 0,
            caller = "unknown",
            callee = "13700137000",
            startedAtMs = 1749367852317L,
            durationSec = 0,
            result = CallResult.NO_ANSWER
        )
        assertTrue(name.contains("_unknown_13700137000_"))
        assertTrue(name.endsWith("_0s_noans.m4a"))
    }

    @Test
    fun `call id is stable and zero padded`() {
        val id = RecordingNaming.buildCallId(1, 1749367852317L)
        assertTrue(id.endsWith("-00001"))
    }

    @Test
    fun `relative dir is year month day`() {
        val dir = RecordingNaming.relativeDir(1749367852317L)
        assertEquals(2, dir.count { it == '/' })
        assertTrue(dir.startsWith("2026/"))
    }
}
