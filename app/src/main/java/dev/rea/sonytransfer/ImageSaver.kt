package dev.rea.sonytransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 保存接收到的文件：图片进 Pictures/SonyTransfer，视频进 Movies/SonyTransfer。 */
object ImageSaver {

    fun isVideo(mime: String): Boolean = mime.startsWith("video/")

    fun save(context: Context, rawName: String, mime: String, data: ByteArray): Uri? {
        val name = safeName(rawName, mime)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, name, mime, data)
            } else {
                saveLegacy(context, name, mime, data)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从临时文件保存（流式复制，不再把大文件整块读进内存）。
     * 视频可达数百 MB，用 ByteArray 版本容易 OOM / GC 卡死。
     */
    fun saveFromFile(context: Context, rawName: String, mime: String, file: File): Uri? {
        if (!file.exists() || file.length() <= 0) return null
        val name = safeName(rawName, mime)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveFileViaMediaStore(context, name, mime, file)
            } else {
                saveFileLegacy(context, name, mime, file)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveFileViaMediaStore(
        context: Context,
        name: String,
        mime: String,
        file: File,
    ): Uri? {
        val resolver = context.contentResolver
        val video = isVideo(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(video))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return null
        var written = false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
                written = true
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveFileLegacy(context: Context, name: String, mime: String, file: File): Uri? {
        val video = isVideo(mime)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(
                if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
            ),
            "SonyTransfer",
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, name)
        var index = 1
        while (target.exists()) {
            target = File(
                dir,
                "${name.substringBeforeLast('.')}_${index++}.${name.substringAfterLast('.')}",
            )
        }
        file.inputStream().use { input ->
            target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
        }
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf(mime), null,
        )
        return Uri.fromFile(target)
    }

    private fun saveViaMediaStore(
        context: Context,
        name: String,
        mime: String,
        data: ByteArray,
    ): Uri? {
        val resolver = context.contentResolver
        val video = isVideo(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(video))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(data) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    private fun relativePath(video: Boolean): String =
        if (video) {
            "${Environment.DIRECTORY_MOVIES}/SonyTransfer"
        } else {
            "${Environment.DIRECTORY_PICTURES}/SonyTransfer"
        }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        name: String,
        mime: String,
        data: ByteArray,
    ): Uri? {
        val video = isVideo(mime)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(
                if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
            ),
            "SonyTransfer",
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        var file = File(dir, name)
        var index = 1
        while (file.exists()) {
            file = File(dir, "${name.substringBeforeLast('.')}_${index++}.${name.substringAfterLast('.')}")
        }
        file.writeBytes(data)
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf(mime), null,
        )
        return Uri.fromFile(file)
    }

    private fun safeName(raw: String, mime: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim()
        val video = isVideo(mime)
        if (name.isEmpty()) name = if (video) "MOV.mp4" else "IMG.jpg"
        if (!name.contains('.')) name += if (video) ".mp4" else ".jpg"
        return name
    }
}
