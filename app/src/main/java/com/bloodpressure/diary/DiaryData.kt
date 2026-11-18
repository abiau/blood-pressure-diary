package com.bloodpressure.diary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    @Upsert
    suspend fun save(measurement: Measurement)

    @Delete
    suspend fun delete(measurement: Measurement)
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
    val fromCamera: Boolean
)

internal class DiaryModel(application: Application) : AndroidViewModel(application) {
    private val database = Room.databaseBuilder(application, DiaryDatabase::class.java, "diary.db").build()
    private val dao = database.measurements()

    val history = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val currentDraft = MutableStateFlow<Draft?>(null)
    val draft = currentDraft.asStateFlow()
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val message = MutableStateFlow<String?>(null)
    val error = message.asStateFlow()

    fun start(whenMeasured: OffsetDateTime, readings: Readings? = null) {
        currentDraft.value = Draft(
            0, readings?.systolic?.toString().orEmpty(), readings?.diastolic?.toString().orEmpty(),
            readings?.pulse?.toString().orEmpty(), whenMeasured.toInstant().toEpochMilli(),
            whenMeasured.offset.totalSeconds, readings != null
        )
    }

    fun edit(measurement: Measurement) {
        currentDraft.value = with(measurement) {
            Draft(id, systolic.toString(), diastolic.toString(), pulse.toString(), measuredAt, offsetSeconds, false)
        }
    }

    fun update(draft: Draft?) {
        if (!writing.value) currentDraft.value = draft
    }

    fun save() {
        val draft = currentDraft.value ?: return
        val systolic = draft.systolic.toIntOrNull()
        val diastolic = draft.diastolic.toIntOrNull()
        val pulse = draft.pulse.toIntOrNull()
        if (systolic == null || diastolic == null || pulse == null ||
            systolic !in 1..999 || diastolic !in 1..999 || pulse !in 1..999 || systolic <= diastolic
        ) {
            message.value = "請填寫三個有效整數（1–999），且收縮壓須大於舒張壓。"
            return
        }
        write("儲存失敗，請再試一次。") {
            dao.save(Measurement(draft.id, systolic, diastolic, pulse, draft.measuredAt, draft.offsetSeconds))
            currentDraft.value = null
        }
    }

    fun delete(measurement: Measurement) = write("刪除失敗，請再試一次。") {
        dao.delete(measurement)
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
