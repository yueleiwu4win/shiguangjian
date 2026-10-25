@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.ray.daybook.ui

import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import cn.ray.daybook.*
import cn.ray.daybook.data.*
import cn.ray.daybook.model.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DaybookApp(vm: DaybookViewModel) {
    val page by vm.page.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val canCancel by vm.canCancelOperation.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preview by vm.restorePreview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pageStates = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.flushOnBackground() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Short); vm.dismissMessage() } }
    BackHandler(enabled = page != Page.ALL || busy != null) { vm.back() }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
            vm.addImages(uris)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let(vm::export)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let(vm::prepareRestore)
    }
    var confirmExport by remember { mutableStateOf(false) }
    val primaryPages = listOf(Page.ALL, Page.TIMELINE, Page.CALENDAR, Page.SETTINGS)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(when (page) { Page.ALL, Page.TIMELINE -> "拾光笺"; Page.CALENDAR -> "日历"; Page.EDITOR -> "记一笔"; Page.DETAIL -> "此刻小记"; Page.SEARCH -> "搜索"; Page.SETTINGS -> "设置"; Page.TRASH -> "回收站"; Page.VIEWER -> "查看图片"; Page.DRAFTS -> "草稿" }, fontFamily = BookFont, fontSize = 23.sp, letterSpacing = 3.sp) },
                navigationIcon = { if (page !in primaryPages) IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (page in listOf(Page.ALL, Page.TIMELINE, Page.CALENDAR)) {
                        IconButton(onClick = { vm.updateSearch(); vm.navigate(Page.SEARCH) }, enabled = busy == null) { Icon(Icons.Outlined.Search, "搜索记录") }
                        TextButton(onClick = vm::newEntry, enabled = busy == null) { Icon(Icons.Outlined.EditNote, null); Text("记一笔") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (page in primaryPages) NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(Triple(Page.ALL, "全部", Icons.Outlined.AutoStories), Triple(Page.TIMELINE, "按日", Icons.Outlined.HistoryEdu), Triple(Page.CALENDAR, "日历", Icons.Outlined.CalendarMonth), Triple(Page.SETTINGS, "设置", Icons.Outlined.Tune)).forEach { (destination, label, icon) ->
                    NavigationBarItem(selected = page == destination, onClick = { vm.navigate(destination) }, icon = { Icon(icon, null) }, label = { Text(label, fontFamily = BookFont) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Reopen photos at the clicked image; only other pages retain navigation state.
            if (page == Page.VIEWER) ViewerScreen(vm)
            else pageStates.SaveableStateProvider(page.name) { when (page) {
                Page.ALL -> AllRecordsScreen(vm)
                Page.TIMELINE -> TimelineScreen(vm)
                Page.CALENDAR -> CalendarScreen(vm)
                Page.EDITOR -> EditorScreen(vm) { images.launch(localDocumentIntent("image/*", multiple = true)) }
                Page.DETAIL -> DetailScreen(vm)
                Page.SEARCH -> SearchScreen(vm)
                Page.SETTINGS -> SettingsScreen(vm, onExport = { confirmExport = true }, onRestore = { restore.launch(localDocumentIntent("*/*")) })
                Page.TRASH -> TrashScreen(vm)
                Page.VIEWER -> Unit
                Page.DRAFTS -> DraftsScreen(vm)
            } }
        }
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("导出本地备份") }, text = { Text("包含全部文字、原图片、草稿和回收站。备份文件未加密，请选择你管理的本机或外部存储位置。") }, confirmButton = { TextButton(onClick = {
        confirmExport = false
        export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_LOCAL_ONLY, true); putExtra(Intent.EXTRA_TITLE, "拾光笺-${LocalDate.now()}-${System.currentTimeMillis()}.daybook") })
    }) { Text("选择位置") } }, dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } })
    preview?.let { prepared ->
        val p = prepared.preview
        AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("确认恢复") }, text = { Text("可新增 ${p.newEntries} 条\n可跳过 ${p.skippedEntries} 条\n冲突副本 ${p.conflictingEntries} 条\n备份含回收站事项 ${p.trashEntries} 条、草稿 ${p.draftCount} 条\n数据量 ${sizeLabel(p.bytes)}\n\n现有内容保留，最终按提交时的数据重新核对。") }, confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("合并恢复") } }, dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("取消") } })
    }
    if (busy != null) Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(busy.orEmpty(), modifier = Modifier.padding(top = 20.dp), fontSize = 14.sp)
                Text("请保持应用开启", modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canCancel) TextButton(onClick = vm::cancelOperation, modifier = Modifier.padding(top = 12.dp)) { Text("取消操作") }
            }
        }
    }
}

private fun localDocumentIntent(mime: String, multiple: Boolean = false) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_LOCAL_ONLY, true); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
}

@Composable
private fun AllRecordsScreen(vm: DaybookViewModel) {
    val entries by vm.allEntries.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val scrollToTop by vm.allScrollToTop.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LaunchedEffect(scrollToTop) { if (scrollToTop) { state.scrollToItem(0); vm.acknowledgeAllScroll() } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("all-records-list"), state = state) {
        item(key = "overview-heading") {
            BookHeading()
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("共 $count 条记录", fontFamily = BookFont, fontSize = 18.sp)
                Text("由近及远", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entries.isEmpty()) item(key = "empty") {
            if (count == 0) EmptyState("尚无旧事", "生活里的大小事，随时都可以记。", "记一笔", vm::newEntry)
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 30.dp))
        }
        itemsIndexed(entries, key = { _, entry -> entry.entry.id }) { i, bundle ->
            val date = bundle.entry.entryDate
            val first = i == 0 || entries[i - 1].entry.entryDate != date
            val last = i == entries.lastIndex || entries[i + 1].entry.entryDate != date
            if (first) {
                Row(Modifier.fillMaxWidth().clickable { vm.selectDate(LocalDate.parse(date)); vm.navigate(Page.TIMELINE) }.semantics { contentDescription = "查看${date}当天记录"; role = Role.Button }.padding(top = 20.dp, bottom = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("yyyy年M月d日")), fontFamily = BookFont, fontSize = 19.sp)
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            TimelineItem(bundle, first, last, false, vm.repository.media, { vm.openEntry(bundle) }, { vm.openImages(bundle.orderedAssets(), it) })
        }
        if (entries.isNotEmpty()) item(key = "overview-footer") {
            if (entries.size < count) {
                TextButton(onClick = vm::loadMoreRecords, modifier = Modifier.fillMaxWidth()) { Text("加载更早的记录") }
                Text("已显示 ${entries.size} / $count 条", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else Text("— 光阴都收在这里 —", fontFamily = BookFont, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun TimelineScreen(vm: DaybookViewModel) {
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val loadedEntries by vm.dayEntries.collectAsStateWithLifecycle()
    val entries = loadedEntries.filter { it.entry.entryDate == date.toString() }
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = state) {
        item { BookHeading(); DateBar(date, vm::selectDate) }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${date.monthValue}月${date.dayOfMonth}日 · ${entries.size} 则", fontFamily = BookFont, fontSize = 18.sp)
                Text("由近及远", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entries.isEmpty()) item { EmptyState("此日尚无记", "记下一件小事，留住一段光阴。", "记一笔", vm::newEntry) }
        itemsIndexed(entries, key = { _, e -> e.entry.id }) { i, bundle -> TimelineItem(bundle, i == 0, i == entries.lastIndex, false, vm.repository.media, { vm.openEntry(bundle) }, { vm.openImages(bundle.orderedAssets(), it) }) }
        item {
            if (drafts.isNotEmpty()) TextButton(onClick = { vm.navigate(Page.DRAFTS) }, modifier = Modifier.fillMaxWidth()) { Text("${drafts.size} 份草稿，继续写") }
            if (entries.isNotEmpty()) Text("— 此日记于此 —", fontFamily = BookFont, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun CalendarScreen(vm: DaybookViewModel) {
    val month by vm.month.collectAsStateWithLifecycle()
    val selected by vm.selectedDate.collectAsStateWithLifecycle()
    val counts by vm.monthCounts.collectAsStateWithLifecycle()
    val loadedEntries by vm.dayEntries.collectAsStateWithLifecycle()
    val entries = loadedEntries.filter { it.entry.entryDate == selected.toString() }
    val map = counts.associate { it.entryDate to it.count }
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { vm.changeMonth(-1) }) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "上个月") }
                Text("${month.year}年${month.monthValue}月", fontFamily = BookFont, fontSize = 23.sp)
                IconButton(onClick = { vm.changeMonth(1) }, enabled = month < YearMonth.now()) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "下个月") }
            }
            Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f).padding(vertical = 9.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = colors.onSurfaceVariant, fontSize = 12.sp) } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val cells = List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        val chosen = date == selected
                        val enabled = date != null && date <= LocalDate.now()
                        Column(Modifier.weight(1f).heightIn(min = 55.dp).background(if (chosen) colors.primary else colors.background, RoundedCornerShape(4.dp)).clickable(enabled = enabled) { vm.selectDate(date!!) }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date?.dayOfMonth?.toString().orEmpty(), fontSize = 14.sp, color = if (chosen) colors.onPrimary else if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = .45f))
                            Text(if (date == LocalDate.now()) "今天" else map[date?.toString()]?.let { "$it 则" }.orEmpty(), fontSize = 10.sp, color = if (chosen) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.heightIn(min = 16.dp))
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            DateBar(selected, vm::selectDate)
            HorizontalDivider(color = colors.outlineVariant)
        }
        if (entries.isEmpty()) item { EmptyState("此日尚无记", "也可以补记过去的时光。", "记一笔", vm::newEntry) }
        itemsIndexed(entries, key = { _, e -> e.entry.id }) { i, bundle -> TimelineItem(bundle, i == 0, i == entries.lastIndex, false, vm.repository.media, { vm.openEntry(bundle) }, { vm.openImages(bundle.orderedAssets(), it) }) }
    }
}

@Composable
private fun EditorScreen(vm: DaybookViewModel, addPhotos: () -> Unit) {
    val editor by vm.editor.collectAsStateWithLifecycle()
    val state = editor ?: return
    val d = state.payload
    val context = LocalContext.current
    var discard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::back) { Text("返回") }
            Text(if (state.dirty) "正在保存草稿…" else "草稿已保存", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = vm::saveEntry, shape = RoundedCornerShape(4.dp)) { Text("保存") }
        }
        TextField(value = d.body, onValueChange = { value -> vm.changeEditor { it.copy(body = value) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).semantics { contentDescription = "记录正文" }, placeholder = { Text("这一刻，想记点什么…", fontFamily = BookFont) }, textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, lineHeight = 32.sp, fontFamily = BookFont), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.background, unfocusedContainerColor = MaterialTheme.colorScheme.background, focusedIndicatorColor = MaterialTheme.colorScheme.background, unfocusedIndicatorColor = MaterialTheme.colorScheme.background))
        if (state.assets.isNotEmpty()) PhotoGrid(state.assets, vm.repository.media) { vm.openImages(state.assets, it) }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = addPhotos, enabled = state.assets.size < Rules.MAX_PHOTOS, shape = RoundedCornerShape(4.dp)) { Icon(Icons.Outlined.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("添加图片") }
            Text("${state.assets.size} / 9 张", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.assets.forEachIndexed { index, asset ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("图片 ${index + 1} · ${sizeLabel(asset.bytes)}", fontSize = 12.sp); IconButton(onClick = { vm.removeImage(asset.id) }) { Icon(Icons.Outlined.Close, "移除图片 ${index + 1}") } }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showDatePicker(context, LocalDate.parse(d.date)) { date -> vm.changeEditor { it.copy(date = date.toString()) } } }, modifier = Modifier.weight(1.5f), shape = RoundedCornerShape(4.dp)) { Text(d.date, fontSize = 13.sp) }
            OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, minute -> vm.changeEditor { it.copy(minute = hour * 60 + minute) } }, d.minute / 60, d.minute % 60, true).show() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { Text(timeLabel(d.minute)) }
        }
        OutlinedTextField(value = d.title, onValueChange = { value -> vm.changeEditor { it.copy(title = value) } }, label = { Text("标题 · 选填") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp)); Text("  仅自己可见 · 保存在本机", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { discard = true }, modifier = Modifier.align(Alignment.End)) { Text("丢弃草稿", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(20.dp))
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("丢弃草稿？") }, text = { Text("这份尚未保存的修改会被移除，已经保存的正式记录不受影响。") }, confirmButton = { TextButton(onClick = { discard = false; vm.discardDraft(d.id) }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable
private fun DetailScreen(vm: DaybookViewModel) {
    val detail by vm.detail.collectAsStateWithLifecycle()
    val bundle = detail ?: return
    val e = bundle.entry
    var delete by remember(e.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(e.title.ifBlank { "随手小记" }, fontFamily = BookFont, fontSize = 26.sp, lineHeight = 38.sp)
        Text("${e.entryDate} · ${timeLabel(e.minuteOfDay)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        if (e.restoredFromId != null) Text("恢复副本", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        Text(e.body, fontSize = 16.sp, lineHeight = 30.sp)
        PhotoGrid(bundle.orderedAssets(), vm.repository.media) { vm.openImages(bundle.orderedAssets(), it) }
        Text("仅自己可见", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::editEntry, shape = RoundedCornerShape(4.dp)) { Text("编辑") }
            OutlinedButton(onClick = { delete = true }, shape = RoundedCornerShape(4.dp)) { Text("移入回收站") }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("移入回收站？") }, text = { Text("之后可在设置的回收站中恢复。") }, confirmButton = { TextButton(onClick = { delete = false; vm.deleteEntry(e.id) }) { Text("移入") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable
private fun SearchScreen(vm: DaybookViewModel) {
    val state by vm.search.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            OutlinedTextField(value = state.query, onValueChange = { vm.updateSearch(query = it) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("搜索标题或正文") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker(context, state.from ?: LocalDate.now()) { vm.updateSearch(from = it) } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { Text(state.from?.toString() ?: "开始日期", fontSize = 12.sp) }
                OutlinedButton(onClick = { showDatePicker(context, state.until ?: LocalDate.now()) { vm.updateSearch(until = it) } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { Text(state.until?.toString() ?: "结束日期", fontSize = 12.sp) }
            }
            if (state.from != null || state.until != null) TextButton(onClick = { vm.updateSearch(from = null, until = null) }) { Text("清空日期范围") }
            Text(state.error ?: if (state.loading) "正在查找…" else "${state.total} 条匹配记录", fontSize = 12.sp, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.loading && state.results.isEmpty()) item { EmptyState("未寻得此记", "换个关键词，或调整日期范围。") }
        itemsIndexed(state.results, key = { _, b -> b.entry.id }) { i, b -> TimelineItem(b, i == 0, i == state.results.lastIndex, true, vm.repository.media, { vm.openEntry(b) }, { vm.openImages(b.orderedAssets(), it) }) }
        if (state.results.size < state.total) item { TextButton(onClick = { vm.updateSearch(more = true) }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
    }
}

@Composable
private fun SettingsScreen(vm: DaybookViewModel, onExport: () -> Unit, onRestore: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val bytes by vm.mediaBytes.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var license by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("留住每一日", fontFamily = BookFont, fontSize = 28.sp, modifier = Modifier.padding(vertical = 16.dp))
        Text("$count 条记录 · 图片占用 ${sizeLabel(bytes)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSection("数据管理") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = onExport, shape = RoundedCornerShape(4.dp)) { Text("导出备份") }; OutlinedButton(onClick = onRestore, shape = RoundedCornerShape(4.dp)) { Text("恢复备份") } }
            Text(if (settings.lastBackupAt == 0L) "尚未成功导出备份" else "最近成功备份：" + Instant.ofEpochMilli(settings.lastBackupAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            Text("卸载应用会删除本机内容。请把备份保存在应用数据之外。", fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { vm.navigate(Page.DRAFTS) }) { Text("草稿 · ${drafts.size}") }
            TextButton(onClick = { vm.navigate(Page.TRASH) }) { Text("回收站 · ${trash.size}") }
        }
        SettingSection("显示设置") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) -> FilterChip(selected = settings.appearance == value, onClick = { vm.appearance(value) }, label = { Text(label, fontSize = 12.sp) }) }
            }
            Text("字号：${(settings.textScale * 100).toInt()}%", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            var slider by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
            Slider(value = slider, onValueChange = { slider = it }, onValueChangeFinished = { vm.scale(slider) }, valueRange = .9f..1.4f, steps = 4)
        }
        SettingSection("关于拾光笺") {
            Text("0.2.1 · 完全离线的私人事记", fontSize = 13.sp)
            Text("标题字体：Noto Serif SC（SIL OFL）\n记录保存在本机，首版未提供内容加密。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp))
            TextButton(onClick = { license = context.assets.open("licenses/NotoSerifSC-OFL.txt").bufferedReader().use { it.readText() } }) { Text("字体许可") }
        }
    }
    license?.let { text -> AlertDialog(onDismissRequest = { license = null }, title = { Text("SIL Open Font License") }, text = { Text(text, modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { license = null }) { Text("关闭") } }) }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 25.dp, bottom = 12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(title, fontFamily = BookFont, fontSize = 20.sp, modifier = Modifier.padding(vertical = 16.dp))
        content()
    }
}

@Composable
private fun TrashScreen(vm: DaybookViewModel) {
    val entries by vm.trash.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        item { Text("记录会保留在这里，直到你选择永久删除。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp)) }
        if (entries.isEmpty()) item { EmptyState("回收站为空", "没有已删除的记录。") }
        itemsIndexed(entries, key = { _, b -> b.entry.id }) { _, bundle ->
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Text(bundle.entry.title.ifBlank { bundle.entry.body.take(40).ifBlank { "图片随记" } }, fontFamily = BookFont, fontSize = 20.sp)
                Text("${bundle.entry.entryDate} · ${bundle.assets.size} 张图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row { TextButton(onClick = { vm.restoreEntry(bundle.entry.id) }) { Text("恢复") }; TextButton(onClick = { deleting = bundle.entry.id }) { Text("永久删除", color = MaterialTheme.colorScheme.error) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("永久删除？") }, text = { Text("本条记录将无法从回收站恢复。仍被其他记录或草稿使用的图片会保留。") }, confirmButton = { TextButton(onClick = { deleting = null; vm.permanentDelete(id) }) { Text("永久删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun DraftsScreen(vm: DaybookViewModel) {
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    var discard by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        if (drafts.isEmpty()) item { EmptyState("暂无草稿", "未写完的记录会保存在这里。") }
        itemsIndexed(drafts, key = { _, b -> b.draft.id }) { _, bundle ->
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                Text(bundle.draft.title.ifBlank { bundle.draft.body.take(35).ifBlank { "未命名草稿" } }, fontFamily = BookFont, fontSize = 20.sp)
                Text("${bundle.draft.entryDate} · ${bundle.assets.size} 张图片" + if (bundle.draft.sourceEntryId != null) " · 编辑草稿" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row { TextButton(onClick = { vm.continueDraft(bundle.draft.id) }) { Text("继续编辑") }; TextButton(onClick = { discard = bundle.draft.id }) { Text("丢弃", color = MaterialTheme.colorScheme.error) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    discard?.let { id -> AlertDialog(onDismissRequest = { discard = null }, title = { Text("丢弃这份草稿？") }, text = { Text("尚未保存的修改将被移除。") }, confirmButton = { TextButton(onClick = { discard = null; vm.discardDraft(id) }) { Text("丢弃") } }, dismissButton = { TextButton(onClick = { discard = null }) { Text("取消") } }) }
}

@Composable
private fun ViewerScreen(vm: DaybookViewModel) {
    val assets by vm.viewer.collectAsStateWithLifecycle()
    val index by vm.viewerIndex.collectAsStateWithLifecycle()
    if (assets.isEmpty()) return
    val pager = rememberPagerState(initialPage = index.coerceIn(assets.indices), pageCount = { assets.size })
    Column(Modifier.fillMaxSize()) {
        Text("${pager.currentPage + 1} / ${assets.size} · 双指缩放，左右切换", Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = true) { page ->
            var zoom by remember(assets[page].id) { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }; var offsetY by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().clipToBounds().pointerInput(assets[page].id) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2 || zoom > 1f) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                            val pan = event.calculatePan()
                            if (zoom > 1f) { offsetX = (offsetX + pan.x).coerceIn(-size.width.toFloat(), size.width.toFloat()); offsetY = (offsetY + pan.y).coerceIn(-size.height.toFloat(), size.height.toFloat()) } else { offsetX = 0f; offsetY = 0f }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }, contentAlignment = Alignment.Center) {
                AsyncImage(model = vm.repository.media.original(assets[page]), contentDescription = "图片 ${page + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offsetX; translationY = offsetY })
            }
        }
    }
}
