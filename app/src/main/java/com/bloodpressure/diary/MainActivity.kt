package com.bloodpressure.diary

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
        if (savedInstanceState == null) importSharedPhotos(intent)
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF8DD6C6))
            } else {
                lightColorScheme(primary = Color(0xFF17695D), background = Color(0xFFF6FAF8))
            }
            MaterialTheme(colorScheme = colors) {
                DiaryScreen(diary) { orientation ->
                    if (requestedOrientation != orientation) requestedOrientation = orientation
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importSharedPhotos(intent)
    }

    private fun importSharedPhotos(intent: Intent) {
        if (intent.type?.startsWith("image/") != true) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        diary.importPhotos((uris + intent.photoUris()).filter { it.scheme == "content" })
    }
}

private fun Intent.photoUris(): List<Uri> = buildList {
    data?.let(::add)
    val clip = clipData ?: return@buildList
    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN)

internal fun timestamp(epochMillis: Long, offsetSeconds: Int): String =
    Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)).format(dateFormat)

@Composable
private fun DiaryScreen(model: DiaryModel, setOrientation: (Int) -> Unit) {
    val context = LocalContext.current
    val history by model.history.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val batch by model.batch.collectAsStateWithLifecycle()
    val importEdit by model.importEdit.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var camera by rememberSaveable { mutableStateOf(false) }
    var trends by rememberSaveable { mutableStateOf(false) }
    var trendLandscape by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Measurement?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::importPhotos)
    val showTrends = trends && batch == null && !camera
    LaunchedEffect(showTrends, trendLandscape) {
        val orientation = when (trendLandscape.takeIf { showTrends }) {
            true -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        setOrientation(orientation)
    }
    val closeTrends = {
        trends = false
        trendLandscape = null
    }
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
            val activeBatch = batch
            if (activeBatch != null) {
                BatchScreen(activeBatch, busy, model)
            } else if (trends) {
                BackHandler(onBack = closeTrends)
                TrendScreen(history, trendLandscape, onClose = closeTrends) { trendLandscape = it }
            } else if (camera) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { files.launch(arrayOf("image/*")) }
                        ) { Text("匯入照片") }
                        OutlinedButton(enabled = !busy, onClick = { trends = true }) {
                            Text("趨勢圖")
                        }
                    }
                    HistoryHeader(history.size, busy, model::deleteAll)
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
                    if (visible.isEmpty()) Text(if (history.isEmpty()) "尚無紀錄" else "查無符合的紀錄")
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
        DraftDialog(entry, busy, model::update, model::save, error, importEdit)
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
private fun HistoryHeader(total: Int, busy: Boolean, onDeleteAll: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("歷史紀錄", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Box {
            TextButton(enabled = total > 0 && !busy, onClick = { menuOpen = true }) { Text("管理") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("刪除全部", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        confirm = true
                    }
                )
            }
        }
    }
    if (confirm && total > 0) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("刪除全部歷史紀錄？") },
            text = { Text("將永久刪除全部 $total 筆紀錄，包含日期篩選未顯示的紀錄。此操作無法復原。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirm = false
                    onDeleteAll()
                }) { Text("刪除全部", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun DraftDialog(
    entry: Draft,
    busy: Boolean,
    onChange: (Draft?) -> Unit,
    onSave: () -> Unit,
    error: String?,
    photoUri: Uri?
) {
    AlertDialog(
        onDismissRequest = { onChange(null) },
        title = {
            Text(when {
                entry.id != 0L -> "修改紀錄"
                entry.fromPhoto -> "確認辨識結果"
                else -> "新增紀錄"
            })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (photoUri != null) {
                    PhotoPreview(photoUri, Modifier.fillMaxWidth().height(185.dp), maxSide = 900)
                }
                Text(timestamp(entry.measuredAt, entry.offsetSeconds))
                entry.photoNote?.let { Text(it) }
                if (entry.fromPhoto) Text("請核對血壓計；辨識不明的欄位請手動填寫。")
                NumberField("收縮壓（mmHg）", entry.systolic, !busy) { onChange(entry.copy(systolic = it)) }
                NumberField("舒張壓（mmHg）", entry.diastolic, !busy) { onChange(entry.copy(diastolic = it)) }
                NumberField("脈搏（次／分）", entry.pulse, !busy) { onChange(entry.copy(pulse = it)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !busy) { Text(if (photoUri == null) "儲存" else "確認這筆") }
        },
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
