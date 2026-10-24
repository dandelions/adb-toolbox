package com.toolbox.adbhelper.core.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.toolbox.adbhelper.core.adb.AdbSync
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DownloadHelper {

    /**
     * 将远端文件拉取并保存到本机系统的公共下载目录 (Downloads)
     * 兼容 Android 10+ Scoped Storage (MediaStore) 及旧版本系统
     * @return 返回保存成功后的可读位置描述
     */
    fun downloadToPublicDownloads(
        context: Context,
        sync: AdbSync,
        remotePath: String,
        fileName: String
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadViaMediaStore(context, sync, remotePath, fileName)
        } else {
            downloadViaFile(context, sync, remotePath, fileName)
        }
    }

    private fun downloadViaMediaStore(
        context: Context,
        sync: AdbSync,
        remotePath: String,
        fileName: String
    ): String {
        val resolver = context.contentResolver
        val mimeType = getMimeType(fileName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在公共下载目录创建文件记录: $fileName")

        try {
            resolver.openOutputStream(uri)?.use { os ->
                sync.pullFile(remotePath, os)
            } ?: throw IOException("无法打开目标文件的写入流")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return "Download/${fileName}"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun downloadViaFile(
        context: Context,
        sync: AdbSync,
        remotePath: String,
        fileName: String
    ): String {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        var targetFile = File(downloadDir, fileName)
        if (targetFile.exists()) {
            val nameWithoutExt = fileName.substringBeforeLast('.', fileName)
            val ext = if (fileName.contains('.')) "." + fileName.substringAfterLast('.') else ""
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(downloadDir, "${nameWithoutExt}_$counter$ext")
                counter++
            }
        }

        FileOutputStream(targetFile).use { fos ->
            sync.pullFile(remotePath, fos)
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            null,
            null
        )

        return targetFile.absolutePath
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
