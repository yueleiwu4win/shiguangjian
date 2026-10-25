package cn.ray.daybook.data

import androidx.room.*
import cn.ray.daybook.model.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "entry", indices = [Index(value = ["entryDate", "deletedAt", "minuteOfDay", "createdAt", "id"]), Index("deletedAt")])
data class EntryEntity(
    @PrimaryKey val id: String,
    val entryDate: String,
    val minuteOfDay: Int,
    val title: String,
    val body: String,
    val searchText: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val deletedAt: Long? = null,
    val restoredFromId: String? = null,
)

@Entity(tableName = "media_asset", indices = [Index(value = ["sha256"], unique = true)])
data class MediaAsset(
    @PrimaryKey val id: String,
    val relativePath: String,
    val sha256: String,
    val mimeType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val createdAt: Long,
)

@Entity(tableName = "entry_media", primaryKeys = ["entryId", "assetId"],
    indices = [Index("assetId"), Index(value = ["entryId", "sortOrder"], unique = true)],
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaAsset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT)])
data class EntryMedia(val entryId: String, val assetId: String, val sortOrder: Int)

@Entity(tableName = "draft", indices = [Index(value = ["sourceEntryId"], unique = true)])
data class DraftEntity(
    @PrimaryKey val id: String,
    val sourceEntryId: String? = null,
    val baseRevision: Long? = null,
    val entryDate: String,
    val minuteOfDay: Int,
    val title: String = "",
    val body: String = "",
    val updatedAt: Long,
)

@Entity(tableName = "draft_media", primaryKeys = ["draftId", "assetId"],
    indices = [Index("assetId"), Index(value = ["draftId", "sortOrder"], unique = true)],
    foreignKeys = [ForeignKey(entity = DraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaAsset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT)])
data class DraftMedia(val draftId: String, val assetId: String, val sortOrder: Int)

@Entity(tableName = "restore_receipt", primaryKeys = ["sourceRecordId", "fingerprint", "recordType"])
data class RestoreReceipt(val sourceRecordId: String, val fingerprint: String, val recordType: String, val localRecordId: String)

data class EntryBundle(
    @Embedded val entry: EntryEntity,
    @Relation(parentColumn = "id", entityColumn = "entryId") val links: List<EntryMedia>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(EntryMedia::class, parentColumn = "entryId", entityColumn = "assetId")) val assets: List<MediaAsset>,
) {
    fun orderedAssets() = links.sortedBy { it.sortOrder }.mapNotNull { link -> assets.find { it.id == link.assetId } }
    fun payload() = entry.let { RecordPayload(it.id, it.entryDate, it.minuteOfDay, it.title, it.body, it.createdAt, it.updatedAt, it.revision, it.deletedAt, links.sortedBy { l -> l.sortOrder }.map { l -> l.assetId }, it.restoredFromId) }
}

data class DraftBundle(
    @Embedded val draft: DraftEntity,
    @Relation(parentColumn = "id", entityColumn = "draftId") val links: List<DraftMedia>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(DraftMedia::class, parentColumn = "draftId", entityColumn = "assetId")) val assets: List<MediaAsset>,
) {
    fun orderedAssets() = links.sortedBy { it.sortOrder }.mapNotNull { link -> assets.find { it.id == link.assetId } }
    fun payload() = draft.let { DraftPayload(it.id, it.sourceEntryId, it.baseRevision, it.entryDate, it.minuteOfDay, it.title, it.body, it.updatedAt, links.sortedBy { l -> l.sortOrder }.map { l -> l.assetId }) }
}
data class DayCount(val entryDate: String, val count: Int)

@Dao
interface DaybookDao {
    @Transaction @Query("SELECT * FROM entry WHERE entryDate=:date AND deletedAt IS NULL ORDER BY minuteOfDay DESC, createdAt DESC, id DESC")
    fun observeDay(date: String): Flow<List<EntryBundle>>
    @Transaction @Query("SELECT * FROM entry WHERE deletedAt IS NULL ORDER BY entryDate DESC, minuteOfDay DESC, createdAt DESC, id DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<EntryBundle>>
    @Query("SELECT entryDate, COUNT(*) as count FROM entry WHERE entryDate>=:from AND entryDate<:until AND deletedAt IS NULL GROUP BY entryDate")
    fun observeCounts(from: String, until: String): Flow<List<DayCount>>
    @Transaction @Query("SELECT * FROM entry WHERE deletedAt IS NULL AND instr(searchText,:query)>0 AND entryDate>=:from AND entryDate<=:until ORDER BY entryDate DESC,minuteOfDay DESC,createdAt DESC,id DESC LIMIT :limit")
    suspend fun search(query: String, from: String, until: String, limit: Int): List<EntryBundle>
    @Query("SELECT COUNT(*) FROM entry WHERE deletedAt IS NULL AND instr(searchText,:query)>0 AND entryDate>=:from AND entryDate<=:until")
    suspend fun searchCount(query: String, from: String, until: String): Int
    @Transaction @Query("SELECT * FROM entry WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<EntryBundle>>
    @Transaction @Query("SELECT * FROM entry WHERE id=:id") suspend fun entry(id: String): EntryBundle?
    @Transaction @Query("SELECT * FROM entry ORDER BY id") suspend fun allEntries(): List<EntryBundle>
    @Transaction @Query("SELECT * FROM draft ORDER BY updatedAt DESC") fun observeDrafts(): Flow<List<DraftBundle>>
    @Transaction @Query("SELECT * FROM draft ORDER BY updatedAt DESC") suspend fun allDrafts(): List<DraftBundle>
    @Transaction @Query("SELECT * FROM draft WHERE id=:id") suspend fun draft(id: String): DraftBundle?
    @Transaction @Query("SELECT * FROM draft WHERE sourceEntryId=:id") suspend fun draftForEntry(id: String): DraftBundle?
    @Query("SELECT * FROM media_asset WHERE id=:id") suspend fun asset(id: String): MediaAsset?
    @Query("SELECT * FROM media_asset") suspend fun allAssets(): List<MediaAsset>
    @Query("SELECT * FROM media_asset WHERE id NOT IN (SELECT assetId FROM entry_media) AND id NOT IN (SELECT assetId FROM draft_media) AND createdAt<:before") suspend fun unreferenced(before: Long): List<MediaAsset>
    @Query("SELECT * FROM restore_receipt WHERE sourceRecordId=:id AND fingerprint=:fingerprint AND recordType=:type") suspend fun receipt(id: String, fingerprint: String, type: String): RestoreReceipt?
    @Query("SELECT COUNT(*) FROM entry WHERE deletedAt IS NULL") fun observeEntryCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(bytes),0) FROM media_asset") fun observeMediaBytes(): Flow<Long>
    @Upsert suspend fun upsertEntry(entry: EntryEntity)
    @Upsert suspend fun upsertDraft(draft: DraftEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAsset(asset: MediaAsset)
    @Insert suspend fun insertEntryLinks(links: List<EntryMedia>)
    @Insert suspend fun insertDraftLinks(links: List<DraftMedia>)
    @Upsert suspend fun putReceipt(receipt: RestoreReceipt)
    @Query("DELETE FROM entry_media WHERE entryId=:id") suspend fun clearEntryLinks(id: String)
    @Query("DELETE FROM draft_media WHERE draftId=:id") suspend fun clearDraftLinks(id: String)
    @Query("DELETE FROM draft WHERE id=:id") suspend fun deleteDraft(id: String)
    @Query("DELETE FROM entry WHERE id=:id") suspend fun deleteEntry(id: String)
    @Query("DELETE FROM media_asset WHERE id=:id") suspend fun deleteAsset(id: String)
}

@Database(entities = [EntryEntity::class, MediaAsset::class, EntryMedia::class, DraftEntity::class, DraftMedia::class, RestoreReceipt::class], version = 1, exportSchema = true)
abstract class DaybookDatabase : RoomDatabase() { abstract fun dao(): DaybookDao }

fun RecordPayload.entity(idOverride: String = id, restoredFrom: String? = restoredFromId) = EntryEntity(idOverride, date, minute, title, body, Rules.searchText(title, body), createdAt, updatedAt, revision, deletedAt, restoredFrom)
fun DraftPayload.entity(idOverride: String = id, sourceOverride: String? = sourceEntryId) = DraftEntity(idOverride, sourceOverride, baseRevision, date, minute, title, body, updatedAt)
