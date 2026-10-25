package cn.ray.daybook.data

import cn.ray.daybook.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDate
import java.util.UUID

object ArchiveCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_METADATA_BYTES = 128L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    const val MAX_RECORDS = 50_000
    fun safePath(path: String): Boolean = path.isNotEmpty() && path.length <= 180 && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    fun validateManifest(manifest: ArchiveManifest) {
        require(manifest.formatVersion == 1) { "不支持此备份版本，请使用对应版本的拾光笺" }
        require(manifest.files.size <= 100_002 && manifest.assets.size <= 100_000) { "备份文件数量过多" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size) { "备份包含重复文件路径" }
        val hash = Regex("[0-9a-f]{64}")
        manifest.files.forEach { require(safePath(it.path) && hash.matches(it.sha256) && it.bytes >= 0 && it.bytes <= MAX_ARCHIVE_BYTES) { "备份文件清单无效" } }
        var total = 0L
        manifest.files.forEach { total = Math.addExact(total, it.bytes) }
        require(total <= MAX_ARCHIVE_BYTES) { "备份解压后超过 8 GiB 限制" }
        require(manifest.files.any { it.path == "entries.jsonl" } && manifest.files.any { it.path == "drafts.jsonl" }) { "备份缺少记录文件" }
        require(manifest.assets.map { it.id }.distinct().size == manifest.assets.size) { "重复图片 ID" }
        manifest.assets.forEach { a ->
            require(hash.matches(a.id) && a.id == a.sha256) { "图片标识无效" }
            val ext = when (a.mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> error("不支持备份中的图片格式") }
            require(a.path == "media/${a.id}.$ext" && a.bytes in 1..Rules.MAX_IMAGE_BYTES && a.width > 0 && a.height > 0 && a.width.toLong() * a.height <= 50_000_000) { "图片清单无效" }
            val listed = manifest.files.find { it.path == a.path }
            require(listed?.sha256 == a.sha256 && listed.bytes == a.bytes) { "图片与文件清单不一致" }
        }
        val expected = manifest.assets.map { it.path }.toSet() + setOf("entries.jsonl", "drafts.jsonl")
        require(manifest.files.map { it.path }.toSet() == expected) { "备份存在未知文件" }
    }
    fun validateRecords(entries: List<RecordPayload>, drafts: List<DraftPayload>, assetIds: Set<String>) {
        require(entries.size <= MAX_RECORDS && drafts.size <= MAX_RECORDS) { "备份记录数量过多" }
        require(entries.map { it.id }.distinct().size == entries.size && drafts.map { it.id }.distinct().size == drafts.size) { "重复记录 ID" }
        entries.forEach { e ->
            UUID.fromString(e.id)
            Rules.validate(e.date, e.minute, e.title, e.body, e.assets.size)
            require(e.createdAt >= 0 && e.updatedAt >= 0 && e.revision > 0 && (e.deletedAt == null || e.deletedAt >= 0)) { "事项时间戳无效" }
            require(e.assets.distinct().size == e.assets.size && assetIds.containsAll(e.assets)) { "事项图片关联不完整" }
        }
        drafts.forEach { d ->
            UUID.fromString(d.id)
            LocalDate.parse(d.date)
            require(d.minute in 0..1439 && d.title.length <= Rules.MAX_TITLE && d.body.length <= Rules.MAX_BODY && d.assets.size <= Rules.MAX_PHOTOS && d.updatedAt >= 0) { "草稿字段无效" }
            require(d.assets.distinct().size == d.assets.size && assetIds.containsAll(d.assets)) { "草稿图片关联不完整" }
        }
    }
    inline fun <reified T> writeLines(file: File, items: List<T>) { file.bufferedWriter(Charsets.UTF_8).use { out -> items.forEach { out.write(json.encodeToString(it)); out.newLine() } } }
    inline fun <reified T> readLines(file: File): List<T> {
        require(file.length() <= MAX_METADATA_BYTES) { "记录文件过大" }
        return file.useLines(Charsets.UTF_8) { lines -> lines.filter { it.isNotBlank() }.take(MAX_RECORDS + 1).map { line -> require(line.length <= 200_000) { "记录字段过长" }; json.decodeFromString<T>(line) }.toList() }.also { require(it.size <= MAX_RECORDS) { "记录过多" } }
    }
}
