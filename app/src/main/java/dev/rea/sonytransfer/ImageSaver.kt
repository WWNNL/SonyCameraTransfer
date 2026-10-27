package dev.rea.sonytransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 保存 JPEG：Android 10+ 走 MediaStore（Pictures/SonyTransfer），9 及以下写公共目录 + MediaScanner。 */
object ImageSaver {

    fun save(context: Context, rawName: String, data: ByteArray): Uri? {
        val name = safeName(rawName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, name, data)
            } else {
                saveLegacy(context, name, data)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveViaMediaStore(context: Context, name: String, data: ByteArray): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SonyTransfer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(data) }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, name: String, data: ByteArray): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "SonyTransfer",
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        var file = File(dir, name)
        var index = 1
        while (file.exists()) {
            file = File(dir, "${name.substringBeforeLast('.')}_${index++}.jpg")
        }
        file.writeBytes(data)
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null,
        )
        return Uri.fromFile(file)
    }

    private fun safeName(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim()
        if (name.isEmpty()) name = "IMG.jpg"
        if (!name.contains('.')) name += ".jpg"
        return name
    }
}
