package com.toolbox.adbhelper.core.scrcpy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.adb.AdbStream
import com.toolbox.adbhelper.core.model.ScrcpyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScrcpyManager(
    private val context: Context,
    private val adbClient: AdbClient
) {
    companion object {
        private const val TAG = "ScrcpyManager"
    }

    private val isStreaming = AtomicBoolean(false)
    private var streamThread: Thread? = null
    private var adbStream: AdbStream? = null
    private var mediaCodec: MediaCodec? = null

    /**
     * 获取对端设备的真实物理分辨率 (例如 1080x2400)
     */
    suspend fun getDeviceResolution(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val out = adbClient.executeShell("wm size")
            val match = Regex("""(\d+)x(\d+)""").find(out)
            if (match != null) {
                val w = match.groupValues[1].toInt()
                val h = match.groupValues[2].toInt()
                return@withContext Pair(w, h)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get wm size", e)
        }
        Pair(1080, 2400)
    }

    /**
     * 计算应用指定 maxSize 后的编码推流分辨率
     */
    fun calculateTargetSize(deviceW: Int, deviceH: Int, maxSize: Int): Pair<Int, Int> {
        val safeW = if (deviceW <= 0) 1080 else deviceW
        val safeH = if (deviceH <= 0) 1920 else deviceH
        var targetW = safeW
        var targetH = safeH

        if (maxSize > 0) {
            val major = maxOf(safeW, safeH)
            if (major > maxSize) {
                val ratio = maxSize.toFloat() / major
                targetW = (safeW * ratio).toInt()
                targetH = (safeH * ratio).toInt()
            }
        }
        // H.264 编码与解码器要求宽高必须是偶数（16 或 2 对齐）
        targetW = (targetW / 2) * 2
        targetH = (targetH / 2) * 2
        return Pair(maxOf(targetW, 16), maxOf(targetH, 16))
    }

    /**
     * 启动低延迟 H.264 硬件解码屏幕流并直接渲染到 Surface
     */
    fun startH264Stream(
        surface: Surface,
        config: ScrcpyConfig,
        deviceW: Int,
        deviceH: Int,
        onError: (String) -> Unit
    ) {
        if (!surface.isValid) {
            onError("Surface 显示画布尚未就绪，正切换为兼容模式...")
            return
        }

        if (isStreaming.getAndSet(true)) return

        val (targetW, targetH) = calculateTargetSize(deviceW, deviceH, config.maxSize)
        val bitrate = config.bitRate

        streamThread = Thread({
            try {
                // 1. 初始化本地 H.264 硬件解码器
                // 解码器输出到 Surface 时无需也不可设置 COLOR_FormatSurface (仅供编码器使用)
                val format = MediaFormat.createVideoFormat("video/avc", targetW, targetH).apply {
                    setInteger("max-input-size", maxOf(targetW * targetH, 256 * 1024))
                    setInteger("frame-rate", config.maxFps)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_OPERATING_RATE, config.maxFps)
                        setInteger(MediaFormat.KEY_PRIORITY, 0) // 实时最高优先级
                    }
                }

                val activeCodec = MediaCodec.createDecoderByType("video/avc")
                mediaCodec = activeCodec
                activeCodec.configure(format, surface, null, 0)
                activeCodec.start()

                // 2. 在被控端启动低延迟 screenrecord
                // --output-format=h264 输出到 stdout 管道
                val command = "shell:screenrecord --output-format=h264 --size ${targetW}x${targetH} --bit-rate $bitrate --time-limit 180 -"
                val activeStream = adbClient.connection.openStream(command)
                adbStream = activeStream

                // 3. 启动解码消费线程
                val decodeThread = Thread({
                    val info = MediaCodec.BufferInfo()
                    while (isStreaming.get()) {
                        try {
                            val outIndex = activeCodec.dequeueOutputBuffer(info, 10000)
                            if (outIndex >= 0) {
                                // 立即渲染到 Surface
                                activeCodec.releaseOutputBuffer(outIndex, true)
                            }
                        } catch (e: Exception) {
                            if (isStreaming.get()) {
                                Log.w(TAG, "Output buffer dequeue error: ${e.message}")
                            }
                            break
                        }
                    }
                }, "ScrcpyDecoderOutThread")
                decodeThread.start()

                // 4. 输入流读取并投递给 MediaCodec
                val inputStream = activeStream.inputStream
                feedH264ToCodec(inputStream, activeCodec)

            } catch (e: Exception) {
                Log.e(TAG, "H264 stream failed", e)
                if (isStreaming.get()) {
                    val errorDetail = e.message ?: e.localizedMessage ?: e.javaClass.simpleName
                    onError("流式投屏启动异常: $errorDetail，正切换为兼容模式...")
                }
            } finally {
                stopStream()
            }
        }, "ScrcpyStreamInThread").apply { start() }
    }

    /**
     * 将 H.264 字节流按 NAL 单元分包送入 MediaCodec
     */
    private fun feedH264ToCodec(input: InputStream, codec: MediaCodec) {
        val buffer = ByteArray(64 * 1024)
        var bufferLen = 0

        while (isStreaming.get()) {
            val bytesRead = input.read(buffer, bufferLen, buffer.size - bufferLen)
            if (bytesRead < 0) break
            bufferLen += bytesRead

            var pos = 0
            while (pos < bufferLen - 4) {
                // 查找下一个 NAL 单元起始码 00 00 00 01 或 00 00 01
                val nalStart = findNalStart(buffer, pos, bufferLen)
                if (nalStart < 0) break

                val nextNal = findNalStart(buffer, nalStart + 3, bufferLen)
                if (nextNal < 0) {
                    // 数据包未结束，移到头部等待更多数据
                    pos = nalStart
                    break
                }

                val nalSize = nextNal - nalStart
                feedOneNal(codec, buffer, nalStart, nalSize)
                pos = nextNal
            }

            if (pos > 0) {
                System.arraycopy(buffer, pos, buffer, 0, bufferLen - pos)
                bufferLen -= pos
            }
        }
    }

    private fun findNalStart(data: ByteArray, start: Int, end: Int): Int {
        for (i in start until end - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i < end - 4 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
        }
        return -1
    }

    private fun feedOneNal(codec: MediaCodec, data: ByteArray, offset: Int, size: Int) {
        try {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    codec.getInputBuffer(inIndex)
                } else {
                    codec.inputBuffers[inIndex]
                }
                if (inBuffer == null) return
                inBuffer.clear()
                if (size > inBuffer.capacity()) {
                    Log.w(TAG, "NAL size $size exceeds buffer capacity ${inBuffer.capacity()}, frame skipped")
                    return
                }
                inBuffer.put(data, offset, size)
                codec.queueInputBuffer(inIndex, 0, size, System.nanoTime() / 1000, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedOneNal error: ${e.message}")
        }
    }

    /**
     * 停止屏幕流
     */
    fun stopStream() {
        if (!isStreaming.getAndSet(false)) return
        try {
            adbStream?.close()
        } catch (_: Exception) {}
        adbStream = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            streamThread?.interrupt()
        } catch (_: Exception) {}
        streamThread = null
    }

    /**
     * 兼容模式截帧（单帧拉取）
     */
    suspend fun captureFrame(targetW: Int = 0, targetH: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
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
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null && targetW > 0 && targetH > 0 && (bitmap.width != targetW || bitmap.height != targetH)) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    bitmap.recycle()
                    scaled
                } else {
                    bitmap
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "captureFrame failed", e)
            null
        }
    }

    // ====== 熄屏控制与电源管理 ======

    /**
     * 被控端熄屏（保留后台操作与投屏）
     */
    suspend fun turnScreenOff() = withContext(Dispatchers.IO) {
        // 先设为保持唤醒，然后关背光/熄屏
        adbClient.executeShell("svc power stayon true")
        adbClient.executeShell("input keyevent 26")
    }

    /**
     * 被控端点亮屏幕
     */
    suspend fun turnScreenOn() = withContext(Dispatchers.IO) {
        adbClient.executeShell("input keyevent 224") // KEYCODE_WAKEUP
    }

    /**
     * 调节亮度（0 熄灭背光但保持系统开启）
     */
    suspend fun setScreenBrightness(brightness: Int) = withContext(Dispatchers.IO) {
        adbClient.executeShell("settings put system screen_brightness $brightness")
    }

    // ====== 虚拟按键与手势输入 ======

    suspend fun sendTap(x: Int, y: Int) = withContext(Dispatchers.IO) {
        adbClient.executeShell("input tap $x $y")
    }

    suspend fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 250) = withContext(Dispatchers.IO) {
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
