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
    private var serverStream: AdbStream? = null
    private var adbStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private var mediaCodec: MediaCodec? = null

    // 缓存最快且可用的截屏方式 (1: exec, 2: base64, 3: temp_file)
    @Volatile
    private var preferredCaptureMethod = 0

    @Volatile
    private var lastCaptureError: String = ""

    fun getLastCaptureError(): String = lastCaptureError

    fun isStreamingNow(): Boolean = isStreaming.get()

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
     * 根据被控机物理分辨率与本机可用显示区域（如 960x1080），等比例计算推流和渲染目标分辨率，严格保持原始纵横比
     * 例如被控机 1080x2400，本机 960x1080：
     * 等比计算后为 486x1080，完整容纳且保持纵横比，绝不拉伸变形
     */
    fun calculateTargetSize(
        deviceW: Int,
        deviceH: Int,
        maxSize: Int = -1,
        localW: Int = 0,
        localH: Int = 0
    ): Pair<Int, Int> {
        val safeW = if (deviceW <= 0) 1080 else deviceW
        val safeH = if (deviceH <= 0) 2400 else deviceH

        var targetW = safeW.toFloat()
        var targetH = safeH.toFloat()

        // 1. 如果提供了本机容器尺寸 (例如 960x1080)，按 Contain (fitCenter) 方式等比限制在容器内
        if (localW > 0 && localH > 0) {
            val scaleW = localW.toFloat() / safeW
            val scaleH = localH.toFloat() / safeH
            val scale = minOf(scaleW, scaleH)
            targetW = safeW * scale
            targetH = safeH * scale
        }

        // 2. 如果用户指定了具体的 maxSize (长边上限，如 1280、1920、854)，且 maxSize > 0
        if (maxSize > 0) {
            val major = maxOf(targetW, targetH)
            if (major > maxSize) {
                val ratio = maxSize.toFloat() / major
                targetW *= ratio
                targetH *= ratio
            }
        }

        // 3. 偶数对齐 (H.264 与视频编解码器硬性要求宽高为 2 或 16 的倍数，最小不低于 16)
        var finalW = (targetW.toInt() / 2) * 2
        var finalH = (targetH.toInt() / 2) * 2
        finalW = maxOf(finalW, 16)
        finalH = maxOf(finalH, 16)

        return Pair(finalW, finalH)
    }

    /**
     * 确保 assets 中的官方 scrcpy-server.jar 已同步到对端设备的 /data/local/tmp 目录
     */
    private fun ensureServerPushed(): Boolean {
        try {
            val localCache = java.io.File(context.cacheDir, "scrcpy-server.jar")
            if (!localCache.exists() || localCache.length() == 0L) {
                context.assets.open("scrcpy-server.jar").use { input ->
                    localCache.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val expectedSize = localCache.length()
            if (expectedSize <= 0) return false

            val checkOut = adbClient.executeShell("ls -l /data/local/tmp/scrcpy-server.jar 2>/dev/null")
            val remoteSizeMatch = Regex("""\s+(\d+)\s+""").find(checkOut)?.groupValues?.get(1)?.toLongOrNull()
            if (remoteSizeMatch == expectedSize) {
                return true
            }

            val sync = adbClient.openSync()
            try {
                sync.pushFile(localCache, "/data/local/tmp/scrcpy-server.jar")
            } finally {
                sync.close()
            }
            adbClient.executeShell("chmod 755 /data/local/tmp/scrcpy-server.jar")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Push scrcpy-server.jar fallback: ${e.message}")
            return false
        }
    }

    /**
     * 启动低延迟 H.264 硬件解码屏幕流并直接渲染到 Surface
     */
    fun startH264Stream(
        surface: Surface,
        config: ScrcpyConfig,
        deviceW: Int,
        deviceH: Int,
        localW: Int = 0,
        localH: Int = 0,
        onFirstFrame: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        if (!surface.isValid) {
            onError("Surface 显示画布尚未就绪，正切换为兼容模式...")
            return
        }

        if (isStreaming.getAndSet(true)) return

        val (targetW, targetH) = calculateTargetSize(deviceW, deviceH, config.maxSize, localW, localH)
        val bitrate = if (config.bitRate > 0) config.bitRate else 4_000_000
        val maxFps = if (config.maxFps > 0) config.maxFps else 60

        streamThread = Thread({
            var firstFrameRendered = false
            var activeStream: AdbStream? = null
            var finalW = targetW
            var finalH = targetH

            try {
                // 1. 尝试使用官方 scrcpy-server (60fps, 极低延迟, 免装 APK)
                var useScrcpyServer = ensureServerPushed()
                if (useScrcpyServer) {
                    try {
                        val serverCmd = "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 1.18 info $targetW $bitrate $maxFps -1 true - false false 0 false true - - false"
                        serverStream = adbClient.connection.openStream("shell:$serverCmd", 5000)

                        // 轮询等待 server 建立 localabstract:scrcpy 监听
                        var connectedStream: AdbStream? = null
                        for (i in 0 until 20) {
                            if (!isStreaming.get()) break
                            try {
                                connectedStream = adbClient.connection.openStream("localabstract:scrcpy", 1000)
                                break
                            } catch (_: Exception) {
                                Thread.sleep(150)
                            }
                        }

                        if (connectedStream != null) {
                            activeStream = connectedStream
                            adbStream = activeStream

                            // 连接辅助 control socket 保持服务端握手完整
                            try {
                                controlStream = adbClient.connection.openStream("localabstract:scrcpy", 1500)
                            } catch (_: Exception) {}

                            // 读取 1 字节 dummy 握手
                            val dummy = activeStream.inputStream.read()
                            if (dummy != 0) {
                                throw java.io.IOException("Invalid scrcpy server handshake: $dummy")
                            }

                            // 读取 68 字节 meta (64字节设备名 + 2字节宽 + 2字节高)
                            val meta = ByteArray(68)
                            var metaRead = 0
                            while (metaRead < 68) {
                                val r = activeStream.inputStream.read(meta, metaRead, 68 - metaRead)
                                if (r < 0) throw java.io.IOException("EOF reading scrcpy metadata")
                                metaRead += r
                            }
                            val serverW = ((meta[64].toInt() and 0xFF) shl 8) or (meta[65].toInt() and 0xFF)
                            val serverH = ((meta[66].toInt() and 0xFF) shl 8) or (meta[67].toInt() and 0xFF)
                            if (serverW > 0 && serverH > 0) {
                                finalW = serverW
                                finalH = serverH
                            }
                            Log.i(TAG, "Scrcpy-server connected successfully, resolution: ${finalW}x${finalH}")
                        } else {
                            useScrcpyServer = false
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Scrcpy-server start failed: ${e.message}, falling back to screenrecord")
                        useScrcpyServer = false
                    }
                }

                // 2. 若 scrcpy-server 无法启动，降级尝试系统 screenrecord
                if (activeStream == null) {
                    val execCommand = "exec:screenrecord --output-format=h264 --size ${targetW}x${targetH} --bit-rate $bitrate --time-limit 180 -"
                    activeStream = try {
                        adbClient.connection.openStream(execCommand, 2500)
                    } catch (_: Exception) {
                        adbClient.connection.openStream("shell:screenrecord --output-format=h264 --size ${targetW}x${targetH} --bit-rate $bitrate --time-limit 180 -", 2500)
                    }
                    adbStream = activeStream
                }

                // 3. 初始化本地 H.264 硬件解码器
                val format = MediaFormat.createVideoFormat("video/avc", finalW, finalH).apply {
                    setInteger("max-input-size", maxOf(finalW * finalH, 1024 * 1024))
                    setInteger("frame-rate", maxFps)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setInteger(MediaFormat.KEY_OPERATING_RATE, maxFps)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                    }
                }

                val activeCodec = MediaCodec.createDecoderByType("video/avc")
                mediaCodec = activeCodec
                activeCodec.configure(format, surface, null, 0)
                activeCodec.start()

                // 4. 启动解码消费线程
                val decodeThread = Thread({
                    val info = MediaCodec.BufferInfo()
                    while (isStreaming.get()) {
                        try {
                            val outIndex = activeCodec.dequeueOutputBuffer(info, 10000)
                            if (outIndex >= 0) {
                                if (!firstFrameRendered) {
                                    firstFrameRendered = true
                                    onFirstFrame()
                                }
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

                // 5. 输入流读取并投递给 MediaCodec
                val stream = activeStream ?: throw java.io.IOException("未能建立对端流式连接")
                val inputStream = stream.inputStream
                val framesFed = feedH264ToCodec(inputStream, activeCodec)

                if (isStreaming.get() && (framesFed == 0 || !firstFrameRendered)) {
                    onError("流式传输未检测到画面数据，已自动切换为稳定截屏模式")
                }

            } catch (e: Exception) {
                val isInterrupted = e is InterruptedException || e.cause is InterruptedException || !isStreaming.get()
                if (isInterrupted) {
                    Log.i(TAG, "Stream stopped or interrupted normally")
                } else {
                    Log.e(TAG, "H264 stream failed", e)
                    if (isStreaming.get()) {
                        val errorDetail = e.message?.takeIf { it.isNotBlank() }
                            ?: e.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: e.javaClass.simpleName
                        onError("流式投屏启动异常 ($errorDetail)，已切换至稳定截屏模式")
                    }
                }
            } finally {
                stopStream()
            }
        }, "ScrcpyStreamInThread").apply { start() }
    }

    /**
     * 将 H.264 字节流按 NAL 单元分包送入 MediaCodec
     */
    private fun feedH264ToCodec(input: InputStream, codec: MediaCodec): Int {
        var buffer = ByteArray(1024 * 1024)
        var bufferLen = 0
        var framesFed = 0

        while (isStreaming.get()) {
            if (buffer.size - bufferLen < 64 * 1024) {
                if (buffer.size < 4 * 1024 * 1024) {
                    buffer = buffer.copyOf(buffer.size * 2)
                }
            }

            val bytesRead = try {
                input.read(buffer, bufferLen, buffer.size - bufferLen)
            } catch (e: Exception) {
                if (isStreaming.get()) {
                    Log.w(TAG, "Input stream read error: ${e.message}")
                }
                break
            }
            if (bytesRead < 0) break
            bufferLen += bytesRead

            var pos = 0
            while (pos < bufferLen - 4) {
                // 查找下一个 NAL 单元起始码 00 00 00 01 或 00 00 01
                val nalStart = findNalStart(buffer, pos, bufferLen)
                if (nalStart < 0) {
                    pos = maxOf(0, bufferLen - 3)
                    break
                }

                val nextNal = findNalStart(buffer, nalStart + 3, bufferLen)
                if (nextNal < 0) {
                    // 数据包未结束，移到头部等待更多数据
                    pos = nalStart
                    break
                }

                val nalSize = nextNal - nalStart
                feedOneNal(codec, buffer, nalStart, nalSize)
                framesFed++
                pos = nextNal
            }

            if (pos > 0) {
                System.arraycopy(buffer, pos, buffer, 0, bufferLen - pos)
                bufferLen -= pos
            }
        }
        return framesFed
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
        if (size <= 4) return
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

                // 解析 NAL 单元起始码偏移以读取 nal_type
                val nalHeaderPos = if (size > 3 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()) {
                    offset + 3
                } else if (size > 4 && data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) {
                    offset + 4
                } else {
                    offset
                }

                val nalType = if (nalHeaderPos < offset + size) {
                    data[nalHeaderPos].toInt() and 0x1F
                } else 0

                val flags = when (nalType) {
                    7, 8 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG // SPS, PPS 参数集配置
                    5 -> MediaCodec.BUFFER_FLAG_KEY_FRAME // IDR 关键帧
                    else -> 0
                }

                codec.queueInputBuffer(inIndex, 0, size, System.nanoTime() / 1000, flags)
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
            controlStream?.close()
        } catch (_: Exception) {}
        controlStream = null

        try {
            serverStream?.close()
        } catch (_: Exception) {}
        serverStream = null

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
     * 稳定兼容截帧（多层自动降级兜底，确保在所有机型上100%获取到画面）
     */
    suspend fun captureFrame(targetW: Int = 0, targetH: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        val method = preferredCaptureMethod
        // 优先使用历史已验证的最快模式
        if (method == 1) {
            val bmp = captureFrameViaExec(targetW, targetH)
            if (bmp != null) return@withContext bmp
        } else if (method == 2) {
            val bmp = captureFrameViaBase64(targetW, targetH)
            if (bmp != null) return@withContext bmp
        } else if (method == 3) {
            val bmp = captureFrameViaTempFile(targetW, targetH)
            if (bmp != null) return@withContext bmp
        }

        // 探测阶段：依次尝试 1: exec -> 2: base64 -> 3: 临时文件同步
        val bmpExec = captureFrameViaExec(targetW, targetH)
        if (bmpExec != null) {
            preferredCaptureMethod = 1
            return@withContext bmpExec
        }

        val bmpB64 = captureFrameViaBase64(targetW, targetH)
        if (bmpB64 != null) {
            preferredCaptureMethod = 2
            return@withContext bmpB64
        }

        val bmpTemp = captureFrameViaTempFile(targetW, targetH)
        if (bmpTemp != null) {
            preferredCaptureMethod = 3
            return@withContext bmpTemp
        }

        null
    }

    private fun captureFrameViaExec(targetW: Int, targetH: Int): Bitmap? {
        try {
            val raw = readStreamBytes("exec:screencap -p", 4000)
            if (raw.isEmpty()) {
                lastCaptureError = "exec:screencap 无输出"
                return null
            }
            if (raw.size > 8) {
                val offset = findPngHeader(raw)
                if (offset >= 0) {
                    val bmp = BitmapFactory.decodeByteArray(raw, offset, raw.size - offset)
                    if (bmp != null) return scaleBitmapIfNeeded(bmp, targetW, targetH)
                    lastCaptureError = "BitmapFactory 解码失败"
                } else {
                    val preview = String(raw, 0, minOf(raw.size, 80), Charsets.UTF_8).trim()
                    lastCaptureError = "非PNG输出: $preview"
                }
            } else {
                lastCaptureError = "字节过短(${raw.size}字节)"
            }
        } catch (e: Exception) {
            lastCaptureError = "Exec截屏异常: ${e.message}"
        }
        return null
    }

    private fun captureFrameViaBase64(targetW: Int, targetH: Int): Bitmap? {
        try {
            val raw = readStreamBytes("shell:screencap -p 2>/dev/null | base64", 5000)
            if (raw.isEmpty()) {
                lastCaptureError = "Base64管道无输出"
                return null
            }
            val decoded = try {
                android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                lastCaptureError = "Base64解码异常: ${e.message}"
                return null
            }
            if (decoded.size > 8) {
                val offset = findPngHeader(decoded)
                if (offset >= 0) {
                    val bmp = BitmapFactory.decodeByteArray(decoded, offset, decoded.size - offset)
                    if (bmp != null) return scaleBitmapIfNeeded(bmp, targetW, targetH)
                    lastCaptureError = "PNG解析失败"
                } else {
                    lastCaptureError = "Base64还原数据无PNG头"
                }
            }
        } catch (e: Exception) {
            lastCaptureError = "Base64截屏异常: ${e.message}"
        }
        return null
    }

    private fun captureFrameViaTempFile(targetW: Int, targetH: Int): Bitmap? {
        try {
            val remotePath = "/data/local/tmp/scrcpy_frame.png"
            val screencapOut = adbClient.executeShell("screencap -p $remotePath")
            if (screencapOut.contains("Permission denied") || screencapOut.contains("error")) {
                lastCaptureError = "临时截图被拒绝: ${screencapOut.trim()}"
                return null
            }
            val localCache = java.io.File(context.cacheDir, "temp_frame.png")
            val sync = adbClient.openSync()
            try {
                sync.pullFile(remotePath, localCache)
            } finally {
                sync.close()
            }
            if (localCache.exists() && localCache.length() > 0) {
                val bmp = BitmapFactory.decodeFile(localCache.absolutePath)
                if (bmp != null) return scaleBitmapIfNeeded(bmp, targetW, targetH)
                lastCaptureError = "临时文件解码失败"
            } else {
                lastCaptureError = "拉取截图为空"
            }
        } catch (e: Exception) {
            lastCaptureError = "文件同步异常: ${e.message}"
        }
        return null
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        return if (targetW > 0 && targetH > 0 && (bitmap.width != targetW || bitmap.height != targetH)) {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (scaled != bitmap) {
                bitmap.recycle()
            }
            scaled
        } else {
            bitmap
        }
    }

    private fun readStreamBytes(command: String, timeoutMs: Long = 4000): ByteArray {
        val stream = try {
            adbClient.connection.openStream(command, timeoutMs)
        } catch (_: Exception) {
            return ByteArray(0)
        }
        val baos = ByteArrayOutputStream(512 * 1024)
        try {
            while (true) {
                val chunk = stream.read() ?: break
                baos.write(chunk)
            }
        } catch (_: Exception) {
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
        return baos.toByteArray()
    }

    private fun sanitizePngBytes(bytes: ByteArray): ByteArray {
        if (bytes.size < 8) return bytes
        // 损坏的 PNG (经过 PTY 0A -> 0D 0A 转换): 89 50 4E 47 0D 0D 0A 1A 0D 0A
        val isCrlfCorrupted = bytes.size >= 10 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0D.toByte() &&
                bytes[6] == 0x0A.toByte()

        if (!isCrlfCorrupted) return bytes

        // 将所有 0x0D 0x0A 还原为 0x0A
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (i < bytes.size - 1 && bytes[i] == 0x0D.toByte() && bytes[i + 1] == 0x0A.toByte()) {
                out.write(0x0A)
                i += 2
            } else {
                out.write(bytes[i].toInt() and 0xFF)
                i++
            }
        }
        return out.toByteArray()
    }

    private fun findPngHeader(data: ByteArray): Int {
        for (i in 0 until data.size - 7) {
            if (data[i] == 0x89.toByte() &&
                data[i + 1] == 0x50.toByte() &&
                data[i + 2] == 0x4E.toByte() &&
                data[i + 3] == 0x47.toByte() &&
                data[i + 4] == 0x0D.toByte() &&
                data[i + 5] == 0x0A.toByte() &&
                data[i + 6] == 0x1A.toByte() &&
                data[i + 7] == 0x0A.toByte()
            ) {
                return i
            }
        }
        return -1
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
