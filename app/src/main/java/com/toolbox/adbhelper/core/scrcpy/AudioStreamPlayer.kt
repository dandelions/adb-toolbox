package com.toolbox.adbhelper.core.scrcpy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AudioStreamPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioStreamPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SNDCPY_PACKAGE = "com.rom1v.sndcpy"
    }

    private val isPlaying = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var serverStream: AdbStream? = null
    private var audioStream: AdbStream? = null
    private var playbackThread: Thread? = null
    private var lastAdbClient: AdbClient? = null

    @Volatile
    var audioGain: Float = 1.0f

    @Volatile
    var audioQualityMode: Int = 0 // 0: 听歌高保真, 1: 极速低延迟

    @Volatile
    var isDeviceMuted: Boolean = false
        private set

    @Volatile
    var isSoundActive: Boolean = false
        private set

    @Volatile
    var lastActiveSoundTime: Long = 0L
        private set

    var onSoundActiveChanged: ((Boolean) -> Unit)? = null

    fun hasRecentSound(timeoutMs: Long = 3000L): Boolean {
        val last = lastActiveSoundTime
        return last > 0L && (System.currentTimeMillis() - last) < timeoutMs
    }

    fun getLastSoundTime(): Long = lastActiveSoundTime

    /**
     * 状态或错误回调通知
     */
    var onStatus: ((message: String, isError: Boolean) -> Unit)? = null

    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * 动态设置被控机外放静音/恢复（遵循用户需求，不强制篡改系统物理音量）
     */
    fun setDeviceMuted(client: AdbClient?, muted: Boolean) {
        isDeviceMuted = muted
    }

    /**
     * 启动音频转发与本机播放
     */
    suspend fun start(
        adbClient: AdbClient,
        audioSource: Int = 3,
        qualityMode: Int = 0,
        gain: Float = 1.0f,
        muteDevice: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (isPlaying.get()) return@withContext
        isPlaying.set(true)
        lastAdbClient = adbClient
        this@AudioStreamPlayer.audioQualityMode = qualityMode
        this@AudioStreamPlayer.audioGain = gain

        try {
            // 模式 3: Sndcpy 伴侣模式 (Android 10+ 官方稳定系统内录，全机型支持)
            if (audioSource == 3) {
                startSndcpy(adbClient)
                return@withContext
            }

            // 模式 0/1/2: 免安装 AudioForwarder 模式
            startAudioForwarder(adbClient, audioSource)

        } catch (e: Exception) {
            val err = "音频转发启动失败: ${e.message}"
            Log.e(TAG, err, e)
            onStatus?.invoke(err, true)
            stop()
        }
    }

    /**
     * 通过 Sndcpy 伴侣应用捕获纯系统声音 (Android 10+)
     */
    private suspend fun startSndcpy(adbClient: AdbClient) {
        onStatus?.invoke("准备 Sndcpy 系统内录组件...", false)

        // 1. 优先尝试直连已有服务 (若被控端已有 Sndcpy 正在运行或等待连接中，则无需重新拉起弹窗)
        var stream: AdbStream? = null
        for (i in 1..8) {
            if (!isPlaying.get()) break
            try {
                stream = adbClient.connection.openStream("localabstract:sndcpy", 1200)
                Log.i(TAG, "检测到对端已有 Sndcpy 等待连接中，直连握手成功！")
                break
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }

        // 2. 若未直连成功，则确保环境就绪并拉起服务与等待授权
        if (stream == null) {
            // 检查被控端是否已安装 Sndcpy APK，未安装则自动无感安装
            val checkInstalled = adbClient.executeShell("pm path $SNDCPY_PACKAGE 2>/dev/null")
            if (!checkInstalled.contains("package:")) {
                onStatus?.invoke("正在向被控端安装 Sndcpy 录音组件...", false)
                val installSuccess = installSndcpyApk(adbClient)
                if (!installSuccess) {
                    onStatus?.invoke("Sndcpy 组件安装失败，正在切换至免安装系统内录...", false)
                    startAudioForwarder(adbClient, 0)
                    return
                }
            }

            // 预先尝试静默授权 PROJECT_MEDIA 与 RECORD_AUDIO
            try {
                adbClient.executeShell("cmd appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null")
                adbClient.executeShell("appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null")
                adbClient.executeShell("pm grant $SNDCPY_PACKAGE android.permission.RECORD_AUDIO 2>/dev/null")
                adbClient.executeShell("cmd appops set $SNDCPY_PACKAGE RECORD_AUDIO allow 2>/dev/null")
            } catch (_: Exception) {}

            // 启动 Sndcpy 捕获服务
            onStatus?.invoke("正在唤醒 Sndcpy 服务 (若屏幕弹出录屏/录音授权请点击允许)...", false)
            adbClient.executeShell("am start -n $SNDCPY_PACKAGE/.MainActivity 2>/dev/null")

            // 经典充裕时间轮询等待连接 localabstract:sndcpy
            for (i in 1..40) {
                if (!isPlaying.get()) break
                try {
                    stream = adbClient.connection.openStream("localabstract:sndcpy", 1200)
                    Log.i(TAG, "Sndcpy 服务已成功连接！")
                    break
                } catch (_: Exception) {
                    if (i % 4 == 0) {
                        onStatus?.invoke("等待 Sndcpy 授权握手 (${i / 4}s/10s，若有弹窗请点击允许)...", false)
                    }
                    Thread.sleep(250)
                }
            }
        }

        // 3. 若最终仍未连上 Sndcpy，平滑自动降级至免安装免弹窗的系统原生内录，杜绝中断报错
        if (stream == null) {
            Log.w(TAG, "未能连接 Sndcpy 服务 (被控端未确认弹窗或超时)，平滑自动降级至原生免弹窗系统内录")
            onStatus?.invoke("Sndcpy 授权未就绪，已平滑切换至免弹窗系统内录...", false)
            startAudioForwarder(adbClient, 0)
            return
        }
        audioStream = stream

        initAudioTrackAndPlay(stream, "Sndcpy 高清系统内录")
    }

    /**
     * 将 assets/sndcpy.apk 推送到被控端并执行 pm install
     */
    private fun installSndcpyApk(client: AdbClient): Boolean {
        return try {
            val assetBytes = context.assets.open("sndcpy.apk").use { it.readBytes() }
            val tempFile = File(context.cacheDir, "sndcpy.apk")
            tempFile.writeBytes(assetBytes)

            val sync = client.openSync()
            try {
                sync.pushFile(tempFile, "/data/local/tmp/sndcpy.apk")
            } finally {
                sync.close()
                tempFile.delete()
            }

            client.executeShell("chmod 755 /data/local/tmp/sndcpy.apk")
            val installResult = client.executeShell("pm install -r -g -t /data/local/tmp/sndcpy.apk")
            client.executeShell("rm -f /data/local/tmp/sndcpy.apk 2>/dev/null")
            Log.i(TAG, "Sndcpy install output: $installResult")
            installResult.contains("Success") || installResult.contains("already installed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install sndcpy apk", e)
            false
        }
    }

    /**
     * 免安装模式：通过 app-helper.jar 的 AudioForwarder 服务
     */
    private suspend fun startAudioForwarder(adbClient: AdbClient, audioSource: Int) {
        onStatus?.invoke("正在准备音频转发组件...", false)
        ensureHelperPushed(context, adbClient)

        val modeName = when (audioSource) {
            1 -> "麦克风 (MIC)"
            2 -> "语音识别"
            else -> "自动 (系统内录优先)"
        }
        onStatus?.invoke("启动对端音频服务 [$modeName]...", false)

        try {
            adbClient.executeShell("pkill -9 -f com.toolbox.adbhelper.server.AudioForwarder 2>/dev/null")
            adbClient.executeShell("input keyevent 224 2>/dev/null") // 唤醒屏幕
            adbClient.executeShell("cmd appops set 2000 RECORD_AUDIO allow 2>/dev/null")
            adbClient.executeShell("cmd appops set com.android.shell RECORD_AUDIO allow 2>/dev/null")
            adbClient.executeShell("am broadcast -a com.android.shell.action.DELETE_HEAP_DUMP 2>/dev/null")
            adbClient.executeShell("rm -f /data/local/tmp/*.hprof /data/local/tmp/heapdump* 2>/dev/null")
        } catch (_: Exception) {}

        val modeArg = when (audioSource) {
            1 -> "mic"
            2 -> "voicerecog"
            else -> "auto"
        }
        val serverCmd = "CLASSPATH=/data/local/tmp/app-helper.jar exec app_process / com.toolbox.adbhelper.server.AudioForwarder $modeArg"
        serverStream = try {
            adbClient.connection.openStream("shell:$serverCmd", 5000)
        } catch (e: Exception) {
            val err = "启动对端音频服务进程失败: ${e.message}"
            Log.w(TAG, err)
            onStatus?.invoke(err, true)
            stop()
            return
        }

        val sStream = serverStream
        if (sStream != null) {
            Thread({
                try {
                    val reader = sStream.inputStream.bufferedReader()
                    while (isPlaying.get()) {
                        val line = reader.readLine() ?: break
                        Log.d(TAG, "[AudioForwarder] $line")
                    }
                } catch (_: Exception) {}
            }, "AudioServerLogThread").apply { isDaemon = true; start() }
        }

        var stream: AdbStream? = null
        for (i in 0 until 30) {
            if (!isPlaying.get()) break
            try {
                stream = adbClient.connection.openStream("localabstract:scrcpy_audio", 800)
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }

        if (stream == null) {
            val err = "无法连接对端音频管道 (scrcpy_audio)"
            Log.w(TAG, err)
            onStatus?.invoke(err, true)
            stop()
            return
        }
        audioStream = stream

        initAudioTrackAndPlay(stream, "音频已连接 (48kHz 双声道低延迟)")
    }

    /**
     * 初始化 AudioTrack 并启动消费线程 (经典高稳定直通模式)
     */
    private fun initAudioTrackAndPlay(stream: AdbStream, successMessage: String) {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        // 经典稳定缓冲大小 (4倍最小缓冲，最少8192字节)
        val bufferSize = (minBufSize * 4).coerceAtLeast(8192)

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        audioTrack = track
        track.play()
        Log.i(TAG, "AudioTrack 初始化成功，开始实时播放")
        onStatus?.invoke(successMessage, false)

        // 启动系统级前台保活服务与唤醒锁，确保按 Home 键切后台后音频持续播放不中断
        AudioPlaybackService.start(context)

        playbackThread = Thread({
            val buffer = ByteArray(4096)
            val input = stream.inputStream
            var totalBytesReceived = 0L
            var hasNotifiedFirstData = false

            try {
                while (isPlaying.get()) {
                    val read = input.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val maxAmp = getMaxAmplitude(buffer, 0, read)
                        val now = System.currentTimeMillis()

                        if (maxAmp > 40) {
                            lastActiveSoundTime = now
                            if (!isSoundActive) {
                                isSoundActive = true
                                onSoundActiveChanged?.invoke(true)
                            }
                        } else {
                            if (isSoundActive && (now - lastActiveSoundTime >= 2500L)) {
                                isSoundActive = false
                                onSoundActiveChanged?.invoke(false)
                            }
                        }

                        track.write(buffer, 0, read)
                        totalBytesReceived += read

                        if (!hasNotifiedFirstData && totalBytesReceived >= 4096) {
                            hasNotifiedFirstData = true
                            onStatus?.invoke("声音传输中 (已接收数据流)", false)
                        }
                    } else if (read < 0) {
                        Log.i(TAG, "音频输入流已到达 EOF")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isPlaying.get()) {
                    Log.w(TAG, "音频流读取异常: ${e.message}")
                    onStatus?.invoke("音频流中断: ${e.message}", true)
                }
            } finally {
                stop()
            }
        }, "AudioPlaybackThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun getMaxAmplitude(buf: ByteArray, offset: Int, len: Int): Int {
        var max = 0
        var i = offset
        val end = offset + len - 1
        while (i < end) {
            val sample = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            val abs = Math.abs(sample)
            if (abs > max) {
                max = abs
            }
            i += 2
        }
        return max
    }

    /**
     * 停止音频播放并释放资源
     */
    fun stop() {
        if (!isPlaying.getAndSet(false)) return

        isSoundActive = false
        lastActiveSoundTime = 0L

        try {
            AudioPlaybackService.stop(context)
        } catch (_: Exception) {}

        try {
            audioTrack?.let {
                it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        audioTrack = null

        try {
            audioStream?.close()
        } catch (_: Exception) {}
        audioStream = null

        try {
            serverStream?.close()
        } catch (_: Exception) {}
        serverStream = null

        playbackThread?.interrupt()
        playbackThread = null
        Log.i(TAG, "音频转发已停止并安全释放")
    }

    private fun ensureHelperPushed(context: Context, client: AdbClient) {
        try {
            val assetBytes = context.assets.open("app-helper.jar").use { it.readBytes() }
            val expectedSize = assetBytes.size
            val check = client.executeShell("ls -l /data/local/tmp/app-helper.jar 2>/dev/null")

            if (!check.contains(expectedSize.toString())) {
                Log.i(TAG, "正在推送最新 app-helper.jar ($expectedSize 字节)...")
                val temp = File(context.cacheDir, "app-helper.jar")
                temp.writeBytes(assetBytes)

                val sync = client.openSync()
                try {
                    sync.pushFile(temp, "/data/local/tmp/app-helper.jar")
                } finally {
                    sync.close()
                    temp.delete()
                }
                client.executeShell("chmod 755 /data/local/tmp/app-helper.jar")
                Log.i(TAG, "app-helper.jar 推送完成并赋予读取执行权限")
            }
        } catch (e: Exception) {
            Log.w(TAG, "推送 app-helper.jar 异常: ${e.message}")
        }
    }
}
