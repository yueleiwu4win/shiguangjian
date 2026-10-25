package cn.ray.daybook

import cn.ray.daybook.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RulesTest {
    private val day = LocalDate.of(2026, 10, 25)
    @Test fun textOnlyAndPhotoOnlyAreValid() {
        Rules.validate(day.toString(), 0, "", " 一件小事 ", 0, day)
        Rules.validate(day.toString(), 1439, "", "", 9, day)
    }
    @Test fun titleDoesNotMakeAnEmptyRecordValid() {
        assertThrows(IllegalArgumentException::class.java) { Rules.validate(day.toString(), 10, "标题", " \n ", 0, day) }
    }
    @Test fun rejectsFutureAndInvalidTime() {
        assertThrows(IllegalArgumentException::class.java) { Rules.validate("2026-10-26", 0, "", "正文", 0, day) }
        assertThrows(IllegalArgumentException::class.java) { Rules.validate(day.toString(), 1440, "", "正文", 0, day) }
    }
    @Test fun rejectsTooManyPhotosAndOverlongText() {
        assertThrows(IllegalArgumentException::class.java) { Rules.validate(day.toString(), 1, "", "正文", 10, day) }
        assertThrows(IllegalArgumentException::class.java) { Rules.validate(day.toString(), 1, "", "字".repeat(20_001), 0, day) }
    }
    @Test fun chineseAndLatinArePreservedForSearch() { assertEquals("秋日ABC\n茶".lowercase(), Rules.searchText("秋日ABC", "茶")) }
    private fun record(title: String = "标题", body: String = "正文") = RecordPayload("id", "2026-10-25", 30, title, body, 1, 2)
    @Test fun fingerprintIgnoresLocalIdAndRevision() {
        val r = record()
        assertEquals(r.fingerprint(), r.copy(id = "restored-id", revision = 5, updatedAt = 9, restoredFromId = "source").fingerprint())
    }
    @Test fun fingerprintDetectsChangedBodyDeletionAndPhotoOrder() {
        val r = record().copy(assets = listOf("a", "b"))
        assertNotEquals(r.fingerprint(), r.copy(body = "新正文").fingerprint())
        assertNotEquals(r.fingerprint(), r.copy(deletedAt = 4).fingerprint())
        assertNotEquals(r.fingerprint(), r.copy(assets = listOf("b", "a")).fingerprint())
    }
    @Test fun fingerprintHasNoFieldDelimiterAmbiguity() { assertNotEquals(record("a", "bc").fingerprint(), record("ab", "c").fingerprint()) }
    @Test fun exactTimeFormatting() { assertEquals("00:00", timeLabel(0)); assertEquals("23:59", timeLabel(1439)); assertEquals("午后", period(850)) }
}
