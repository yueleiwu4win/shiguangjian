package cn.ray.daybook

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.ray.daybook.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

abstract class RepositoryContract {
    private lateinit var context: Context
    private lateinit var db: DaybookDatabase
    private lateinit var repo: DaybookRepository
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DaybookDatabase::class.java).build()
        repo = DaybookRepository(db, MediaFiles(context))
    }
    @After fun close() { db.close() }
    private suspend fun save(body: String = "巷陌寻秋") : String {
        val draft = repo.startDraft(LocalDate.of(2020, 10, 25)).payload().copy(body = body)
        repo.writeDraft(draft)
        return repo.save(draft)
    }
    @Test fun saveDraftEditAndTrashRoundTrip() = runBlocking {
        val id = save()
        assertTrue(repo.dao.allDrafts().isEmpty())
        val draft = repo.startDraft(LocalDate.now(), id).payload().copy(body = "修改后的正文")
        repo.writeDraft(draft); repo.save(draft)
        assertEquals("修改后的正文", repo.dao.entry(id)!!.entry.body)
        repo.moveToTrash(id); assertNotNull(repo.dao.entry(id)!!.entry.deletedAt)
        repo.restore(id); assertNull(repo.dao.entry(id)!!.entry.deletedAt)
        repo.moveToTrash(id); repo.permanentlyDelete(id); assertNull(repo.dao.entry(id))
    }
    @Test fun staleEditorCannotOverwriteNewerRecord() = runBlocking {
        val id = save()
        val draft = repo.startDraft(LocalDate.now(), id).payload()
        val e = repo.dao.entry(id)!!.entry
        repo.dao.upsertEntry(e.copy(revision = e.revision + 1, body = "较新内容"))
        assertTrue(runCatching { repo.save(draft.copy(body = "旧内容")) }.isFailure)
        assertEquals("较新内容", repo.dao.entry(id)!!.entry.body)
        assertNotNull(repo.dao.draft(draft.id))
    }
    @Test fun recordsAndDraftsSurviveDatabaseReopen() = runBlocking {
        val databaseName = "daybook-durability-${java.util.UUID.randomUUID()}.db"
        var persistent = Room.databaseBuilder(context, DaybookDatabase::class.java, databaseName).build()
        try {
            val first = DaybookRepository(persistent, repo.media)
            val draft = first.startDraft(LocalDate.of(2020, 5, 6)).payload().copy(body = "关闭数据库后仍需保留", minute = 620)
            val id = first.save(draft)
            val unfinished = first.startDraft(LocalDate.of(2020, 5, 7)).payload().copy(body = "待续的草稿")
            first.writeDraft(unfinished)
            persistent.close()
            persistent = Room.databaseBuilder(context, DaybookDatabase::class.java, databaseName).build()
            val reopened = persistent.dao()
            assertEquals("关闭数据库后仍需保留", reopened.entry(id)!!.entry.body)
            assertEquals("2020-05-06", reopened.entry(id)!!.entry.entryDate)
            assertEquals(620, reopened.entry(id)!!.entry.minuteOfDay)
            assertEquals("待续的草稿", reopened.draft(unfinished.id)!!.draft.body)
        } finally { persistent.close(); context.deleteDatabase(databaseName) }
    }
    @Test fun allRecordsSpanDaysSortNewestFirstAndExcludeTrash() = runBlocking {
        suspend fun add(date: String, minute: Int, text: String): String {
            val draft = repo.startDraft(LocalDate.parse(date)).payload().copy(minute = minute, body = text)
            return repo.save(draft)
        }
        val oldest = add("2020-01-01", 1200, "旧日")
        val morning = add("2020-01-02", 420, "清晨")
        val afternoon = add("2020-01-02", 900, "午后")
        val deleted = add("2020-01-03", 600, "已删除")
        repo.moveToTrash(deleted)
        assertEquals(listOf(afternoon, morning), repo.timeline(2).first().map { it.entry.id })
        assertEquals(listOf(afternoon, morning, oldest), repo.timeline(50).first().map { it.entry.id })
    }
    @Test fun backupIsIdempotentAndConflictsKeepBothVersions() = runBlocking {
        val id = save("原始正文")
        val pending = repo.startDraft(LocalDate.of(2020, 1, 2)).payload().copy(body = "未完成草稿")
        repo.writeDraft(pending)
        val file = File(context.cacheDir, "test-${System.nanoTime()}.daybook")
        val backup = BackupService(context, repo, SettingsRepository(context))
        try {
            backup.export(Uri.fromFile(file)) {}
            val p1 = backup.prepare(Uri.fromFile(file)) {}
            assertEquals(1, p1.preview.skippedEntries)
            backup.restore(p1) {}
            assertEquals(1, repo.dao.allEntries().size)
            val edit = repo.startDraft(LocalDate.now(), id).payload().copy(body = "本机新正文")
            repo.save(edit)
            val p2 = backup.prepare(Uri.fromFile(file)) {}
            assertEquals(1, p2.preview.conflictingEntries)
            backup.restore(p2) {}
            assertEquals(2, repo.dao.allEntries().size)
            assertEquals("本机新正文", repo.dao.entry(id)!!.entry.body)
            val p3 = backup.prepare(Uri.fromFile(file)) {}
            backup.restore(p3) {}
            assertEquals(2, repo.dao.allEntries().size)
            assertEquals(1, repo.dao.allDrafts().size)
        } finally { file.delete() }
    }
    @Test fun importedPhotoSurvivesSourceRemovalAndBackupRestore() = runBlocking {
        val source = File(context.cacheDir, "photo-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(140, 80, 50))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val draft = repo.startDraft(LocalDate.of(2020, 1, 1))
        val imported = repo.importImages(draft.draft.id, listOf(Uri.fromFile(source)))
        val asset = imported.assets.single()
        source.delete()
        assertTrue(repo.media.original(asset).isFile)
        val id = repo.save(imported.payload())
        val file = File(context.cacheDir, "image-backup-${System.nanoTime()}.daybook")
        val backup = BackupService(context, repo, SettingsRepository(context))
        try {
            backup.export(Uri.fromFile(file)) {}
            val otherDb = Room.inMemoryDatabaseBuilder(context, DaybookDatabase::class.java).build()
            try {
                val other = DaybookRepository(otherDb, repo.media)
                val restore = BackupService(context, other, SettingsRepository(context))
                restore.restore(restore.prepare(Uri.fromFile(file)) {}) {}
                assertEquals(asset.sha256, other.dao.entry(id)!!.orderedAssets().single().sha256)
            } finally { otherDb.close() }
        } finally { file.delete() }
    }
    @Test fun damagedBackupDoesNotChangeExistingRecords() = runBlocking {
        val id = save()
        val file = File(context.cacheDir, "broken-${System.nanoTime()}.daybook").apply { writeText("broken zip") }
        try {
            val backup = BackupService(context, repo, SettingsRepository(context))
            assertTrue(runCatching { backup.prepare(Uri.fromFile(file)) {} }.isFailure)
            assertEquals("巷陌寻秋", repo.dao.entry(id)!!.entry.body)
        } finally { file.delete() }
    }
}
