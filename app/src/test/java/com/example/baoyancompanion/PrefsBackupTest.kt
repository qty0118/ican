package com.example.baoyancompanion

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsBackupTest {

    @Test
    fun roundTrip_preserves_json_strings_with_special_chars() {
        val m = mapOf(
            "tasks" to "[{\"title\":\"背单词\"}]",
            "target_date" to "2028-09-01",
            "memories" to "含中文、换行\n和\"引号\"还有反斜杠\\",
            "papers" to "[]"
        )
        val json = PrefsBackup.mapToJson(m)
        val back = PrefsBackup.jsonToMap(json)
        assertEquals(m, back)
    }

    @Test
    fun nullValues_areDropped() {
        val json = PrefsBackup.mapToJson(mapOf("a" to "1", "b" to null))
        assertEquals(mapOf("a" to "1"), PrefsBackup.jsonToMap(json))
    }

    @Test
    fun emptyMap_isEmptyObject() {
        assertEquals("{}", PrefsBackup.mapToJson(emptyMap()))
        assertEquals(emptyMap<String, String>(), PrefsBackup.jsonToMap("{}"))
    }

    @Test
    fun jsonToMap_readsNestedJsonAsRawString() {
        val src = "{\"watched\":\"[{\\\"name\\\":\\\"赛氪\\\"}]\",\"docs\":\"[]\"}"
        val back = PrefsBackup.jsonToMap(src)
        assertEquals("[{\"name\":\"赛氪\"}]", back["watched"])
        assertEquals("[]", back["docs"])
    }
}
