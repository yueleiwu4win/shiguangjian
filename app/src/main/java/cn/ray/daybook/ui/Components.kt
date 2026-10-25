@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.ray.daybook.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cn.ray.daybook.data.*
import cn.ray.daybook.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun showDatePicker(context: android.content.Context, selected: LocalDate, onDate: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
        datePicker.maxDate = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        show()
    }
}

@Composable
fun BookHeading() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("私 人 手 记", fontSize = 11.sp, letterSpacing = 3.sp, color = colors.onSurfaceVariant)
            Text("日常拾记", fontFamily = BookFont, fontSize = 31.sp, letterSpacing = 5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Text("随手记下，皆是光阴。", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        Box(Modifier.padding(end = 8.dp).border(1.dp, colors.primary).padding(3.dp).border(.5.dp, colors.primary).padding(horizontal = 6.dp, vertical = 9.dp).clearAndSetSemantics { }) {
            Text("拾\n光", fontFamily = BookFont, fontSize = 18.sp, lineHeight = 24.sp, color = colors.primary)
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
    Spacer(Modifier.height(2.dp))
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
fun DateBar(date: LocalDate, onDate: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onDate(date.minusDays(1)) }) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, "前一天") }
        TextButton(onClick = { showDatePicker(context, date, onDate) }, modifier = Modifier.weight(1f)) {
            Text(date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        IconButton(onClick = { onDate(date.plusDays(1)) }, enabled = date < LocalDate.now()) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, "后一天") }
        TextButton(onClick = { onDate(LocalDate.now()) }) { Text("今天") }
    }
}

@Composable
fun TimelineItem(bundle: EntryBundle, first: Boolean, last: Boolean, showDate: Boolean, media: MediaFiles, onOpen: () -> Unit, onImage: (Int) -> Unit) {
    val entry = bundle.entry
    val colors = MaterialTheme.colorScheme
    val axis = colors.outlineVariant
    val node = colors.primary
    val timeColumn = (44 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = (timeColumn + 8.dp).toPx(); val y = 20.dp.toPx() + 11.sp.toPx()
        if (!(first && last)) drawLine(axis, Offset(x, if (first) y else 0f), Offset(x, if (last) y else size.height), 1.dp.toPx())
        drawCircle(node, 4.dp.toPx(), Offset(x, y))
        if (!first) drawCircle(colors.background, 2.5.dp.toPx(), Offset(x, y))
    }.padding(top = 20.dp, bottom = 22.dp)) {
        Column(Modifier.width(timeColumn).padding(top = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(timeLabel(entry.minuteOfDay), fontSize = 13.sp, fontWeight = FontWeight.Medium, softWrap = false)
            Text(period(entry.minuteOfDay), fontFamily = BookFont, fontSize = 12.sp, letterSpacing = 2.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
        Spacer(Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            if (showDate) Text(entry.entryDate, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { role = Role.Button }) {
                if (entry.title.isNotBlank()) Text(entry.title, fontFamily = BookFont, fontSize = 21.sp, lineHeight = 29.sp, modifier = Modifier.padding(bottom = 7.dp))
                if (entry.body.isNotBlank()) Text(entry.body.take(240) + if (entry.body.length > 240) "…" else "", fontSize = 14.sp, lineHeight = 26.sp)
                if (entry.title.isBlank() && entry.body.isBlank()) Text("图片随记", fontFamily = BookFont, fontSize = 20.sp)
            }
            if (entry.restoredFromId != null) Text("恢复副本", fontSize = 11.sp, color = colors.primary, modifier = Modifier.padding(top = 4.dp))
            PhotoGrid(bundle.orderedAssets(), media, onImage)
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (bundle.assets.isEmpty()) "文字随记" else "${bundle.assets.size} 张随记", fontSize = 12.sp, color = colors.onSurfaceVariant)
                IconButton(onClick = onOpen) { Icon(Icons.Outlined.MoreHoriz, "详情与编辑", tint = colors.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun PhotoGrid(assets: List<MediaAsset>, media: MediaFiles, onClick: (Int) -> Unit) {
    if (assets.isEmpty()) return
    val columns = when (assets.size) { 1 -> 1; 4 -> 2; else -> 3 }
    val width = if (assets.size == 4) 200.dp else if (assets.size == 1) 230.dp else 360.dp
    Column(Modifier.widthIn(max = width).fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        assets.chunked(columns).forEachIndexed { row, items ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items.forEachIndexed { col, asset ->
                    Box(Modifier.weight(1f).aspectRatio(if (assets.size == 1) 4f / 3f else 1f).background(MaterialTheme.colorScheme.surfaceVariant).border(.5.dp, MaterialTheme.colorScheme.outlineVariant).clickable { onClick(row * columns + col) }, contentAlignment = Alignment.Center) {
                        var failed by remember(asset.id) { mutableStateOf(false) }
                        if (failed) Icon(Icons.Outlined.BrokenImage, "图片读取失败")
                        AsyncImage(model = media.thumbnail(asset), contentDescription = "图片 ${row * columns + col + 1}", contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                    }
                }
                repeat(columns - items.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontFamily = BookFont, fontSize = 22.sp)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp, bottom = 15.dp))
        if (action != null) Button(onClick = onAction, shape = RoundedCornerShape(4.dp)) { Text(action) }
    }
}

fun sizeLabel(bytes: Long): String = when { bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024)); bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024)); else -> "${bytes / 1024} KiB" }
