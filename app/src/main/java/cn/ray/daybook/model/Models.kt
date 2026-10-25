package cn.ray.daybook.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

object Rules {
    const val MAX_PHOTOS = 9
    const val MAX_BODY = 20_000
    const val MAX_TITLE = 80
    const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    fun validate(date: String, minute: Int, title: String, body: String, photoCount: Int, today: LocalDate = LocalDate.now()) {
        val parsed = runCatching { LocalDate.parse(date) }.getOrElse { error("请选择有效日期") }
        require(!parsed.isAfter(today)) { "暂不支持记录未来日期" }
        require(minute in 0..1439) { "请选择有效时间" }
        require(title.length <= MAX_TITLE) { "标题最多 $MAX_TITLE 字" }
        require(body.length <= MAX_BODY) { "正文最多 $MAX_BODY 字" }
        require(photoCount in 0..MAX_PHOTOS) { "每条最多 $MAX_PHOTOS 张图片" }
        require(body.isNotBlank() || photoCount > 0) { "请写下正文或添加图片" }
    }
    fun searchText(title: String, body: String) = "$title\n$body".lowercase(Locale.ROOT)
}

fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
fun timeLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
fun period(minute: Int): String = when (minute / 60) { in 0..5 -> "夜深"; in 6..8 -> "清晨"; in 9..11 -> "上午"; in 12..13 -> "正午"; in 14..17 -> "午后"; else -> "入夜" }

@Serializable
data class RecordPayload(
    val id: String,
    val date: String,
    val minute: Int,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val deletedAt: Long? = null,
    val assets: List<String> = emptyList(),
    val restoredFromId: String? = null,
) {
    fun fingerprint(): String = buildString {
        // Length-prefix fields so arbitrary body text cannot create ambiguous encodings.
        listOf(date, minute.toString(), title, body, deletedAt?.toString().orEmpty()).forEach { append(it.length).append(':').append(it) }
        assets.forEach { append(it.length).append(':').append(it) }
    }.toByteArray(Charsets.UTF_8).sha256()
}

@Serializable
data class DraftPayload(
    val id: String, val sourceEntryId: String? = null, val baseRevision: Long? = null,
    val date: String, val minute: Int, val title: String = "", val body: String = "",
    val updatedAt: Long, val assets: List<String> = emptyList(),
) {
    fun fingerprint() = (RecordPayload(id, date, minute, title, body, 0, 0, assets = assets).fingerprint() + ":" + sourceEntryId.orEmpty() + ":" + baseRevision).toByteArray(Charsets.UTF_8).sha256()
}

@Serializable
data class AssetPayload(val id: String, val sha256: String, val mime: String, val bytes: Long, val width: Int, val height: Int, val path: String)

@Serializable
data class ArchiveFile(val path: String, val sha256: String, val bytes: Long)

@Serializable
data class ArchiveManifest(val formatVersion: Int = 1, val appVersion: String = "0.2.1", val exportedAt: Long, val files: List<ArchiveFile>, val assets: List<AssetPayload>)
