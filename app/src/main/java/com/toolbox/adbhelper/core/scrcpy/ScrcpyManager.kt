package com.toolbox.adbhelper.core.scrcpy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.toolbox.adbhelper.core.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class ScrcpyManager(
    private val context: Context,
    private val adbClient: AdbClient
) {

    suspend fun deployServer(): Boolean = withContext(Dispatchers.IO) {
        try {
            val assetFile = File(context.cacheDir, "scrcpy-server.jar")
            context.assets.open("scrcpy-server.jar").use { input ->
                FileOutputStream(assetFile).use { output ->
                    input.copyTo(output)
                }
            }
            val sync = adbClient.openSync()
            sync.pushFile(assetFile, "/data/local/tmp/scrcpy-server.jar")
            sync.close()
            assetFile.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 高效截取当前对端设备屏幕帧
     */
    suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val stream = adbClient.connection.openStream("shell:screencap -p")
            val baos = ByteArrayOutputStream()
            while (true) {
                val chunk = stream.read() ?: break
                baos.write(chunk)
            }
            stream.close()
            val bytes = baos.toByteArray()
            if (bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 发送模拟按键与手势
     */
    suspend fun sendTap(x: Int, y: Int) = withContext(Dispatchers.IO) {
        adbClient.executeShell("input tap $x $y")
    }

    suspend fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) = withContext(Dispatchers.IO) {
        adbClient.executeShell("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    suspend fun sendKey(keyCode: Int) = withContext(Dispatchers.IO) {
        adbClient.executeShell("input keyevent $keyCode")
    }

    suspend fun sendBack() = sendKey(4)
    suspend fun sendHome() = sendKey(3)
    suspend fun sendAppSwitch() = sendKey(187)
    suspend fun sendPower() = sendKey(26)
    suspend fun sendVolumeUp() = sendKey(24)
    suspend fun sendVolumeDown() = sendKey(25)
}
