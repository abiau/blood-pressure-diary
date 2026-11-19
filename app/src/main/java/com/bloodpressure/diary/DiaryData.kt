package com.bloodpressure.diary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Entity(tableName = "measurements")
internal data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val measuredAt: Long,
    val offsetSeconds: Int
)

@Dao
internal interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY measuredAt DESC, id DESC")
    fun observe(): kotlinx.coroutines.flow.Flow<List<Measurement>>

    @Query("SELECT measuredAt FROM measurements")
    suspend fun measurementTimes(): List<Long>

    @Upsert
    suspend fun save(measurement: Measurement)

    @Insert
    suspend fun insert(measurements: List<Measurement>)

    @Delete
    suspend fun delete(measurement: Measurement)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()
}

@Database(entities = [Measurement::class], version = 1)
internal abstract class DiaryDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
}

internal data class Draft(
    val id: Long,
    val systolic: String,
    val diastolic: String,
    val pulse: String,
    val measuredAt: Long,
    val offsetSeconds: Int,
    val fromPhoto: Boolean,
    val photoNote: String? = null
) {
    fun toMeasurement(): Measurement? {
        val sys = systolic.toIntOrNull() ?: return null
        val dia = diastolic.toIntOrNull() ?: return null
        val heartRate = pulse.toIntOrNull() ?: return null
        if (sys !in 1..999 || dia !in 1..999 || heartRate !in 1..999 || sys <= dia) return null
        return Measurement(id, sys, dia, heartRate, measuredAt, offsetSeconds)
    }
}

internal data class ImportEntry(
    val uri: Uri,
    val name: String,
    val draft: Draft? = null,
    val problem: String? = null,
    val selected: Boolean = false
)

internal data class ImportBatch(
    val total: Int,
    val entries: List<ImportEntry> = emptyList(),
    val processed: Int = 0,
    val saved: Int = 0,
    val duplicates: Int = 0
)

// 部分選檔來源提供毫秒，EXIF 一般只有秒；相同的 UTC 秒視為同一次量測。
internal fun measurementSecond(epochMillis: Long): Long = Math.floorDiv(epochMillis, 1000L)

internal class DiaryModel(application: Application) : AndroidViewModel(application) {
    private val database = Room.databaseBuilder(application, DiaryDatabase::class.java, "diary.db").build()
    private val dao = database.measurements()

    val history = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val currentDraft = MutableStateFlow<Draft?>(null)
    val draft = currentDraft.asStateFlow()
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val currentBatch = MutableStateFlow<ImportBatch?>(null)
    val batch = currentBatch.asStateFlow()
    private val currentImportEdit = MutableStateFlow<Uri?>(null)
    val importEdit = currentImportEdit.asStateFlow()
    private var importJob: Job? = null
    private val message = MutableStateFlow<String?>(null)
    val error = message.asStateFlow()

    fun start(whenMeasured: OffsetDateTime, readings: Readings? = null) {
        currentDraft.value = newDraft(whenMeasured, readings)
    }

    private fun newDraft(whenMeasured: OffsetDateTime, readings: Readings?, photoNote: String? = null) =
        Draft(
            0, readings?.systolic?.toString().orEmpty(), readings?.diastolic?.toString().orEmpty(),
            readings?.pulse?.toString().orEmpty(), whenMeasured.toInstant().toEpochMilli(),
            whenMeasured.offset.totalSeconds, readings != null, photoNote
        )

    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (currentBatch.value != null || writing.value || currentDraft.value != null) {
            message.value = "請先完成目前的編輯或匯入，再選擇其他照片。"
            return
        }
        val selected = uris.distinct()
        currentBatch.value = ImportBatch(selected.size)
        importJob = viewModelScope.launch {
            val knownTimes = try {
                dao.measurementTimes().mapTo(hashSetOf(), ::measurementSecond)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentBatch.value = null
                message.value = "無法檢查歷史紀錄，請重新選擇照片。"
                return@launch
            }
            // 每次解碼一張，批次中只保留文字與原檔 URI。
            for ((index, uri) in selected.withIndex()) {
                val entry = importEntry(uri, index + 1, knownTimes)
                ensureActive()
                val active = currentBatch.value ?: return@launch
                currentBatch.value = if (entry == null) {
                    active.copy(processed = index + 1, duplicates = active.duplicates + 1)
                } else {
                    active.copy(entries = active.entries + entry, processed = index + 1)
                }
            }
        }
    }

    private suspend fun importEntry(uri: Uri, number: Int, knownTimes: MutableSet<Long>): ImportEntry? {
        val app = getApplication<Application>()
        val name = withContext(Dispatchers.IO) { photoName(app, uri) } ?: "照片 $number"
        return try {
            val time = withContext(Dispatchers.IO) { readPhotoTime(app, uri, name) }
            val second = measurementSecond(time.value.toInstant().toEpochMilli())
            if (second in knownTimes) return null
            val source = if (time.fromFile) "依檔案修改時間" else "依照片原始拍攝時間"
            val image = withContext(Dispatchers.IO) { readPhotoImage(app, uri) }
            val draft = if (image != null) {
                val readings = try {
                    recognizePhoto(image)
                } finally {
                    image.bitmapInternal?.recycle()
                }
                newDraft(time.value, readings, source)
            } else {
                newDraft(time.value, Readings(null, null, null), "$source。無法讀取影像，請手動填寫。")
            }
            knownTimes.add(second)
            ImportEntry(uri, name, draft)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImportEntry(uri, name, problem = if (error is PhotoImportException) error.message else "無法讀取圖檔。")
        }
    }

    fun editImport(entry: ImportEntry) {
        currentImportEdit.value = entry.uri
        currentDraft.value = entry.draft
    }

    fun selectImport(uri: Uri, selected: Boolean) = changeImport(uri) {
        it.copy(selected = selected && it.draft?.toMeasurement() != null)
    }

    fun selectCompleteImports(selected: Boolean) {
        val active = currentBatch.value ?: return
        if (writing.value) return
        currentBatch.value = active.copy(entries = active.entries.map {
            it.copy(selected = selected && it.draft?.toMeasurement() != null)
        })
    }

    fun saveImports() {
        val active = currentBatch.value ?: return
        if (active.processed != active.total) return
        val selected = active.entries.filter { it.selected }
        val measurements = selected.mapNotNull { it.draft?.toMeasurement() }
        if (measurements.isEmpty() || measurements.size != selected.size) return
        write("批次儲存失敗，請再試一次。") {
            val added = database.withTransaction {
                val knownTimes = dao.measurementTimes().mapTo(hashSetOf(), ::measurementSecond)
                val new = measurements.filter { knownTimes.add(measurementSecond(it.measuredAt)) }
                if (new.isNotEmpty()) dao.insert(new)
                new.size
            }
            currentBatch.value = active.copy(
                entries = active.entries.filterNot { it.selected },
                saved = active.saved + added,
                duplicates = active.duplicates + measurements.size - added
            )
        }
    }

    fun closeBatch() {
        if (writing.value) return
        importJob?.cancel()
        currentBatch.value = null
        currentImportEdit.value = null
        currentDraft.value = null
    }

    private fun changeImport(uri: Uri, change: (ImportEntry) -> ImportEntry) {
        val active = currentBatch.value ?: return
        if (!writing.value) currentBatch.value = active.copy(
            entries = active.entries.map { if (it.uri == uri) change(it) else it }
        )
    }

    fun edit(measurement: Measurement) {
        currentDraft.value = with(measurement) {
            Draft(id, systolic.toString(), diastolic.toString(), pulse.toString(), measuredAt, offsetSeconds, false)
        }
    }

    fun update(draft: Draft?) {
        if (!writing.value) {
            currentDraft.value = draft
            if (draft == null) currentImportEdit.value = null
        }
    }

    fun save() {
        val draft = currentDraft.value ?: return
        val measurement = draft.toMeasurement()
        if (measurement == null) {
            message.value = "請填寫三個有效整數（1–999），且收縮壓須大於舒張壓。"
            return
        }
        val uri = currentImportEdit.value
        if (uri != null) {
            changeImport(uri) { it.copy(draft = draft, selected = true) }
            update(null)
            return
        }
        write("儲存失敗，請再試一次。") {
            dao.save(measurement)
            currentDraft.value = null
        }
    }

    fun delete(measurement: Measurement) = write("刪除失敗，請再試一次。") {
        dao.delete(measurement)
    }

    fun deleteAll() = write("無法刪除全部紀錄，請再試一次。") {
        dao.deleteAll()
    }

    fun clearError() {
        message.value = null
    }

    private fun write(failureMessage: String, operation: suspend () -> Unit) {
        if (writing.value) return
        writing.value = true
        viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = failureMessage
            } finally {
                writing.value = false
            }
        }
    }

    override fun onCleared() {
        database.close()
    }
}
