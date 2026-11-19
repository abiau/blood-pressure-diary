package com.bloodpressure.diary

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BatchScreen(batch: ImportBatch, busy: Boolean, model: DiaryModel) {
    val finished = batch.processed == batch.total
    val selected = batch.entries.count { it.selected }
    val ready = batch.entries.count { it.draft?.toMeasurement() != null }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    val close = {
        if (!busy) {
            if (batch.entries.isNotEmpty() || !finished) confirmClose = true else model.closeBatch()
        }
    }
    BackHandler(onBack = close)

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("批次匯入", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(enabled = !busy, onClick = close) { Text("結束匯入") }
        }
        Text("已處理 ${batch.processed}／${batch.total} 張　已儲存 ${batch.saved} 筆")
        if (batch.duplicates > 0) Text("已跳過 ${batch.duplicates} 張量測時間重複的照片")
        if (!finished) {
            LinearProgressIndicator(
                progress = { batch.processed.toFloat() / batch.total },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (batch.entries.isNotEmpty()) {
            Text("請對照照片核對。點「核對」可查看原圖與補填數值。", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(batch.entries, key = { it.uri.toString() }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (finished && entry.draft != null) {
                            PhotoPreview(entry.uri, Modifier.size(54.dp).padding(end = 6.dp), maxSide = 144)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val draft = entry.draft
                            if (draft == null) {
                                Text(entry.problem.orEmpty(), color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(timestamp(draft.measuredAt, draft.offsetSeconds),
                                    style = MaterialTheme.typography.bodySmall)
                                val sys = draft.systolic.ifEmpty { "—" }
                                val dia = draft.diastolic.ifEmpty { "—" }
                                val pulse = draft.pulse.ifEmpty { "—" }
                                Text("$sys／$dia mmHg　脈搏 $pulse", style = MaterialTheme.typography.bodyMedium)
                                if (draft.toMeasurement() == null) {
                                    Text("待補填或修正", color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (entry.draft != null) {
                            TextButton(enabled = !busy, onClick = { model.editImport(entry) }) { Text("核對") }
                            Checkbox(
                                checked = entry.selected,
                                enabled = !busy && entry.draft.toMeasurement() != null,
                                modifier = Modifier.semantics { contentDescription = "選取 ${entry.name}" },
                                onCheckedChange = { model.selectImport(entry.uri, it) }
                            )
                        }
                    }
                }
            }
        }
        if (finished && batch.entries.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(enabled = !busy && ready > 0, onClick = { model.selectCompleteImports(selected != ready) }) {
                    Text(if (ready > 0 && selected == ready) "取消全選" else "全選完整紀錄")
                }
                Button(enabled = !busy && selected > 0, onClick = { confirmSave = true }) {
                    Text("儲存 $selected 筆")
                }
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("儲存 $selected 筆紀錄？") },
            text = { Text("請確認勾選的數值與原照片相符。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    model.saveImports()
                }) { Text("確認儲存") }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("返回核對") } }
        )
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("結束這次匯入？") },
            text = { Text("尚未儲存的匯入結果將會清除。") },
            confirmButton = { TextButton(onClick = model::closeBatch) { Text("結束匯入") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("繼續") } }
        )
    }
}

@Composable
internal fun PhotoPreview(uri: Uri, modifier: Modifier, maxSide: Int) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, maxSide) {
        value = withContext(Dispatchers.IO) { decodePhoto(context, uri, maxSide) }
    }
    val decoded = bitmap
    if (decoded == null) {
        Spacer(modifier)
    } else {
        val picture = remember(decoded) { decoded.asImageBitmap() }
        Image(picture, "血壓計照片", modifier, contentScale = ContentScale.Fit)
    }
}
