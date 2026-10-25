package cn.ray.daybook

import cn.ray.daybook.data.ArchiveCodec
import cn.ray.daybook.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class ArchiveCodecTest {
    private val hash = "a".repeat(64)
    private fun manifest() = ArchiveManifest(exportedAt = 1, files = listOf(ArchiveFile("entries.jsonl", hash, 0), ArchiveFile("drafts.jsonl", hash, 0)), assets = emptyList())
    @Test fun allowsOnlyRelativePaths() {
        listOf("../x", "/x", "C:/x", "media/../../x", "media\\x", "media//x").forEach { assertFalse(it, ArchiveCodec.safePath(it)) }
        assertTrue(ArchiveCodec.safePath("media/a.jpg"))
    }
    @Test fun validEmptyArchiveManifest() { ArchiveCodec.validateManifest(manifest()) }
    @Test fun previousAppVersionKeepsCompatibleBackupFormat() { ArchiveCodec.validateManifest(manifest().copy(appVersion = "0.1.0")) }
    @Test fun rejectsUnsupportedVersion() { assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.validateManifest(manifest().copy(formatVersion = 2)) } }
    @Test fun rejectsRepeatedAndUnknownPaths() {
        val m = manifest()
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.validateManifest(m.copy(files = m.files + m.files.first())) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.validateManifest(m.copy(files = m.files + ArchiveFile("other.txt", hash, 1))) }
    }
    @Test fun rejectsMediaNotMatchingManifest() {
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.validateManifest(manifest().copy(assets = listOf(AssetPayload(hash, hash, "image/jpeg", 1, 1, 1, "media/$hash.jpg")))) }
    }
    @Test fun rejectsMissingImageRelationAndDuplicateRecords() {
        val r = RecordPayload(UUID.randomUUID().toString(), "2020-01-01", 1, "", "记录", 1, 1)
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.validateRecords(listOf(r.copy(assets = listOf(hash))), emptyList(), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveCodec.validateRecords(listOf(r, r), emptyList(), emptySet()) }
    }
    @Test fun chineseMultilineRoundTrip() {
        val r = RecordPayload(UUID.randomUUID().toString(), "2020-01-01", 600, "一卷闲书", "第一行\n第二行：\"引号\"与\\斜线", 1, 1)
        val f = Files.createTempFile("daybook", ".jsonl").toFile()
        try { ArchiveCodec.writeLines(f, listOf(r)); assertEquals(listOf(r), ArchiveCodec.readLines<RecordPayload>(f)) } finally { f.delete() }
    }
}
