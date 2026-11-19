package com.bloodpressure.diary

import android.content.Context
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

internal suspend fun recognizePhoto(image: InputImage): Readings {
    val segments = withContext(Dispatchers.Default) {
        image.bitmapInternal?.let { readDisplay(it, image.rotationDegrees) }
    }
    if (segments?.isComplete == true) return segments
    val fallback = suspendCoroutine { continuation ->
        recognizeText(image) { continuation.resume(it) }
    }
    return segments?.withFallback(fallback) ?: fallback
}

private fun recognizeText(image: InputImage, onResult: (Readings) -> Unit) {
    val empty = Readings(null, null, null)
    val recognizer = try {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    } catch (_: Exception) {
        onResult(empty)
        return
    }
    val task = try {
        recognizer.process(image)
    } catch (_: Exception) {
        recognizer.close()
        onResult(empty)
        return
    }
    task.addOnCompleteListener { result ->
        val readings = if (result.isSuccessful) {
            val tokens = result.result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.mapNotNull { element ->
                        element.boundingBox?.let { OcrToken(element.text, it.left, it.top, it.right, it.bottom) }
                    }
                }
            }
            parseReadings(tokens)
        } else {
            empty
        }
        recognizer.close()
        onResult(readings)
    }
}

internal data class PhotoTime(val value: OffsetDateTime, val fromFile: Boolean)
internal class PhotoImportException(message: String) : Exception(message)

private val imageTypes = setOf("image/jpeg", "image/heic", "image/heif")
private val extensions = setOf("jpg", "jpeg", "heic", "heif")
private val exifDate = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

internal fun photoName(context: Context, uri: Uri): String? =
    context.contentResolver.column(uri, OpenableColumns.DISPLAY_NAME)

internal fun readPhotoTime(context: Context, uri: Uri, name: String): PhotoTime {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)
    if (mime !in imageTypes && name.substringAfterLast('.').lowercase(Locale.ROOT) !in extensions) {
        throw PhotoImportException("請選擇 HEIC 或 JPG 圖檔。")
    }

    val modified = resolver.column(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?: resolver.column(uri, MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()
            ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
    // 檔案時間優先。檔案提供者未回報時，改讀照片內原始拍攝時間。
    val exif = if (modified == null) runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val info = ExifInterface(stream)
            info.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) to info.getAttribute("OffsetTimeOriginal")
        }
    }.getOrNull() else null
    return resolvePhotoTime(modified, exif?.first, exif?.second, ZoneId.systemDefault())
        ?: throw PhotoImportException("這張照片沒有可用的檔案時間或拍攝時間，請選擇原始圖檔。")
}

internal fun readPhotoImage(context: Context, uri: Uri): InputImage? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodePhoto(context, uri, 2048)?.let { InputImage.fromBitmap(it, 0) }
    } else {
        runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
    }

internal fun decodePhoto(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // 由系統套用原圖方向；限制長邊以控制大型照片的記憶體用量。
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height))
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1))
        }
    } else {
        InputImage.fromFilePath(context, uri).bitmapInternal
    }
}.getOrNull()

private val openCvAvailable by lazy {
    try {
        OpenCVLoader.initLocal()
    } catch (_: LinkageError) {
        false
    }
}

private fun readDisplay(bitmap: Bitmap, rotation: Int): Readings? {
    if (!openCvAvailable) return null
    val scale = minOf(1f, 640f / maxOf(bitmap.width, bitmap.height))
    val transform = Matrix().apply {
        setScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
    val rgba = Mat()
    val gray = Mat()
    return try {
        Utils.bitmapToMat(scaled, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        readSevenSegmentDisplay(gray)
    } catch (_: Exception) {
        null
    } finally {
        gray.release()
        rgba.release()
        if (scaled !== bitmap) scaled.recycle()
    }
}

internal fun resolvePhotoTime(
    modifiedMillis: Long?,
    original: String?,
    originalOffset: String?,
    zone: ZoneId
): PhotoTime? {
    if (modifiedMillis != null && modifiedMillis > 0) {
        return PhotoTime(Instant.ofEpochMilli(modifiedMillis).atZone(zone).toOffsetDateTime(), true)
    }
    return runCatching {
        val local = LocalDateTime.parse(original?.trim() ?: return null, exifDate)
        val offset = originalOffset?.trim()?.takeIf { it.isNotEmpty() }?.let(ZoneOffset::of)
        PhotoTime(offset?.let(local::atOffset) ?: local.atZone(zone).toOffsetDateTime(), false)
    }.getOrNull()
}

private fun ContentResolver.column(uri: Uri, name: String): String? = runCatching {
    query(uri, arrayOf(name), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(name)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
