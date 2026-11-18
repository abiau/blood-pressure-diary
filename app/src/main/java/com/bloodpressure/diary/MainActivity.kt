package com.bloodpressure.diary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val diary by viewModels<DiaryModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF8DD6C6))
            } else {
                lightColorScheme(primary = Color(0xFF17695D), background = Color(0xFFF6FAF8))
            }
            MaterialTheme(colorScheme = colors) {
                DiaryScreen(diary)
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN)

private fun timestamp(epochMillis: Long, offsetSeconds: Int): String =
    Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)).format(dateFormat)

@Composable
private fun DiaryScreen(model: DiaryModel) {
    val context = LocalContext.current
    val history by model.history.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var camera by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Measurement?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            camera = true
        } else {
            model.start(OffsetDateTime.now())
        }
    }
    val manual = {
        camera = false
        model.start(OffsetDateTime.now())
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            model.clearError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (camera) {
                CameraScreen(
                    onCancel = { camera = false },
                    onManual = manual,
                    onResult = { takenAt, readings ->
                        camera = false
                        model.start(takenAt, readings)
                    }
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text("血壓日記", style = MaterialTheme.typography.headlineLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(enabled = !busy, onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                camera = true
                            } else {
                                permission.launch(Manifest.permission.CAMERA)
                            }
                        }) { Text("拍攝血壓計") }
                        OutlinedButton(onClick = manual, enabled = !busy) { Text("手動新增") }
                    }
                    Text("歷史紀錄", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = filter, onValueChange = { filter = it },
                        label = { Text("依日期查詢（年月日）") },
                        placeholder = { Text("2026/11/18") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    val query = filter.trim().replace('-', '/')
                    val visible = history.filter {
                        timestamp(it.measuredAt, it.offsetSeconds).substringBefore(' ').contains(query)
                    }
                    if (visible.isEmpty()) Text(if (query.isEmpty()) "尚無紀錄" else "查無符合的紀錄")
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(visible, key = { it.id }) { entry ->
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(timestamp(entry.measuredAt, entry.offsetSeconds))
                                    Text(
                                        "${entry.systolic} / ${entry.diastolic} mmHg",
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                    Text("脈搏 ${entry.pulse} 次／分")
                                    Row {
                                        TextButton(enabled = !busy, onClick = { model.edit(entry) }) { Text("修改") }
                                        TextButton(enabled = !busy, onClick = { deleting = entry }) { Text("刪除") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    draft?.let { entry ->
        DraftDialog(entry, busy, model::update, model::save, error)
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除這筆紀錄？") },
            text = { Text(timestamp(entry.measuredAt, entry.offsetSeconds)) },
            confirmButton = {
                TextButton(onClick = { model.delete(entry); deleting = null }) { Text("確認刪除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun DraftDialog(
    entry: Draft,
    busy: Boolean,
    onChange: (Draft?) -> Unit,
    onSave: () -> Unit,
    error: String?
) {
    AlertDialog(
        onDismissRequest = { onChange(null) },
        title = {
            Text(when {
                entry.id != 0L -> "修改紀錄"
                entry.fromCamera -> "確認辨識結果"
                else -> "新增紀錄"
            })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(timestamp(entry.measuredAt, entry.offsetSeconds))
                if (entry.fromCamera) Text("請核對血壓計；辨識不明的欄位請手動填寫。")
                NumberField("收縮壓（mmHg）", entry.systolic, !busy) { onChange(entry.copy(systolic = it)) }
                NumberField("舒張壓（mmHg）", entry.diastolic, !busy) { onChange(entry.copy(diastolic = it)) }
                NumberField("脈搏（次／分）", entry.pulse, !busy) { onChange(entry.copy(pulse = it)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = onSave, enabled = !busy) { Text("儲存") } },
        dismissButton = { TextButton(onClick = { onChange(null) }, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 3 && it.all { char -> char in '0'..'9' }) onChange(it) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
