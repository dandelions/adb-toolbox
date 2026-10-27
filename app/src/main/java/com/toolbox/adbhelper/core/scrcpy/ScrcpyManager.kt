package com.toolbox.adbhelper.core.scrcpy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.adb.AdbStream
import com.toolbox.adbhelper.core.model.ScrcpyConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class ScrcpyManager(
    private val context: Context,
    private var adbClient: AdbClient
) {
    companion object {
        private const val TAG = "ScrcpyManager"
    }

    fun updateAdbClient(newClient: AdbClient) {
        this.adbClient = newClient
    }

    private val isStreaming = AtomicBoolean(false)
    private var streamThread: Thread? = null
    private var serverStream: AdbStream? = null
    private var adbStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private val controlQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var controlThread: Thread? = null
    @Volatile
    private var controlReaderThread: Thread? = null
    private val _deviceClipboard = java.util.concurrent.atomic.AtomicReference<String>("")
    val lastDeviceClipboard: String get() = _deviceClipboard.get()
    @Volatile
    private var getClipboardDeferred: CompletableDeferred<String>? = null
    var onDeviceClipboardChanged: ((String) -> Unit)? = null
    private var mediaCodec: MediaCodec? = null
    private var audioPlayer: AudioStreamPlayer? = null
    @Volatile
    private var isSurfaceReady = false
    private var currentSurface: Surface? = null

    /**
     * 动态更新 MediaCodec 的输出 Surface (API 23+)
     * 用于 App 切后台 (Surface 销毁) 与切回前台 (Surface 重建) 时的无缝切换，
     * 无需断开底层推流连接或重启解码器，彻底杜绝黑屏问题。
     */
    fun updateSurface(newSurface: Surface?): Boolean {
        currentSurface = newSurface
        if (newSurface == null || !newSurface.isValid) {
            isSurfaceReady = false
            Log.d(TAG, "Surface 销毁或脱离，暂停解码画面渲染")
            return true
        }

        val codec = mediaCodec
        if (codec != null && isStreaming.get()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    codec.setOutputSurface(newSurface)
                    isSurfaceReady = true
                    Log.i(TAG, "MediaCodec 输出 Surface 动态切换成功，画面即时恢复")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "MediaCodec.setOutputSurface 失败 (${e.message})，需完整重构推流")
                }
            }
        }
        return false
    }

    fun hasControl(): Boolean = controlStream != null && controlStream?.isClosed() != true

    @Volatile
    var serverVideoWidth: Int = 0
        private set
    @Volatile
    var serverVideoHeight: Int = 0
        private set

    /**
     * 音频转发状态与错误监听
     */
    var onAudioStatus: ((message: String, isError: Boolean) -> Unit)? = null

    /**
     * 音频活动状态(有声/静音)变化监听
     */
    var onSoundActiveChanged: ((Boolean) -> Unit)? = null

    // 缓存最快且可用的截屏方式 (1: exec, 2: base64, 3: temp_file)
    @Volatile
    private var preferredCaptureMethod = 0

    fun getPreferredCaptureMethod(): Int = preferredCaptureMethod

    fun setPreferredCaptureMethod(method: Int) {
        preferredCaptureMethod = method.coerceIn(0, 3)
    }

    fun getPreferredCaptureMethodName(): String = when (preferredCaptureMethod) {
        1 -> "Exec管道"
        2 -> "Base64管道"
        3 -> "临时文件"
        else -> "自动探测"
    }

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
            // 优先匹配 Override size (系统实际生效的显示分辨率)，其次匹配 Physical size
            val overrideMatch = Regex("""Override size:\s*(\d+)x(\d+)""").find(out)
            if (overrideMatch != null) {
                val w = overrideMatch.groupValues[1].toInt()
                val h = overrideMatch.groupValues[2].toInt()
                return@withContext Pair(w, h)
            }
            val physicalMatch = Regex("""Physical size:\s*(\d+)x(\d+)""").find(out)
            if (physicalMatch != null) {
                val w = physicalMatch.groupValues[1].toInt()
                val h = physicalMatch.groupValues[2].toInt()
                return@withContext Pair(w, h)
            }
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
        // 同时 scale 不超过 1.0f，避免小屏对端被无谓放大推流超过原生尺寸
        if (localW > 0 && localH > 0) {
            val scaleW = localW.toFloat() / safeW
            val scaleH = localH.toFloat() / safeH
            val scale = minOf(1.0f, scaleW, scaleH)
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
        onFirstFrame: (width: Int, height: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit
    ) {
        if (!surface.isValid) {
            onError("Surface 显示画布尚未就绪，正切换为兼容模式...")
            return
        }

        if (isStreaming.getAndSet(true)) return

        currentSurface = surface
        isSurfaceReady = surface.isValid

        val (targetW, targetH) = calculateTargetSize(deviceW, deviceH, config.maxSize, localW, localH)
        val bitrate = if (config.bitRate > 0) config.bitRate else 4_000_000
        val maxFps = if (config.maxFps > 0) config.maxFps else 60

        if (config.enableAudio) {
            val qualityMode = config.audioQualityMode
            val gain = config.audioGain
            val muteDevice = config.muteDeviceAudio
            val existingPlayer = audioPlayer
            if (existingPlayer != null && existingPlayer.isPlaying()) {
                existingPlayer.audioGain = gain
                existingPlayer.audioQualityMode = qualityMode
                existingPlayer.setDeviceMuted(adbClient, muteDevice)
                Log.i(TAG, "复用已有活跃音频流，动态同步增益与静音配置")
            }
        }

        streamThread = Thread({
            var firstFrameRendered = false
            var activeStream: AdbStream? = null
            var finalW = targetW
            var finalH = targetH

            try {
                // 启动前确保清理对端残留的 scrcpy-server / screenrecord 进程，释放 localabstract:scrcpy 端口
                try {
                    adbClient.executeShell("pkill -9 -f com.genymobile.scrcpy.Server; pkill -9 -f screenrecord", 2000)
                } catch (_: Exception) {}

                // 1. 尝试使用官方 scrcpy-server (60fps, 极低延迟, 免装 APK)
                val useScrcpyServer = ensureServerPushed()
                if (useScrcpyServer) {
                    try {
                        // scrcpy 1.18 命令行规范严格校验 (必须精确匹配 16 个参数):
                        // 1.version(1.18) 2.logLevel(info) 3.maxSize(必须为8的倍数, 0代表原生)
                        // 4.bitrate 5.maxFps 6.lockedVideoOrientation(-1) 7.tunnelForward(true)
                        // 8.crop(-) 9.sendFrameMeta(false) 10.control(true:开启控制通道以支持无缝熄屏) 11.displayId(0)
                        // 12.showTouches(false) 13.stayAwake(true) 14.codecOptions(-) 15.encoderName(-) 16.powerOffScreenOnClose(false)
                        // maxSize > 0 代表指定固定分辨率(如1280/1920/854)；maxSize <= 0(如自动适配-1、原画0)传0表示原生无损输出
                        val safeMaxSize = if (config.maxSize > 0) (config.maxSize / 8) * 8 else 0
                        val serverCmd = "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 1.18 info $safeMaxSize $bitrate $maxFps -1 true - false true 0 false true - - false"
                        val sStream = adbClient.connection.openStream("shell:$serverCmd", 5000)
                        serverStream = sStream

                        // 异步消费 server 输出，防止管道缓冲区写满挂起
                        Thread({
                            try {
                                val reader = sStream.inputStream.bufferedReader()
                                while (isStreaming.get()) {
                                    val line = reader.readLine() ?: break
                                    Log.d(TAG, "[scrcpy-server] $line")
                                }
                            } catch (_: Exception) {}
                        }, "ScrcpyServerLogThread").apply { isDaemon = true; start() }

                        // 轮询等待 server 建立 localabstract:scrcpy 监听
                        var connectedStream: AdbStream? = null
                        for (i in 0 until 30) {
                            if (!isStreaming.get()) break
                            try {
                                connectedStream = adbClient.connection.openStream("localabstract:scrcpy", 1000)
                                break
                            } catch (_: Exception) {
                                Thread.sleep(100)
                            }
                        }

                        if (connectedStream != null) {
                            activeStream = connectedStream
                            adbStream = activeStream

                            // 握手步骤 1: 读取服务端在接受 videoSocket 连接后写入的 1 字节 dummy 响应 (值为 0)
                            val dummyByte = activeStream.inputStream.read()
                            if (dummyByte < 0) {
                                throw java.io.IOException("EOF reading scrcpy handshake dummy byte")
                            }

                            // 握手步骤 2: 紧接着连接 controlSocket (scrcpy-server DesktopConnection.open 必须 accept 两次，否则会一直阻塞等待控制通道)
                            try {
                                val cStream = adbClient.connection.openStream("localabstract:scrcpy", 2000)
                                controlStream = cStream
                                startControlThread(cStream)
                                Log.i(TAG, "Scrcpy 原生极速控制通道已建立并启动分发线程")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to connect scrcpy control stream: ${e.message}")
                            }

                            // 握手步骤 3: 读取 68 字节 meta (64字节设备名 + 2字节宽 + 2字节高)
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
                                serverVideoWidth = serverW
                                serverVideoHeight = serverH
                            }
                            Log.i(TAG, "Scrcpy-server connected successfully, resolution: ${finalW}x${finalH}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Scrcpy-server start failed: ${e.message}, falling back to screenrecord")
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
                val activeCodec = MediaCodec.createDecoderByType("video/avc")
                mediaCodec = activeCodec

                // 优先尝试低延迟高刷配置；若机型驱动不支持则平滑降级为基线标准配置
                val lowLatencyFormat = MediaFormat.createVideoFormat("video/avc", finalW, finalH).apply {
                    setInteger("max-input-size", maxOf(finalW * finalH, 1024 * 1024))
                    setInteger("frame-rate", maxFps)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                        } catch (_: Exception) {}
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        } catch (_: Exception) {}
                    }
                }

                try {
                    activeCodec.configure(lowLatencyFormat, surface, null, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "MediaCodec low-latency configure failed (${e.message}), falling back to safe baseline format")
                    val baselineFormat = MediaFormat.createVideoFormat("video/avc", finalW, finalH).apply {
                        setInteger("max-input-size", maxOf(finalW * finalH, 1024 * 1024))
                        setInteger("frame-rate", minOf(maxFps, 60))
                    }
                    activeCodec.configure(baselineFormat, surface, null, 0)
                }
                activeCodec.start()

                // 4. 启动解码消费线程 (鲁棒性容错，绝不因单帧异常退圈导致卡死)
                val decodeThread = Thread({
                    val info = MediaCodec.BufferInfo()
                    while (isStreaming.get()) {
                        try {
                            val outIndex = activeCodec.dequeueOutputBuffer(info, 10000)
                            when {
                                outIndex >= 0 -> {
                                    if (!firstFrameRendered) {
                                        firstFrameRendered = true
                                        onFirstFrame(finalW, finalH)
                                    }
                                    val shouldRender = isSurfaceReady && isStreaming.get()
                                    try {
                                        activeCodec.releaseOutputBuffer(outIndex, shouldRender)
                                    } catch (e: Exception) {
                                        if (isStreaming.get()) {
                                            Log.w(TAG, "Transient output buffer release error: ${e.message}")
                                        }
                                    }
                                }
                                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    Log.i(TAG, "Decoder output format changed: ${activeCodec.outputFormat}")
                                }
                                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    // 正常等待数据
                                }
                            }
                        } catch (e: Exception) {
                            if (isStreaming.get()) {
                                Log.w(TAG, "Transient output buffer dequeue error: ${e.message}")
                                try { Thread.sleep(10) } catch (_: Exception) {}
                            }
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
                synchronized(this@ScrcpyManager) {
                    if (streamThread == Thread.currentThread()) {
                        // 关键修复：视频接收线程异常或退出时，仅停止视频推流，绝不能打断独立运行的音频流
                        stopVideoStream()
                    }
                }
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
            var inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex < 0) {
                // 解码器忙时重试一次，避免高刷时轻微抖动丢弃关键数据
                inIndex = codec.dequeueInputBuffer(20000)
            }
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

                val isConfig = nalType == 7 || nalType == 8
                // 注意：MediaCodec.queueInputBuffer 的 flags 严格只支持 BUFFER_FLAG_CODEC_CONFIG 或 0
                // 严禁传入 BUFFER_FLAG_KEY_FRAME（该标志仅用于 MediaExtractor/Muxer），否则部分 Android 芯片驱动会报 IllegalArgumentException 导致关键帧全丢
                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                // SPS/PPS 配置包 pts 必须为 0，视频帧单调递增纳秒时间戳
                val pts = if (isConfig) 0L else (System.nanoTime() / 1000L)
                codec.queueInputBuffer(inIndex, 0, size, pts, flags)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedOneNal error: ${e.message}")
        }
    }

    /**
     * 停止屏幕流
     */
    fun isAudioPlaying(): Boolean = audioPlayer?.isPlaying() == true

    fun isSoundActive(): Boolean = audioPlayer?.isSoundActive == true

    fun hasRecentSound(timeoutMs: Long = 3000L): Boolean = audioPlayer?.hasRecentSound(timeoutMs) == true

    fun setAudioGain(gain: Float) {
        audioPlayer?.audioGain = gain
    }

    fun setDeviceMuted(muted: Boolean) {
        audioPlayer?.setDeviceMuted(adbClient, muted)
    }

    /**
     * 独立启动音频流转发
     */
    fun startAudioStream(
        audioSource: Int = 3,
        qualityMode: Int = 0,
        gain: Float = 1.0f,
        muteDevice: Boolean = false
    ) {
        audioPlayer?.stop()
        Thread({
            try {
                val player = AudioStreamPlayer(context)
                player.onStatus = { msg, isErr -> onAudioStatus?.invoke(msg, isErr) }
                player.onSoundActiveChanged = { active -> onSoundActiveChanged?.invoke(active) }
                audioPlayer = player
                kotlinx.coroutines.runBlocking {
                    player.start(adbClient, audioSource, qualityMode, gain, muteDevice)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio player launch error: ${e.message}")
                onAudioStatus?.invoke("音频启动异常: ${e.message}", true)
            }
        }, "AudioStartThread").start()
    }

    /**
     * 停止音频流
     */
    fun stopAudioStream() {
        try {
            audioPlayer?.stop()
        } catch (_: Exception) {}
        audioPlayer = null
    }

    /**
     * 仅停止视频推流与解码（切后台或纯后台听歌时使用，保持音频流不间断）
     */
    fun stopVideoStream() {
        if (!isStreaming.getAndSet(false)) return
        isSurfaceReady = false
        currentSurface = null

        // 1. 优先停止解码器与解码线程，防止它们继续向 socket 写入 OKAY 数据包
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        serverVideoWidth = 0
        serverVideoHeight = 0

        val t = streamThread
        streamThread = null
        try {
            t?.interrupt()
            if (t != null && t != Thread.currentThread()) {
                t.join(800)
            }
        } catch (_: Exception) {}

        stopControlThread()

        // 2. 温和关闭各 ADB 通道流，顺序为视频流 -> 控制流 -> server进程流
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
    }

    /**
     * 停止全部推流（视频与音频）
     */
    fun stopStream() {
        stopVideoStream()
        stopAudioStream()
    }

    /**
     * 动态开启或关闭音频传输
     * @param audioSource 0: 自动系统内录, 1: 麦克风音源, 2: 语音识别
     */
    fun setAudioEnabled(
        enabled: Boolean,
        audioSource: Int = 0,
        qualityMode: Int = 0,
        gain: Float = 1.0f,
        muteDevice: Boolean = false
    ) {
        if (enabled) {
            startAudioStream(audioSource, qualityMode, gain, muteDevice)
        } else {
            stopAudioStream()
            onAudioStatus?.invoke("音频已停止", false)
        }
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
            val raw = readStreamBytes("exec:screencap -p", 3500)
            if (raw.isEmpty()) {
                lastCaptureError = "exec:screencap 无输出"
                return null
            }
            if (raw.size > 8) {
                var offset = findPngHeader(raw)
                var validData = raw
                if (offset < 0) {
                    val sanitized = sanitizePngBytes(raw)
                    val offset2 = findPngHeader(sanitized)
                    if (offset2 >= 0) {
                        offset = offset2
                        validData = sanitized
                    }
                }
                if (offset >= 0) {
                    val bmp = BitmapFactory.decodeByteArray(validData, offset, validData.size - offset)
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

    @Suppress("UNUSED_PARAMETER")
    private fun scaleBitmapIfNeeded(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        // 严格保持原始图像的长宽比，严禁破坏比例硬缩放拉伸导致画面变形！
        // ImageView 的 FIT_CENTER 会由 GPU 硬件加速自动等比居中渲染，彻底省去 CPU 重采样开销
        return bitmap
    }

    private fun readStreamBytes(command: String, timeoutMs: Long = 3500): ByteArray {
        val stream = try {
            adbClient.connection.openStream(command, timeoutMs)
        } catch (_: Exception) {
            return ByteArray(0)
        }
        val baos = ByteArrayOutputStream(512 * 1024)
        val startTime = System.currentTimeMillis()
        try {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val remain = timeoutMs - elapsed
                if (remain <= 0) break
                val chunk = stream.read(minOf(remain, 1200L)) ?: break
                if (chunk.isEmpty()) break
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

    // ====== 控制通道分发循环与极速输入注入 ======

    private fun startControlThread(stream: AdbStream) {
        controlThread?.interrupt()
        controlReaderThread?.interrupt()
        controlQueue.clear()

        controlThread = Thread({
            try {
                val out = stream.outputStream
                while (!Thread.currentThread().isInterrupted && isStreaming.get()) {
                    val packet = controlQueue.take()
                    out.write(packet)
                    if (controlQueue.isEmpty()) {
                        out.flush()
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "Control loop error: ${e.message}")
            }
        }, "ScrcpyControlThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        controlReaderThread = Thread({
            try {
                val din = java.io.DataInputStream(stream.inputStream)
                while (!Thread.currentThread().isInterrupted && isStreaming.get() && !stream.isClosed()) {
                    val type = din.readByte().toInt()
                    if (type == 0) { // TYPE_CLIPBOARD = 0
                        val len = din.readInt()
                        if (len in 0..1048576) {
                            val buf = ByteArray(len)
                            din.readFully(buf)
                            val text = String(buf, java.nio.charset.StandardCharsets.UTF_8)
                            Log.d(TAG, "Received clipboard from scrcpy device: ${text.take(30)}... (${text.length} chars)")
                            _deviceClipboard.set(text)
                            getClipboardDeferred?.complete(text)
                            getClipboardDeferred = null
                            onDeviceClipboardChanged?.invoke(text)
                        }
                    } else {
                        Log.d(TAG, "Received unknown control message type: $type")
                    }
                }
            } catch (_: java.io.EOFException) {
            } catch (_: java.net.SocketException) {
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "Control reader loop error: ${e.message}")
            }
        }, "ScrcpyControlReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopControlThread() {
        try {
            controlThread?.interrupt()
        } catch (_: Exception) {}
        controlThread = null

        try {
            controlReaderThread?.interrupt()
        } catch (_: Exception) {}
        controlReaderThread = null

        getClipboardDeferred?.cancel()
        getClipboardDeferred = null
        controlQueue.clear()
    }

    /**
     * 微秒级无延迟注入原生触控事件 (ACTION_DOWN, ACTION_MOVE, ACTION_UP, ACTION_CANCEL, ACTION_POINTER_DOWN, ACTION_POINTER_UP)
     * scrcpy-server 1.18 自定义版 INJECT_MY_TOUCH_EVENT 标准报文: 36 字节
     * 直接投递给底层 InputManager，精准携带 pointerId、精确时间戳和按键状态，完美支持惯性滑动与多指触控
     */
    fun sendTouchEvent(
        action: Int,
        x: Int,
        y: Int,
        devW: Int,
        devH: Int,
        pointerId: Long = 0L,
        buttons: Int = 1,
        timeMs: Long = SystemClock.uptimeMillis()
    ) {
        val stream = controlStream
        if (stream == null || stream.isClosed()) return

        try {
            val scrcpyAction = when (action) {
                MotionEvent.ACTION_DOWN -> 0.toByte()
                MotionEvent.ACTION_UP -> 1.toByte()
                MotionEvent.ACTION_MOVE -> 2.toByte()
                MotionEvent.ACTION_CANCEL -> 1.toByte()
                else -> {
                    when (action and MotionEvent.ACTION_MASK) {
                        MotionEvent.ACTION_POINTER_DOWN -> 0.toByte()
                        MotionEvent.ACTION_POINTER_UP -> 1.toByte()
                        else -> return
                    }
                }
            }

            // scrcpy-server 服务端 Device.getPhysicalPoint 校验规则：
            // 报文中的 screenWidth 和 screenHeight 必须与服务端的 unlockedVideoSize (即 serverVideoWidth x serverVideoHeight) 严格一致！
            // 否则会直接抛出: "Ignore touch event, it was generated for a different device size" 并把事件全丢弃！
            val targetW = if (serverVideoWidth > 0) serverVideoWidth else devW
            val targetH = if (serverVideoHeight > 0) serverVideoHeight else devH
            if (targetW <= 0 || targetH <= 0) return

            // 若调用端传入的参考系 (devW, devH) 与服务端真实分辨率不一致，自动等比换算，确保绝对命中目标像素
            val actualX = if (devW > 0 && devW != targetW) {
                ((x.toFloat() / devW) * targetW).toInt()
            } else {
                x
            }.coerceIn(0, targetW - 1)

            val actualY = if (devH > 0 && devH != targetH) {
                ((y.toFloat() / devH) * targetH).toInt()
            } else {
                y
            }.coerceIn(0, targetH - 1)

            // 报文结构共 36 字节:
            // [0] TYPE: 2 (1 byte, TYPE_INJECT_TOUCH_EVENT / INJECT_MY_TOUCH_EVENT)
            // [1] action: 0=DOWN, 1=UP, 2=MOVE (1 byte)
            // [2..9] pointerId: Long (8 bytes)
            // [10..13] x: Int (4 bytes)
            // [14..17] y: Int (4 bytes)
            // [18..19] screenWidth: Short (2 bytes)
            // [20..21] screenHeight: Short (2 bytes)
            // [22..23] pressure: Short (2 bytes, 0xffff=1.0f, 释放为0)
            // [24..27] buttons: Int (4 bytes, 1=PRIMARY, 释放为0)
            // [28..35] time: Long (8 bytes, 毫秒级时间戳，用于VelocityTracker计算滑动惯性速度)
            val buf = ByteBuffer.allocate(36)
            buf.put(2.toByte())
            buf.put(scrcpyAction)
            buf.putLong(pointerId)
            buf.putInt(actualX)
            buf.putInt(actualY)
            buf.putShort(targetW.toShort())
            buf.putShort(targetH.toShort())

            val isRelease = (action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL ||
                    (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP)
            buf.putShort(if (isRelease) 0.toShort() else 0xffff.toShort())
            buf.putInt(if (isRelease) 0 else buttons)
            buf.putLong(timeMs)

            // 防网络抖动队列保护：若队列积压过多(>30)，清理旧的MOVE包保持跟手度
            if (action == MotionEvent.ACTION_MOVE && controlQueue.size > 30) {
                controlQueue.poll()
            }
            controlQueue.offer(buf.array())
        } catch (e: Exception) {
            Log.w(TAG, "sendTouchEvent error: ${e.message}")
        }
    }

    // ====== 熄屏控制与电源管理 ======

    /**
     * 被控端物理熄屏（底层切断屏幕硬件供电，但系统不休眠，视频流与后台交互持续正常输出，绝不黑屏）
     */
    suspend fun turnScreenOff() = withContext(Dispatchers.IO) {
        var success = false
        // 1. 优先通过官方 scrcpy 控制通道发送 TYPE_SET_SCREEN_POWER_MODE = 10, mode = 0 (POWER_MODE_OFF)
        try {
            val stream = controlStream
            if (stream != null) {
                stream.outputStream.write(byteArrayOf(10.toByte(), 0.toByte()))
                stream.outputStream.flush()
                success = true
                Log.i(TAG, "Turned screen off via scrcpy control stream (SurfaceControl.POWER_MODE_OFF)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Turn screen off via control stream failed: ${e.message}")
        }

        if (!success) {
            // 2. 降级兜底：严禁调用 input keyevent 26（电源键休眠会导致投屏画面全黑）
            // 降级为将背光亮度调至 0 并保持唤醒
            try {
                adbClient.executeShell("svc power stayon true")
                adbClient.executeShell("settings put system screen_brightness 0")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback turnScreenOff failed: ${e.message}")
            }
        }
    }

    /**
     * 被控端点亮物理屏幕并尝试唤醒解锁
     */
    suspend fun turnScreenOn() = withContext(Dispatchers.IO) {
        // 1. 优先通过官方 scrcpy 控制通道恢复屏幕供电：TYPE_SET_SCREEN_POWER_MODE = 10, mode = 2 (POWER_MODE_NORMAL)
        try {
            val stream = controlStream
            if (stream != null) {
                stream.outputStream.write(byteArrayOf(10.toByte(), 2.toByte()))
                stream.outputStream.flush()
                Log.i(TAG, "Turned screen on via scrcpy control stream (SurfaceControl.POWER_MODE_NORMAL)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Turn screen on via control stream failed: ${e.message}")
        }

        // 2. 恢复系统亮度并发送亮屏解锁指令
        try {
            adbClient.executeShell("settings put system screen_brightness 128")
            adbClient.executeShell("input keyevent 224") // KEYCODE_WAKEUP
            adbClient.executeShell("wm dismiss-keyguard") // 解锁无密码锁屏
        } catch (e: Exception) {
            Log.w(TAG, "turnScreenOn fallback failed: ${e.message}")
        }
    }

    /**
     * 调节亮度（0 熄灭背光但保持系统开启）
     */
    suspend fun setScreenBrightness(brightness: Int) = withContext(Dispatchers.IO) {
        adbClient.executeShell("settings put system screen_brightness $brightness")
    }

    // ====== 虚拟按键与手势输入 ======

    suspend fun sendTap(x: Int, y: Int) = withContext(Dispatchers.IO) {
        val stream = controlStream
        if (stream != null && !stream.isClosed() && serverVideoWidth > 0 && serverVideoHeight > 0) {
            val (devW, devH) = getDeviceResolution()
            val now = SystemClock.uptimeMillis()
            sendTouchEvent(MotionEvent.ACTION_DOWN, x, y, devW, devH, 0L, 1, now)
            kotlinx.coroutines.delay(40)
            sendTouchEvent(MotionEvent.ACTION_UP, x, y, devW, devH, 0L, 0, now + 40)
            return@withContext
        }
        adbClient.executeShell("input tap $x $y")
    }

    suspend fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 250) = withContext(Dispatchers.IO) {
        val stream = controlStream
        if (stream != null && !stream.isClosed() && serverVideoWidth > 0 && serverVideoHeight > 0) {
            val (devW, devH) = getDeviceResolution()
            val start = SystemClock.uptimeMillis()
            sendTouchEvent(MotionEvent.ACTION_DOWN, x1, y1, devW, devH, 0L, 1, start)
            val steps = (durationMs / 12).coerceIn(8, 30)
            for (i in 1..steps) {
                val curX = x1 + ((x2 - x1) * i) / steps
                val curY = y1 + ((y2 - y1) * i) / steps
                val now = start + (durationMs * i / steps)
                sendTouchEvent(MotionEvent.ACTION_MOVE, curX, curY, devW, devH, 0L, 1, now)
                kotlinx.coroutines.delay((durationMs / steps).toLong().coerceAtLeast(5L))
            }
            sendTouchEvent(MotionEvent.ACTION_UP, x2, y2, devW, devH, 0L, 0, start + durationMs)
            return@withContext
        }
        adbClient.executeShell("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    suspend fun sendKey(keyCode: Int) = withContext(Dispatchers.IO) {
        val stream = controlStream
        if (stream != null && !stream.isClosed()) {
            try {
                // scrcpy 1.18 INJECT_KEYCODE 报文: 14 字节
                val downBuf = ByteBuffer.allocate(14)
                downBuf.put(0.toByte()) // TYPE_INJECT_KEYCODE = 0
                downBuf.put(0.toByte()) // ACTION_DOWN = 0
                downBuf.putInt(keyCode)
                downBuf.putInt(0) // repeat
                downBuf.putInt(0) // metaState
                controlQueue.offer(downBuf.array())

                val upBuf = ByteBuffer.allocate(14)
                upBuf.put(0.toByte()) // TYPE_INJECT_KEYCODE = 0
                upBuf.put(1.toByte()) // ACTION_UP = 1
                upBuf.putInt(keyCode)
                upBuf.putInt(0) // repeat
                upBuf.putInt(0) // metaState
                controlQueue.offer(upBuf.array())
                return@withContext
            } catch (_: Exception) {}
        }
        adbClient.executeShell("input keyevent $keyCode")
    }

    suspend fun sendBack() = sendKey(4)
    suspend fun sendHome() = sendKey(3)
    suspend fun sendAppSwitch() = sendKey(187)
    suspend fun sendPower() = sendKey(26)
    suspend fun sendVolumeUp() = sendKey(24)
    suspend fun sendVolumeDown() = sendKey(25)

    /**
     * 发送文本到被控机剪贴板，支持可选自动粘贴
     */
    suspend fun setDeviceClipboard(text: String, paste: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val stream = controlStream
        if (stream != null && !stream.isClosed()) {
            try {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                val buf = ByteBuffer.allocate(1 + 1 + 4 + textBytes.size)
                buf.put(9.toByte()) // TYPE_SET_CLIPBOARD = 9
                buf.put(if (paste) 1.toByte() else 0.toByte())
                buf.putInt(textBytes.size)
                buf.put(textBytes)
                controlQueue.offer(buf.array())
                Log.i(TAG, "Sent clipboard to device via scrcpy control (size=${textBytes.size}, paste=$paste)")
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send clipboard via scrcpy control stream: ${e.message}")
            }
        }

        // 降级兜底方案：ADB Shell cmd clipboard set-text
        try {
            val escaped = text.replace("'", "'\\''")
            adbClient.executeShell("cmd clipboard set-text '$escaped'")
            if (paste) {
                adbClient.executeShell("input keyevent 279") // KEYCODE_PASTE
            }
            Log.i(TAG, "Set clipboard via adb shell fallback")
            return@withContext true
        } catch (e: Exception) {
            Log.w(TAG, "Fallback set clipboard failed: ${e.message}")
            return@withContext false
        }
    }

    /**
     * 主动向被控机请求获取当前剪贴板文本
     */
    suspend fun getDeviceClipboard(): String? = withContext(Dispatchers.IO) {
        val stream = controlStream
        if (stream != null && !stream.isClosed()) {
            try {
                val deferred = CompletableDeferred<String>()
                getClipboardDeferred = deferred
                controlQueue.offer(byteArrayOf(8.toByte())) // TYPE_GET_CLIPBOARD = 8
                val result = withTimeoutOrNull(1500) {
                    deferred.await()
                }
                if (result != null) {
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Get clipboard via control stream failed or timeout: ${e.message}")
            } finally {
                getClipboardDeferred = null
            }
        }

        // 降级兜底方案：ADB Shell cmd clipboard get-text
        try {
            val out = adbClient.executeShell("cmd clipboard get-text")
            val trimmed = out.trimEnd('\r', '\n')
            if (trimmed.isNotEmpty()) {
                _deviceClipboard.set(trimmed)
                return@withContext trimmed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback get clipboard failed: ${e.message}")
        }
        return@withContext _deviceClipboard.get().ifEmpty { null }
    }
}
