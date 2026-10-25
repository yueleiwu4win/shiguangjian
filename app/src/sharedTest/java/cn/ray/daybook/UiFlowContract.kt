package cn.ray.daybook

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assert.assertEquals

abstract class UiFlowContract {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun isolateTestData() {
        compose.runOnIdle {
            val database = (compose.activity.application as DaybookApplication).database
            runBlocking { withContext(Dispatchers.IO) { database.clearAllTables() } }
        }
    }
    @Test fun createRecordAndFindItInSearch() {
        val text = "散步回来，泡了一壶茶。\n把今天的小事，慢慢记下来。"
        compose.runOnIdle {
            runBlocking {
                val repo = (compose.activity.application as DaybookApplication).repository
                listOf(450 to "晨起煮茶", 560 to "一卷闲书").forEach { (minute, title) ->
                    val draft = repo.startDraft(LocalDate.now()).payload().copy(minute = minute, title = title, body = if (minute == 450) "烧水，开窗，把今天想做的事慢慢理一遍。" else "给喜欢的事情，留一点时间。")
                    repo.save(draft)
                }
            }
        }
        compose.onAllNodesWithText("记一笔").onFirst().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("记录正文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("记录正文").performTextInput(text)
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
        System.getProperty("daybook.screenshotDir")?.let { directory ->
            screenshot(directory, "timeline.png")
        }
        compose.onNodeWithContentDescription("搜索记录").performClick()
        compose.onNodeWithText("搜索标题或正文").performTextInput("泡了一壶茶")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 条匹配记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertExists()
    }

    @Test fun overviewIncludesPastDatesAndReturnsToTheSameList() {
        val historical = LocalDate.now().minusDays(3)
        compose.runOnIdle { runBlocking {
            val repo = (compose.activity.application as DaybookApplication).repository
            listOf(LocalDate.now() to "今日小记", historical to "三日前的旧事").forEach { (date, title) ->
                repo.save(repo.startDraft(date).payload().copy(title = title, body = "在全部记录里，也能找到这段时光。"))
            }
        } }
        compose.onNodeWithText("拾光笺").assertExists()
        compose.onNodeWithText("日常拾记").assertExists()
        compose.onNodeWithText("全部").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("共 2 条记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("all-records-list").performScrollToNode(hasText("三日前的旧事"))
        compose.onNodeWithText("三日前的旧事").performClick()
        compose.onNodeWithText("此刻小记").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("三日前的旧事").assertIsDisplayed()
        System.getProperty("daybook.screenshotDir")?.let { screenshot(it, "all-records.png") }
        compose.onNodeWithContentDescription("查看${historical}当天记录").performClick()
        compose.onNodeWithText("${historical.monthValue}月${historical.dayOfMonth}日 · 1 则").assertExists()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText("三日前的旧事").assertIsDisplayed()
    }

    @Test fun overviewLoadsOlderRecordsBeyondFirstBatch() {
        compose.runOnIdle { runBlocking {
            val repo = (compose.activity.application as DaybookApplication).repository
            repeat(51) { i -> repo.save(repo.startDraft(LocalDate.of(2020, 1, 1)).payload().copy(minute = i, body = "批次记录 $i")) }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("共 51 条记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("all-records-list").performScrollToNode(hasText("加载更早的记录"))
        compose.onNodeWithText("加载更早的记录").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("加载更早的记录").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("all-records-list").performScrollToNode(hasText("批次记录 0"))
        compose.onNodeWithText("批次记录 0").assertIsDisplayed()
    }

    @Test fun reopeningImagesStartsAtTheClickedPhoto() {
        val assets = compose.runOnIdle {
            val context = compose.activity
            val media = (context.application as DaybookApplication).repository.media
            (1..2).map { index ->
                val source = File.createTempFile("gallery-$index-", ".png", context.cacheDir)
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(if (index == 1) android.graphics.Color.RED else android.graphics.Color.BLUE)
                    source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    runBlocking { media.import(Uri.fromFile(source)) }
                } finally { bitmap.recycle(); source.delete() }
            }
        }
        fun open(index: Int, photos: List<cn.ray.daybook.data.MediaAsset> = assets) {
            compose.runOnIdle { model().openImages(photos, index) }
            compose.onNodeWithText("${index + 1} / ${photos.size} · 双指缩放，左右切换").assertExists()
            compose.onNodeWithContentDescription("图片 ${index + 1}").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
        }
        open(0)
        open(1)
        open(0)
        open(0, assets.takeLast(1))
        open(1)
    }

    @Test fun discardingNewDraftReturnsToOverviewAtTheSamePosition() {
        compose.runOnIdle { runBlocking {
            val repo = (compose.activity.application as DaybookApplication).repository
            repeat(10) { index ->
                repo.save(repo.startDraft(LocalDate.now()).payload().copy(minute = index, title = "返回位置 $index", body = "保留全部列表的阅读位置。\n再写一行。\n还有一行。"))
            }
            model().selectDate(LocalDate.of(2020, 1, 1))
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("共 10 条记录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("all-records-list").performScrollToNode(hasText("返回位置 0"))
        compose.onAllNodesWithText("记一笔").onFirst().performClick()
        waitForEditor()
        compose.runOnIdle { assertEquals(LocalDate.now().toString(), model().editor.value!!.payload.date) }
        discardEditor()
        compose.runOnIdle { assertEquals(Page.ALL, model().page.value) }
        compose.onNodeWithText("返回位置 0").assertIsDisplayed()
        compose.runOnIdle { runBlocking {
            assertEquals(10, model().repository.dao.allEntries().size)
            assertEquals(0, model().repository.dao.allDrafts().size)
        } }
    }

    @Test fun discardingDraftReturnsToDayCalendarDraftsOrDetail() {
        for (origin in listOf(Page.TIMELINE, Page.CALENDAR)) {
            compose.runOnIdle { model().navigate(origin); model().newEntry() }
            waitForEditor()
            discardEditor()
            compose.runOnIdle { assertEquals(origin, model().page.value) }
        }
        compose.runOnIdle { runBlocking {
            val draft = model().repository.startDraft(LocalDate.now())
            model().navigate(Page.DRAFTS)
            model().continueDraft(draft.draft.id)
        } }
        waitForEditor()
        discardEditor()
        compose.runOnIdle { assertEquals(Page.DRAFTS, model().page.value) }
        compose.runOnIdle { runBlocking {
            val repo = model().repository
            val id = repo.save(repo.startDraft(LocalDate.now()).payload().copy(body = "正式记录原文"))
            model().navigate(Page.ALL)
            model().openEntry(repo.dao.entry(id)!!)
            model().editEntry()
        } }
        waitForEditor()
        compose.onNodeWithContentDescription("记录正文").performTextReplacement("应当丢弃的修改")
        discardEditor()
        compose.runOnIdle { assertEquals(Page.DETAIL, model().page.value) }
        compose.onNodeWithText("正式记录原文").assertIsDisplayed()
        compose.runOnIdle { runBlocking {
            val repo = model().repository
            assertEquals("正式记录原文", repo.dao.entry(model().detail.value!!.entry.id)!!.entry.body)
            assertEquals(0, repo.dao.allDrafts().size)
        } }
    }

    private fun model() = ViewModelProvider(compose.activity)[DaybookViewModel::class.java]
    private fun waitForEditor() {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("记录正文").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun discardEditor() {
        compose.onNodeWithText("丢弃草稿").performScrollTo().performClick()
        compose.onNodeWithText("丢弃", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("记录正文").fetchSemanticsNodes().isEmpty() }
    }

    private fun screenshot(directory: String, name: String) {
        val file = File(directory, name).apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try { view.draw(android.graphics.Canvas(bitmap)); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
}
