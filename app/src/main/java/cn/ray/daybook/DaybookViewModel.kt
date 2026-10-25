package cn.ray.daybook

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.ray.daybook.data.*
import cn.ray.daybook.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

enum class Page { ALL, TIMELINE, CALENDAR, SETTINGS, SEARCH, TRASH, EDITOR, DETAIL, VIEWER, DRAFTS }
data class EditorUi(val payload: DraftPayload, val assets: List<MediaAsset>, val dirty: Boolean = false, val savedAt: Long = 0)
data class SearchUi(val query: String = "", val from: LocalDate? = null, val until: LocalDate? = null, val results: List<EntryBundle> = emptyList(), val total: Int = 0, val limit: Int = 50, val loading: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class DaybookViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as DaybookApplication
    val repository = app.repository
    private val backup = app.backup
    val page = MutableStateFlow(runCatching { Page.valueOf(saved.get<String>("page") ?: "ALL") }.getOrDefault(Page.ALL))
    val selectedDate = MutableStateFlow(runCatching { LocalDate.parse(saved.get<String>("date")) }.getOrDefault(LocalDate.now()))
    val month = MutableStateFlow(YearMonth.from(selectedDate.value))
    val dayEntries = selectedDate.flatMapLatest(repository::day).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val allLimit = MutableStateFlow(50)
    val allEntries = allLimit.flatMapLatest(repository::timeline).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allScrollToTop = MutableStateFlow(false)
    val monthCounts = month.flatMapLatest(repository::month).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val drafts = repository.drafts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trash = repository.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val count = repository.dao.observeEntryCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val mediaBytes = repository.dao.observeMediaBytes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val settings = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val editor = MutableStateFlow<EditorUi?>(null)
    val detail = MutableStateFlow<EntryBundle?>(null)
    val viewer = MutableStateFlow<List<MediaAsset>>(emptyList())
    val viewerIndex = MutableStateFlow(0)
    val search = MutableStateFlow(SearchUi())
    val busy = MutableStateFlow<String?>(null)
    val canCancelOperation = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val restorePreview = MutableStateFlow<PreparedRestore?>(null)
    private var sourcePage = Page.ALL
    private var detailSourcePage = Page.ALL
    private var viewerSource = Page.DETAIL
    private var draftDebounce: Job? = null
    private var searchJob: Job? = null
    private val editWrites = Mutex()
    private var savingEntry = false
    private var operation: Job? = null

    init {
        viewModelScope.launch { runCatching { repository.cleanup() } }
        if (page.value in listOf(Page.EDITOR, Page.DETAIL, Page.VIEWER)) {
            val draftId = saved.get<String>("draftId")
            page.value = Page.ALL
            if (draftId != null) action { repository.dao.draft(draftId)?.let { setEditor(it); navigate(Page.EDITOR) } }
        }
        viewModelScope.launch {
            while (isActive) { delay(2000); if (editor.value?.dirty == true && !savingEntry && busy.value == null) flushDraft() }
        }
    }
    fun navigate(target: Page) { page.value = target; saved["page"] = target.name }
    fun loadMoreRecords() { allLimit.value = (allLimit.value.toLong() + 50).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    fun acknowledgeAllScroll() { allScrollToTop.value = false }
    fun selectDate(date: LocalDate) { if (date > LocalDate.now()) return; selectedDate.value = date; month.value = YearMonth.from(date); saved["date"] = date.toString() }
    fun changeMonth(delta: Long) { val next = month.value.plusMonths(delta); if (next > YearMonth.now()) return; month.value = next; selectedDate.value = next.atDay(1); saved["date"] = selectedDate.value.toString() }
    fun dismissMessage() { message.value = null }
    fun report(text: String) { message.value = text }

    private fun action(label: String? = null, cancellable: Boolean = false, block: suspend () -> Unit) {
        if (busy.value != null) return
        val job = viewModelScope.launch {
            if (label != null) { busy.value = label; canCancelOperation.value = cancellable }
            try { block() } catch (e: CancellationException) { if (label != null) message.value = "操作已取消；已提交的内容仍保留"; throw e } catch (e: Exception) { message.value = e.message?.take(180) ?: "操作未完成，请重试" } finally { if (label != null) { busy.value = null; canCancelOperation.value = false } }
        }
        if (label != null) operation = job
    }
    fun cancelOperation() { if (canCancelOperation.value) operation?.cancel() }
    fun newEntry() { sourcePage = page.value; action("打开草稿…") { check(flushDraft()) { "原草稿尚未保存，请先处理保存错误" }; val d = repository.startDraft(if (sourcePage == Page.ALL) LocalDate.now() else selectedDate.value); setEditor(d); navigate(Page.EDITOR) } }
    fun editEntry() { val id = detail.value?.entry?.id ?: return; sourcePage = Page.DETAIL; action("打开记录…") { setEditor(repository.startDraft(selectedDate.value, id)); navigate(Page.EDITOR) } }
    fun continueDraft(id: String) { sourcePage = Page.DRAFTS; action("读取草稿…") { setEditor(repository.dao.draft(id) ?: error("草稿已不存在")); navigate(Page.EDITOR) } }
    private fun setEditor(bundle: DraftBundle) { editor.value = EditorUi(bundle.payload(), bundle.orderedAssets(), savedAt = bundle.draft.updatedAt); saved["draftId"] = bundle.draft.id }
    fun changeEditor(transform: (DraftPayload) -> DraftPayload) {
        if (busy.value != null || savingEntry) return
        val current = editor.value ?: return
        val next = transform(current.payload).copy(updatedAt = System.currentTimeMillis())
        if (next.title.length > Rules.MAX_TITLE || next.body.length > Rules.MAX_BODY) { report("标题最多 80 字，正文最多 20000 字"); return }
        editor.value = current.copy(payload = next, dirty = true)
        draftDebounce?.cancel()
        draftDebounce = viewModelScope.launch { delay(500); flushDraft() }
    }
    suspend fun flushDraft(): Boolean = editWrites.withLock {
        val snapshot = editor.value ?: return@withLock true
        if (!snapshot.dirty || savingEntry) return@withLock true
        try {
            repository.writeDraft(snapshot.payload)
            if (editor.value?.payload == snapshot.payload) editor.value = snapshot.copy(dirty = false, savedAt = System.currentTimeMillis())
            true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e.message ?: "草稿保存失败，内容仍保留在页面中"); false }
    }
    fun flushOnBackground() { viewModelScope.launch { flushDraft() } }
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        action("导入图片…") {
            draftDebounce?.cancelAndJoin(); check(flushDraft()) { "草稿保存失败，尚未开始导入图片" }
            val id = editor.value?.payload?.id ?: error("请先打开编辑页")
            try { repository.importImages(id, uris) }
            finally { repository.dao.draft(id)?.let { setEditor(it) } }
        }
    }
    fun removeImage(id: String) { changeEditor { it.copy(assets = it.assets.filterNot { asset -> asset == id }) }; editor.value = editor.value?.let { it.copy(assets = it.assets.filterNot { a -> a.id == id }) } }
    fun saveEntry() {
        action("正在保存…") {
            draftDebounce?.cancelAndJoin()
            check(flushDraft()) { "草稿保存失败，内容仍保留在编辑页" }
            editWrites.withLock {
                val snapshot = editor.value ?: return@withLock
                savingEntry = true
                try {
                    repository.save(snapshot.payload)
                    selectDate(LocalDate.parse(snapshot.payload.date)); editor.value = null; saved["draftId"] = null
                    val destination = if (sourcePage == Page.ALL || sourcePage == Page.DETAIL && detailSourcePage == Page.ALL) Page.ALL else Page.TIMELINE
                    if (destination == Page.ALL) allScrollToTop.value = true
                    navigate(destination); report("已保存")
                } finally { savingEntry = false }
            }
        }
    }
    private fun editorReturnPage() = if (sourcePage == Page.DETAIL && detail.value == null) Page.ALL else sourcePage
    fun discardDraft(id: String) {
        action("丢弃草稿…") {
            draftDebounce?.cancelAndJoin()
            val isCurrentEditor = editor.value?.payload?.id == id
            editWrites.withLock {
                repository.discard(id)
                if (isCurrentEditor) { editor.value = null; saved["draftId"] = null }
            }
            if (page.value == Page.EDITOR && isCurrentEditor) navigate(editorReturnPage())
        }
    }
    fun openEntry(bundle: EntryBundle) { detailSourcePage = page.value; detail.value = bundle; navigate(Page.DETAIL) }
    fun openImages(assets: List<MediaAsset>, index: Int) { viewerSource = page.value; viewer.value = assets; viewerIndex.value = index; navigate(Page.VIEWER) }
    fun back() {
        if (busy.value != null) { report("当前操作处理中，请稍候或取消操作"); return }
        when (page.value) {
            Page.EDITOR -> action { draftDebounce?.cancelAndJoin(); if (flushDraft()) { navigate(editorReturnPage()); report("草稿已保留，可在设置中继续编辑") } }
            Page.DETAIL -> navigate(if (detailSourcePage in listOf(Page.DETAIL, Page.EDITOR)) Page.ALL else detailSourcePage)
            Page.VIEWER -> navigate(viewerSource)
            Page.SEARCH -> navigate(Page.ALL)
            Page.TRASH, Page.DRAFTS -> navigate(Page.SETTINGS)
            else -> navigate(Page.ALL)
        }
    }
    fun deleteEntry(id: String) { action("移入回收站…") { repository.moveToTrash(id); detail.value = null; navigate(if (detailSourcePage == Page.ALL) Page.ALL else Page.TIMELINE); report("已移入回收站，可在设置中恢复") } }
    fun restoreEntry(id: String) { action("恢复记录…") { repository.restore(id); report("已恢复到原日期") } }
    fun permanentDelete(id: String) { action("永久删除…") { repository.permanentlyDelete(id); report("已永久删除") } }
    fun updateSearch(query: String = search.value.query, from: LocalDate? = search.value.from, until: LocalDate? = search.value.until, more: Boolean = false) {
        searchJob?.cancel()
        val limit = if (more) search.value.limit + 50 else 50
        search.value = search.value.copy(query = query, from = from, until = until, limit = limit, loading = true, error = null)
        searchJob = viewModelScope.launch {
            delay(300)
            if (from != null && until != null && from > until) { search.value = search.value.copy(loading = false, error = "开始日期不能晚于结束日期"); return@launch }
            try {
                val q = query.trim().lowercase(Locale.ROOT); val start = from?.toString() ?: "0001-01-01"; val end = until?.toString() ?: "9999-12-31"
                val (results, count) = withContext(Dispatchers.IO) { repository.dao.search(q, start, end, limit) to repository.dao.searchCount(q, start, end) }
                search.value = search.value.copy(results = results, total = count, loading = false)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { search.value = search.value.copy(loading = false, error = "查询失败，请重试") }
        }
    }
    fun appearance(value: String) { action { app.settings.appearance(value) } }
    fun scale(value: Float) { action { app.settings.scale(value) } }
    fun export(uri: Uri) { action("准备备份…", cancellable = true) { draftDebounce?.cancelAndJoin(); check(flushDraft()) { "草稿未保存，尚未开始备份" }; backup.export(uri) { busy.value = it }; report("备份已完成并校验") } }
    fun prepareRestore(uri: Uri) { action("检查备份…", cancellable = true) { draftDebounce?.cancelAndJoin(); check(flushDraft()) { "草稿未保存，尚未开始恢复" }; restorePreview.value?.let { backup.discard(it) }; restorePreview.value = backup.prepare(uri) { busy.value = it } } }
    fun confirmRestore() { val prepared = restorePreview.value ?: return; action("恢复记录…") { val result = backup.restore(prepared) { busy.value = it }; restorePreview.value = null; detail.value = null; editor.value = null; saved["draftId"] = null; allScrollToTop.value = true; report("恢复完成：新增 ${result.imported} 条，冲突副本 ${result.conflicts} 条，跳过 ${result.skipped} 条，草稿 ${result.drafts} 条"); navigate(Page.ALL) } }
    fun cancelRestore() { val p = restorePreview.value ?: return; restorePreview.value = null; viewModelScope.launch { backup.discard(p) } }
}
