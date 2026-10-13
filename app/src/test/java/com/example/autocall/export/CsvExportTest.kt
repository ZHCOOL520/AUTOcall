package com.example.autocall.export

import com.example.autocall.AutoCallViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 2.7 / 2.8：CSV 字段转义与时长格式化的纯逻辑单测。
 */
class CsvExportTest {

    @Test
    fun `escape wraps field containing comma`() {
        assertEquals("\"a,b\"", AutoCallViewModel.escapeCsvField("a,b"))
    }

    @Test
    fun `escape doubles quotes`() {
        assertEquals("\"a\"\"b\"", AutoCallViewModel.escapeCsvField("a\"b"))
    }

    @Test
    fun `escape wraps field containing newline`() {
        assertEquals("\"a\nb\"", AutoCallViewModel.escapeCsvField("a\nb"))
    }

    @Test
    fun `plain field unchanged`() {
        assertEquals("13800138000", AutoCallViewModel.escapeCsvField("13800138000"))
    }

    @Test
    fun `duration zero`() {
        assertEquals("0:00:00", AutoCallViewModel.formatDuration(0))
    }

    @Test
    fun `duration 3661`() {
        assertEquals("1:01:01", AutoCallViewModel.formatDuration(3661))
    }
}
