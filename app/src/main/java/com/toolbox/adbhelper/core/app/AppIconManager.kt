package com.toolbox.adbhelper.core.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.toolbox.adbhelper.core.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object AppIconManager {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4096)
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var isSyncing = false

    fun getFromMemory(packageName: String): Bitmap? {
        return memoryCache.get(packageName)
    }

    fun loadIcon(context: Context, packageName: String): Bitmap? {
        val mem = memoryCache.get(packageName)
        if (mem != null) return mem

        try {
            val iconDir = File(context.cacheDir, "app_icons")
            val iconFile = File(iconDir, "$packageName.png")
            if (iconFile.exists()) {
                val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
                if (bmp != null) {
                    memoryCache.put(packageName, bmp)
                    return bmp
                }
            }
        } catch (_: Exception) {}

        return null
    }

    fun saveIcon(context: Context, packageName: String, bitmap: Bitmap) {
        memoryCache.put(packageName, bitmap)
        try {
            val iconDir = File(context.cacheDir, "app_icons")
            if (!iconDir.exists()) iconDir.mkdirs()
            val iconFile = File(iconDir, "$packageName.png")
            FileOutputStream(iconFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (_: Exception) {}
    }

    suspend fun syncRemoteIcons(
        context: Context,
        client: AdbClient,
        onAppUpdated: (packageName: String, label: String?, icon: Bitmap?) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isSyncing) return@withContext
        isSyncing = true
        try {
            ensureHelperPushed(context, client)
            val remoteCmd = "export CLASSPATH=/data/local/tmp/app-helper.jar; exec app_process / com.toolbox.adbhelper.server.AppHelper all"

            client.executeShellLines(remoteCmd, timeoutMs = 60000) { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("APP:")) {
                    val raw = trimmed.substring(4)
                    val parts = raw.split("\t")
                    if (parts.isNotEmpty()) {
                        val pkgName = parts[0]
                        val label = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1] else null
                        var bitmap: Bitmap? = null

                        if (parts.size > 2 && parts[2].isNotEmpty()) {
                            try {
                                val bytes = Base64.decode(parts[2], Base64.DEFAULT)
                                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    saveIcon(context, pkgName, bitmap)
                                }
                            } catch (_: Exception) {}
                        }

                        if (label != null || bitmap != null) {
                            mainHandler.post {
                                onAppUpdated(pkgName, label, bitmap)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isSyncing = false
        }
    }

    private fun ensureHelperPushed(context: Context, client: AdbClient) {
        try {
            val check = client.executeShell("ls -l /data/local/tmp/app-helper.jar 2>/dev/null")
            if (check.contains("app-helper.jar")) {
                return
            }

            val temp = File(context.cacheDir, "app-helper.jar")
            context.assets.open("app-helper.jar").use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            }

            val sync = client.openSync()
            try {
                sync.pushFile(temp, "/data/local/tmp/app-helper.jar")
            } finally {
                sync.close()
                temp.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
