package cn.ray.daybook.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import cn.ray.daybook.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

private val Context.settingsStore by preferencesDataStore("settings")
data class AppSettings(val appearance: String = "system", val textScale: Float = 1f, val lastBackupAt: Long = 0, val lastBackupUri: String = "")

class SettingsRepository(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val scale = floatPreferencesKey("text_scale")
    private val backupAt = longPreferencesKey("backup_at")
    private val backupUri = stringPreferencesKey("backup_uri")
    val settings = context.settingsStore.data.map { AppSettings(it[theme] ?: "system", it[scale] ?: 1f, it[backupAt] ?: 0, it[backupUri] ?: "") }
    suspend fun appearance(value: String) { require(value in listOf("system", "light", "dark")); context.settingsStore.edit { it[theme] = value } }
    suspend fun scale(value: Float) { context.settingsStore.edit { it[scale] = value.coerceIn(.9f, 1.4f) } }
    suspend fun backedUp(uri: String) { context.settingsStore.edit { it[backupAt] = System.currentTimeMillis(); it[backupUri] = uri } }
}

class DaybookRepository(val db: DaybookDatabase, val media: MediaFiles) {
    val dao = db.dao()
    val writes = Mutex()
    fun day(date: LocalDate) = dao.observeDay(date.toString())
    fun timeline(limit: Int) = dao.observeAll(limit)
    fun month(month: java.time.YearMonth) = dao.observeCounts(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())
    val drafts = dao.observeDrafts()
    val trash = dao.observeTrash()

    suspend fun startDraft(date: LocalDate, source: String? = null): DraftBundle = withContext(Dispatchers.IO) { writes.withLock {
        if (source != null) dao.draftForEntry(source)?.let { return@withLock it }
        val entry = source?.let { dao.entry(it) ?: error("记录已不存在") }
        require(entry?.entry?.deletedAt == null) { "请先从回收站恢复记录" }
        val now = System.currentTimeMillis()
        val time = LocalTime.now()
        val d = DraftEntity(UUID.randomUUID().toString(), source, entry?.entry?.revision, entry?.entry?.entryDate ?: date.toString(), entry?.entry?.minuteOfDay ?: (time.hour * 60 + time.minute), entry?.entry?.title.orEmpty(), entry?.entry?.body.orEmpty(), now)
        db.withTransaction {
            dao.upsertDraft(d)
            dao.insertDraftLinks(entry?.orderedAssets()?.mapIndexed { i, a -> DraftMedia(d.id, a.id, i) }.orEmpty())
        }
        dao.draft(d.id)!!
    } }

    suspend fun writeDraft(draft: DraftPayload) = withContext(Dispatchers.IO) { writes.withLock {
        require(draft.title.length <= Rules.MAX_TITLE && draft.body.length <= Rules.MAX_BODY)
        require(draft.assets.distinct().size == draft.assets.size && draft.assets.size <= Rules.MAX_PHOTOS)
        require(dao.draft(draft.id) != null) { "草稿已被保存或移除" }
        draft.assets.forEach { check(dao.asset(it) != null) { "图片尚未导入完成" } }
        db.withTransaction {
            dao.upsertDraft(draft.entity())
            dao.clearDraftLinks(draft.id)
            dao.insertDraftLinks(draft.assets.mapIndexed { i, id -> DraftMedia(draft.id, id, i) })
        }
    } }

    suspend fun importImages(draftId: String, uris: List<Uri>): DraftBundle = withContext(Dispatchers.IO) { writes.withLock {
        var draft = dao.draft(draftId) ?: error("草稿已不存在")
        require(uris.size <= Rules.MAX_PHOTOS - draft.assets.size) { "每条最多 9 张图片，请减少选择数量" }
        for (uri in uris) {
            val asset = media.import(uri)
            val ids = (draft.orderedAssets().map { it.id } + asset.id).distinct()
            db.withTransaction {
                dao.insertAsset(asset)
                dao.clearDraftLinks(draftId)
                dao.insertDraftLinks(ids.mapIndexed { i, id -> DraftMedia(draftId, id, i) })
                dao.upsertDraft(draft.draft.copy(updatedAt = System.currentTimeMillis()))
            }
            draft = dao.draft(draftId)!!
        }
        draft
    } }

    suspend fun save(draft: DraftPayload): String = withContext(Dispatchers.IO) { writes.withLock {
        Rules.validate(draft.date, draft.minute, draft.title, draft.body, draft.assets.size)
        require(draft.assets.distinct().size == draft.assets.size) { "同一条记录中图片不能重复" }
        draft.assets.forEach { val a = dao.asset(it) ?: error("图片丢失，请重新选择"); check(media.original(a).isFile) { "图片文件丢失，请重新选择" } }
        val old = draft.sourceEntryId?.let { dao.entry(it)?.entry }
        if (draft.sourceEntryId != null) require(old != null && old.deletedAt == null && old.revision == draft.baseRevision) { "记录已发生变化；草稿已保留，请返回重新确认" }
        val now = System.currentTimeMillis()
        val entry = EntryEntity(old?.id ?: UUID.randomUUID().toString(), draft.date, draft.minute, draft.title.trim(), draft.body.trim(), Rules.searchText(draft.title.trim(), draft.body.trim()), old?.createdAt ?: now, now, (old?.revision ?: 0) + 1, restoredFromId = old?.restoredFromId)
        db.withTransaction {
            dao.upsertEntry(entry)
            dao.clearEntryLinks(entry.id)
            dao.insertEntryLinks(draft.assets.mapIndexed { i, id -> EntryMedia(entry.id, id, i) })
            dao.deleteDraft(draft.id)
        }
        entry.id
    } }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) { writes.withLock { dao.deleteDraft(id) } }
    suspend fun moveToTrash(id: String) = withContext(Dispatchers.IO) { writes.withLock {
        check(dao.draftForEntry(id) == null) { "此记录还有编辑草稿，请先保存或丢弃草稿" }
        val e = dao.entry(id)?.entry ?: error("记录不存在")
        dao.upsertEntry(e.copy(deletedAt = System.currentTimeMillis(), revision = e.revision + 1, updatedAt = System.currentTimeMillis()))
    } }
    suspend fun restore(id: String) = withContext(Dispatchers.IO) { writes.withLock {
        val e = dao.entry(id)?.entry ?: error("记录不存在")
        dao.upsertEntry(e.copy(deletedAt = null, revision = e.revision + 1, updatedAt = System.currentTimeMillis()))
    } }
    suspend fun permanentlyDelete(id: String) = withContext(Dispatchers.IO) { writes.withLock {
        check(dao.entry(id)?.entry?.deletedAt != null) { "仅可永久删除回收站记录" }
        db.withTransaction { dao.deleteEntry(id) }
        cleanupUnlocked(System.currentTimeMillis() + 1)
    } }
    suspend fun cleanup() = withContext(Dispatchers.IO) { writes.withLock { cleanupUnlocked(System.currentTimeMillis() - 86_400_000) } }
    private suspend fun cleanupUnlocked(before: Long) {
        dao.unreferenced(before).forEach { asset ->
            if (!media.original(asset).exists() || media.original(asset).delete()) dao.deleteAsset(asset.id)
        }
        // Files left before the asset transaction are safe to remove only after a protection period.
        val known = dao.allAssets().map { it.relativePath.substringAfterLast('/') }.toSet()
        val orphanBefore = System.currentTimeMillis() - 86_400_000
        media.mediaDir.listFiles()?.filter { it.name !in known && it.lastModified() < orphanBefore }?.forEach { it.delete() }
        media.stagingDir.listFiles()?.filter { it.lastModified() < orphanBefore }?.forEach { if (it.isFile) it.delete() }
    }
}
