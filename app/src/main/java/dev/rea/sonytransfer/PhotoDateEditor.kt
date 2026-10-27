package dev.rea.sonytransfer

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 修正照片拍摄日期（用于相机没电后时钟归零、照片时间变成 2016 等场景）。
 * 同时改写 JPEG 的 EXIF（DateTime / DateTimeOriginal / DateTimeDigitized）和 MediaStore 的 DATE_TAKEN，
 * 这样相册里的日期排序/显示也会跟着更新。
 */
class PhotoDateEditor(private val context: Context) {

    data class UpdateResult(val ok: Boolean, val message: String, val epochMillis: Long)

    fun updateDate(uri: Uri?, calendar: Calendar, isVideo: Boolean = false): UpdateResult {
        if (uri == null) return UpdateResult(false, "没有可修改的照片", 0L)
        return try {
            if (isVideo) {
                updateMediaStore(uri, calendar)
                rescanIfNeeded(uri)
                return UpdateResult(
                    true,
                    "视频相册日期已改为 ${PhotoDates.displayString(calendar)}",
                    calendar.timeInMillis,
                )
            }
            val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return UpdateResult(false, "读取照片失败", 0L)
            val patched = runCatching { patchExif(original, calendar) }.getOrNull()
            if (patched != null) {
                writeBytes(uri, patched)
            }
            updateMediaStore(uri, calendar)
            rescanIfNeeded(uri)
            val message = if (patched != null) {
                "日期已改为 ${PhotoDates.displayString(calendar)}"
            } else {
                "相册日期已改为 ${PhotoDates.displayString(calendar)}（EXIF 未能更新）"
            }
            UpdateResult(true, message, calendar.timeInMillis)
        } catch (e: Exception) {
            UpdateResult(false, "修改失败：${e.message}", 0L)
        }
    }

    /** 用 ExifInterface 改写日期后返回新的 JPEG 字节。 */
    private fun patchExif(original: ByteArray, calendar: Calendar): ByteArray {
        val exifDate = PhotoDates.exifString(calendar)
        val temp = File.createTempFile("datefix", ".jpg", context.cacheDir)
        return try {
            temp.writeBytes(original)
            val exif = ExifInterface(temp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
            exif.saveAttributes()
            temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        var lastError: Exception? = null
        for (mode in listOf("wt", "w")) {
            try {
                val output = context.contentResolver.openOutputStream(uri, mode) ?: continue
                output.use { it.write(bytes) }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法写入照片")
    }

    private fun updateMediaStore(uri: Uri, calendar: Calendar) {
        if (uri.scheme != "content" || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_TAKEN, calendar.timeInMillis)
            put(MediaStore.Images.Media.DATE_MODIFIED, calendar.timeInMillis / 1000)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    private fun rescanIfNeeded(uri: Uri) {
        if (uri.scheme == "file") {
            val path = uri.path ?: return
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(path), arrayOf("image/jpeg"), null,
            )
        }
    }
}

/** 日期选择与格式化工具（EXIF 用 "yyyy:MM:dd HH:mm:ss"，界面展示用 "yyyy-MM-dd HH:mm:ss"）。 */
object PhotoDates {

    fun showPicker(activity: Activity, initialTakenAt: String?, onPicked: (Calendar) -> Unit) {
        val calendar = Calendar.getInstance()
        val initial = initialTakenAt?.let {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(it)
            }.getOrNull()
        }
        calendar.time = initial ?: Date()
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        onPicked(calendar)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    fun exifString(calendar: Calendar): String =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(calendar.time)

    fun displayString(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(calendar.time)

    fun parseDisplay(value: String?): Date? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(raw)
        }.getOrNull()
    }
}
