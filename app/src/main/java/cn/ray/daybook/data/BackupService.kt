package cn.ray.daybook.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import cn.ray.daybook.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

data class RestorePreview(val newEntries: Int, val skippedEntries: Int, val conflictingEntries: Int, val trashEntries: Int, val draftCount: Int, val bytes: Long)
data class PreparedRestore(val directory: File, val manifest: ArchiveManifest, val entries: List<RecordPayload>, val drafts: List<DraftPayload>, val preview: RestorePreview)
data class RestoreResult(val imported: Int, val conflicts: Int, val skipped: Int, val drafts: Int)

class BackupService(private val context: Context, private val repo: DaybookRepository, private val settings: SettingsRepository) {
    private val dao get() = repo.dao
    private val work = File(context.filesDir, "backup-staging").apply { mkdirs() }

    suspend fun export(uri: Uri, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val dir = File(work, "export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val archive = repo.writes.withLock {
                progress("正在整理本机记录…")
                val (records, drafts) = repo.db.withTransaction { dao.allEntries().map { it.payload() } to dao.allDrafts().map { it.payload() } }
                val ids = (records.flatMap { it.assets } + drafts.flatMap { it.assets }).toSet()
                val assets = ids.map { dao.asset(it) ?: error("图片索引丢失，无法完整备份") }
                val estimate = assets.sumOf { it.bytes } + records.sumOf { (it.body.length + it.title.length) * 4L } + 16 * 1024 * 1024
                require(dir.usableSpace > estimate) { "本机临时空间不足，无法生成完整备份" }
                val entriesFile = File(dir, "entries.jsonl"); ArchiveCodec.writeLines(entriesFile, records)
                val draftsFile = File(dir, "drafts.jsonl"); ArchiveCodec.writeLines(draftsFile, drafts)
                val sourceFiles = linkedMapOf("entries.jsonl" to entriesFile, "drafts.jsonl" to draftsFile)
                assets.forEachIndexed { index, a ->
                    coroutineContext.ensureActive()
                    progress("核对图片 ${index + 1}/${assets.size}")
                    val f = repo.media.original(a)
                    require(f.isFile && f.length() == a.bytes && MediaFiles.digest(f) == a.sha256) { "有图片缺失或损坏，未生成不完整备份" }
                    sourceFiles[a.relativePath] = f
                }
                val manifest = ArchiveManifest(exportedAt = System.currentTimeMillis(), files = sourceFiles.map { (path, file) -> ArchiveFile(path, MediaFiles.digest(file), file.length()) }, assets = assets.map { AssetPayload(it.id, it.sha256, it.mimeType, it.bytes, it.width, it.height, it.relativePath) })
                ArchiveCodec.validateManifest(manifest)
                val zip = File(dir, "backup.daybook")
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    out.putNextEntry(ZipEntry("manifest.json")); out.write(ArchiveCodec.json.encodeToString(manifest).toByteArray(Charsets.UTF_8)); out.closeEntry()
                    sourceFiles.entries.forEachIndexed { i, (path, file) ->
                        coroutineContext.ensureActive(); progress("正在打包 ${i + 1}/${sourceFiles.size}")
                        out.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(out, 64 * 1024) }; out.closeEntry()
                    }
                }
                zip
            }
            progress("正在写入所选位置…")
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> archive.inputStream().use { it.copyTo(output, 64 * 1024) } } ?: error("无法写入所选位置")
            // Reopen destination: a successful local ZIP alone does not mean export succeeded.
            val verified = File(dir, "verify.daybook")
            context.contentResolver.openInputStream(uri)?.use { input -> verified.outputStream().use { MediaFiles.copyLimited(input, it, ArchiveCodec.MAX_ARCHIVE_BYTES) } } ?: error("无法复核导出文件，请重新选择本机位置")
            check(verified.length() == archive.length() && MediaFiles.digest(verified) == MediaFiles.digest(archive)) { "导出文件校验失败，请重试" }
            settings.backedUp(uri.toString())
            progress("备份已完成并校验")
        } catch (e: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        } finally { dir.deleteRecursively() }
    }

    suspend fun prepare(uri: Uri, progress: (String) -> Unit): PreparedRestore = withContext(Dispatchers.IO) {
        val dir = File(work, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress("正在读取备份…")
            val archive = File(dir, "source.daybook")
            context.contentResolver.openInputStream(uri)?.use { input -> archive.outputStream().use { MediaFiles.copyLimited(input, it, ArchiveCodec.MAX_ARCHIVE_BYTES) } } ?: error("无法读取备份文件")
            val manifest = ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 3..100_003 && entries.map { it.name }.distinct().size == entries.size) { "备份结构无效或包含重复路径" }
                require(entries.all { !it.isDirectory && ArchiveCodec.safePath(it.name) }) { "备份包含不安全的文件路径" }
                val metadata = zip.getEntry("manifest.json") ?: error("不是有效的拾光笺备份")
                val manifestFile = File(dir, "manifest.json")
                zip.getInputStream(metadata).use { input -> manifestFile.outputStream().use { MediaFiles.copyLimited(input, it, ArchiveCodec.MAX_MANIFEST_BYTES) } }
                val m = ArchiveCodec.json.decodeFromString<ArchiveManifest>(manifestFile.readText(Charsets.UTF_8))
                ArchiveCodec.validateManifest(m)
                require(entries.map { it.name }.toSet() == m.files.map { it.path }.toSet() + "manifest.json") { "备份清单与文件不一致" }
                val expanded = m.files.sumOf { it.bytes }
                require(dir.usableSpace > expanded * 2 + 32L * 1024 * 1024) { "恢复所需临时空间不足" }
                m.files.forEachIndexed { index, item ->
                    coroutineContext.ensureActive(); progress("正在校验 ${index + 1}/${m.files.size}")
                    val z = zip.getEntry(item.path) ?: error("备份缺少文件")
                    require(z.size == item.bytes && (z.size <= 1024 * 1024 || z.compressedSize > 0 && z.size / z.compressedSize.coerceAtLeast(1) <= 500)) { "备份文件大小或压缩比异常" }
                    val file = File(dir, item.path).apply { parentFile?.mkdirs() }
                    val limit = if (item.path.endsWith(".jsonl")) minOf(item.bytes, ArchiveCodec.MAX_METADATA_BYTES) else minOf(item.bytes, Rules.MAX_IMAGE_BYTES)
                    zip.getInputStream(z).use { input -> file.outputStream().use { MediaFiles.copyLimited(input, it, limit) } }
                    require(file.length() == item.bytes && MediaFiles.digest(file) == item.sha256) { "备份文件损坏：${item.path}" }
                }
                m
            }
            val entries = ArchiveCodec.readLines<RecordPayload>(File(dir, "entries.jsonl"))
            val drafts = ArchiveCodec.readLines<DraftPayload>(File(dir, "drafts.jsonl"))
            ArchiveCodec.validateRecords(entries, drafts, manifest.assets.map { it.id }.toSet())
            manifest.assets.forEach { asset -> val info = repo.media.inspect(File(dir, asset.path)); require(info.mime == asset.mime && info.width == asset.width && info.height == asset.height) { "图片内容与清单不符" } }
            val preview = repo.writes.withLock { preview(entries, drafts, manifest.files.sumOf { it.bytes }) }
            PreparedRestore(dir, manifest, entries, drafts, preview)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    private suspend fun preview(records: List<RecordPayload>, drafts: List<DraftPayload>, bytes: Long): RestorePreview {
        var new = 0; var skip = 0; var conflict = 0
        for (r in records) {
            val old = dao.entry(r.id)
            if (old?.payload()?.fingerprint() == r.fingerprint() || hasLiveReceipt(r.id, r.fingerprint(), "entry")) skip++
            else if (old == null) new++ else conflict++
        }
        return RestorePreview(new, skip, conflict, records.count { it.deletedAt != null }, drafts.size, bytes)
    }
    private suspend fun hasLiveReceipt(id: String, fingerprint: String, type: String): Boolean {
        val receipt = dao.receipt(id, fingerprint, type) ?: return false
        return if (type == "entry") dao.entry(receipt.localRecordId) != null else dao.draft(receipt.localRecordId) != null
    }

    suspend fun restore(prepared: PreparedRestore, progress: (String) -> Unit): RestoreResult = withContext(Dispatchers.IO) { repo.writes.withLock {
        progress("正在合并恢复…")
        // Staging is private and immutable after validation. Recheck files before committing references.
        for (asset in prepared.manifest.assets) {
            coroutineContext.ensureActive()
            val source = File(prepared.directory, asset.path)
            require(source.isFile && MediaFiles.digest(source) == asset.sha256) { "恢复图片已损坏" }
            val destination = File(context.filesDir, asset.path)
            if (!destination.exists()) {
                val temp = File(repo.media.stagingDir, "restore-${UUID.randomUUID()}.part")
                try { source.copyTo(temp, overwrite = false); check(temp.renameTo(destination)) { "无法保存恢复图片" } } finally { temp.delete() }
            } else require(destination.length() == asset.bytes && MediaFiles.digest(destination) == asset.sha256) { "本机同名图片已损坏，未覆盖现有文件" }
        }
        var imported = 0; var conflicts = 0; var skipped = 0; var draftCount = 0
        repo.db.withTransaction {
            prepared.manifest.assets.forEach { a -> dao.insertAsset(MediaAsset(a.id, a.path, a.sha256, a.mime, a.bytes, a.width, a.height, System.currentTimeMillis())) }
            val sourceMapping = mutableMapOf<String, String>()
            for (record in prepared.entries) {
                val old = dao.entry(record.id)
                val fingerprint = record.fingerprint()
                val receipt = dao.receipt(record.id, fingerprint, "entry")
                if (old?.payload()?.fingerprint() == fingerprint) { skipped++; sourceMapping[record.id] = record.id; continue }
                if (receipt != null && dao.entry(receipt.localRecordId) != null) { skipped++; sourceMapping[record.id] = receipt.localRecordId; continue }
                val id = if (old == null) record.id else UUID.randomUUID().toString()
                if (old == null) imported++ else conflicts++
                dao.upsertEntry(record.entity(id, if (old == null) record.restoredFromId else record.id))
                dao.insertEntryLinks(record.assets.mapIndexed { i, asset -> EntryMedia(id, asset, i) })
                dao.putReceipt(RestoreReceipt(record.id, fingerprint, "entry", id))
                sourceMapping[record.id] = id
            }
            for (draft in prepared.drafts) {
                val old = dao.draft(draft.id)
                val fingerprint = draft.fingerprint()
                if (old?.payload()?.fingerprint() == fingerprint || hasLiveReceipt(draft.id, fingerprint, "draft")) continue
                val id = if (old == null) draft.id else UUID.randomUUID().toString()
                val mappedSource = draft.sourceEntryId?.let { sourceMapping[it] ?: it }
                val source = mappedSource?.let { dao.entry(it)?.entry }
                val canEditSource = source != null && source.deletedAt == null && source.revision == draft.baseRevision && dao.draftForEntry(source.id) == null
                dao.upsertDraft(draft.entity(id, if (canEditSource) source?.id else null))
                dao.insertDraftLinks(draft.assets.mapIndexed { i, asset -> DraftMedia(id, asset, i) })
                dao.putReceipt(RestoreReceipt(draft.id, fingerprint, "draft", id)); draftCount++
            }
        }
        prepared.directory.deleteRecursively()
        progress("恢复完成")
        RestoreResult(imported, conflicts, skipped, draftCount)
    } }

    suspend fun discard(prepared: PreparedRestore) = withContext(Dispatchers.IO) { prepared.directory.deleteRecursively(); Unit }
}
