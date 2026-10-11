package com.example.baoyancompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorLogicTest {

    @Test
    fun normDate_handles_chinese_and_common_formats() {
        assertEquals("2026-03-31", Monitor.normDate("2026年3月31日"))
        assertEquals("2026-03-01", Monitor.normDate("2026-03-01"))
        assertEquals("2026-09-01", Monitor.normDate("2026/09/01"))
        assertEquals("2026-09-01", Monitor.normDate("2026.9.1"))
        assertEquals("", Monitor.normDate("null"))
        assertEquals("", Monitor.normDate("下周"))
    }

    @Test
    fun statusText_maps_states() {
        assertEquals("报名中", Monitor.statusText("enrolling"))
        assertEquals("已截止", Monitor.statusText("closed"))
        assertEquals("未开始", Monitor.statusText("not_started"))
        assertEquals("未知", Monitor.statusText("weird"))
    }

    @Test
    fun htmlToText_strips_tags_and_scripts() {
        val t = Monitor.htmlToText(
            "<html><body><h1>报名</h1><script>var x=1;</script><style>.a{}</style>" +
                    "<p>截止 2026-03-31&nbsp;结束</p></body></html>"
        )
        assertTrue(t.contains("报名"))
        assertTrue(t.contains("截止"))
        assertFalse(t.contains("<"))
        assertFalse(t.contains("var x=1"))
    }

    @Test
    fun daysUntil_is_zero_for_today() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        assertEquals(0, Monitor.daysUntil(today))
    }

    @Test
    fun daysUntil_is_negative_or_sentinel_for_past_and_bad() {
        assertTrue(Monitor.daysUntil("2000-01-01") < 0)
        assertEquals(Int.MIN_VALUE, Monitor.daysUntil("不是日期"))
    }
}
