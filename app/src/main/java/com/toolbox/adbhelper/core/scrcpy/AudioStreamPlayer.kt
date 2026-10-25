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
    private var focusRequest: Any? = null

    @Volatile
    var audioGain: Float = 1.0f

    @Volatile
    var audioQualityMode: Int = 0 // 0: 听歌高保真, 1: 极速低延迟

    @Volatile
    var isDeviceMuted: Boolean = false
        private set

    /**
     * 状态或错误回调通知
     */
    var onStatus: ((message: String, isError: Boolean) -> Unit)? = null

    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * 动态设置被控机物理外放静音（将外放音量置为微音量1格，仅将内录音频传输给控制端播放）
     */
    fun setDeviceMuted(client: AdbClient?, muted: Boolean) {
        val targetClient = client ?: lastAdbClient ?: return
        isDeviceMuted = muted
        Thread({
            try {
                if (muted) {
                    // 1. 优先调用 app-helper.jar 的系统原生 AudioManager.setStreamVolume(3, 1, 8)
                    // 2. 结合 media volume / cmd media_session 将音量设为 1 格微音量
                    // 核心技术规范：Android 系统的 AudioPlaybackCapture 与内录基于混音器输出；
                    // 若直接置为 0 会导致混音采样乘零全哑。设为 1 格可在被控端扬声器极微弱无扰的前提下，保留完整内录 PCM 信号！
                    val muteCmd = "cmd audio set-volume-key-mute 3 0 2>/dev/null; " +
                            "CLASSPATH=/data/local/tmp/app-helper.jar app_process / com.toolbox.adbhelper.server.AppHelper volume 1 2>/dev/null; " +
                            "cmd media_session volume --stream 3 --set 1 2>/dev/null; " +
                            "media volume --stream 3 --set 1 2>/dev/null; " +
                            "settings put system volume_music_speaker 1 2>/dev/null; " +
                            "settings put system volume_music 1 2>/dev/null"
                    val out = targetClient.executeShell(muteCmd, 3500)
                    Log.i(TAG, "已安全设置被控端外放静音(音量1格微外放保持内录), 命令输出: $out")
                } else {
                    // 恢复被控机物理外放音量至正常级别（约 10 格，满格15）
                    // 必须先解除底层 set-volume-key-mute 静音，并补发多次音量加键 (keyevent 24) 解除底层按键静音状态
                    val unmuteCmd = "cmd audio set-volume-key-mute 3 0 2>/dev/null; " +
                            "CLASSPATH=/data/local/tmp/app-helper.jar app_process / com.toolbox.adbhelper.server.AppHelper volume 10 2>/dev/null; " +
                            "cmd media_session volume --stream 3 --set 10 2>/dev/null; " +
                            "media volume --stream 3 --set 10 2>/dev/null; " +
                            "settings put system volume_music_speaker 10 2>/dev/null; " +
                            "settings put system volume_music 10 2>/dev/null; " +
                            "input keyevent 24 24 24 2>/dev/null"
                    val out = targetClient.executeShell(unmuteCmd, 3500)
                    Log.i(TAG, "已恢复被控端正常媒体外放音量(音量10), 命令输出: $out")
                }
            } catch (e: Exception) {
                Log.w(TAG, "设置对端静音失败: ${e.message}")
            }
        }, "MuteDeviceThread").start()
    }

    /**
     * 启动音频转发与本机播放
     * @param audioSource 3: Sndcpy伴侣系统内录, 0: 自动系统内录, 1: 麦克风音源, 2: 语音识别音源
     * @param qualityMode 0: 高保真音乐模式(300ms防爆音大缓冲), 1: 极速低延迟模式(30ms游戏响应)
     * @param gain 音频数字增益(1.0f~3.0f)
     * @param muteDevice 是否静音被控端物理外放
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
            // 检查本机媒体音量
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
            if (currentVol == 0) {
                onStatus?.invoke("提示：本机当前媒体音量为静音，请按音量键调大", false)
            }

            // 关键：启动音频前强制同步对端物理外放状态，未勾选静音时立即恢复正常发声(消除此前可能残留的静音状态)
            setDeviceMuted(adbClient, muteDevice)

            // 模式 3: Sndcpy 伴侣模式 (Android 10+ 官方稳定系统内录)
            if (audioSource == 3) {
                startSndcpy(adbClient, muteDevice)
                return@withContext
            }

            // 模式 0/1/2: 免安装 AudioForwarder 模式
            startAudioForwarder(adbClient, audioSource, muteDevice)

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
    private suspend fun startSndcpy(adbClient: AdbClient, muteDevice: Boolean = false) {
        onStatus?.invoke("正在连接 Sndcpy 音频服务...", false)

        // 异步确保 app-helper.jar 就绪（供后续系统级静音调用）
        Thread({
            try {
                ensureHelperPushed(context, adbClient)
            } catch (_: Exception) {}
        }, "AsyncPushHelperThread").start()

        // 步骤 1: 优先尝试直接连接已有服务（如果对端已弹出并显示等待连接中，直接秒连！）
        var stream: AdbStream? = null
        for (i in 0 until 10) {
            if (!isPlaying.get()) break
            try {
                stream = adbClient.connection.openStream("localabstract:sndcpy", 500)
                Log.i(TAG, "检测到对端已有 Sndcpy 等待连接中，直连握手成功！")
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }

        // 步骤 2: 若未直接连上，才进行拉起服务与自动确认
        if (stream == null) {
            val checkInstalled = adbClient.executeShell("pm path $SNDCPY_PACKAGE 2>/dev/null")
            if (!checkInstalled.contains("package:")) {
                onStatus?.invoke("正在向被控端安装 Sndcpy 录音组件...", false)
                val installSuccess = installSndcpyApk(adbClient)
                if (!installSuccess) {
                    onStatus?.invoke("Sndcpy 安装失败，正在切换至原生系统内录...", false)
                    startAudioForwarder(adbClient, 0, muteDevice)
                    return
                }
            }

            // 启动前先停止旧实例，保证拉起全新任务并触发 MediaProjection 授权
            try {
                adbClient.executeShell("am force-stop $SNDCPY_PACKAGE 2>/dev/null", 2000)
            } catch (_: Exception) {}

            // 合并执行：预授权、屏幕唤醒与拉起 Sndcpy MainActivity（合并为一条指令，大幅减少3~4秒的开销与延迟）
            onStatus?.invoke("正在唤醒 Sndcpy 服务 (若对端弹出权限请允许)...", false)
            val prepCmd = "cmd appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null; " +
                    "appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null; " +
                    "pm grant $SNDCPY_PACKAGE android.permission.RECORD_AUDIO 2>/dev/null; " +
                    "input keyevent 224 2>/dev/null; " +
                    "am start -n $SNDCPY_PACKAGE/.MainActivity 2>/dev/null"
            try {
                adbClient.executeShell(prepCmd, 4000)
            } catch (_: Exception) {}

            // 异步自动点击弹窗 (右键 22 + 回车 66 或 下键 20 + 回车 66)
            Thread({
                for (step in 1..4) {
                    if (!isPlaying.get() || audioStream != null) break
                    try {
                        Thread.sleep(600)
                        adbClient.executeShell("input keyevent 22 66 2>/dev/null", 1500)
                        Thread.sleep(300)
                        adbClient.executeShell("input keyevent 20 66 2>/dev/null", 1500)
                    } catch (_: Exception) {}
                }
            }, "AutoAcceptProjectionThread").start()

            // 快速高频轮询等待连接（50 次，每次 150ms，最长 7.5 秒）
            for (i in 1..50) {
                if (!isPlaying.get()) break
                try {
                    stream = adbClient.connection.openStream("localabstract:sndcpy", 600)
                    break
                } catch (_: Exception) {
                    Thread.sleep(150)
                }
            }
        }

        if (stream == null) {
            Log.w(TAG, "Sndcpy 未能在时限内连接，自动降级至系统原生免弹窗内录")
            onStatus?.invoke("Sndcpy 授权未就绪，已自动平滑降级至免弹窗系统内录...", false)
            startAudioForwarder(adbClient, 0, muteDevice)
            return
        }
        audioStream = stream

        initAudioTrackAndPlay(stream, "Sndcpy 高清系统内录")

        // 音频流稳定连接后，按设置同步对端外放音量状态
        setDeviceMuted(adbClient, muteDevice)
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
    private suspend fun startAudioForwarder(adbClient: AdbClient, audioSource: Int, muteDevice: Boolean = false) {
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
            adbClient.executeShell("input keyevent 224") // 唤醒屏幕
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
                        if (line.contains("AUDIO_RECORDING_STARTED")) {
                            onStatus?.invoke("音频录制已启动，正在流式传输", false)
                        } else if (line.contains("Selected strategy: AudioPolicy")) {
                            onStatus?.invoke("音频录制：系统内录 (AudioPolicy)", false)
                        } else if (line.contains("Selected strategy:")) {
                            onStatus?.invoke("音频录制：系统声音内录 (REMOTE_SUBMIX)", false)
                        } else if (line.contains("Selected fallback:") || line.contains("explicit MIC")) {
                            onStatus?.invoke("音频录制：麦克风采集模式", false)
                        } else if (line.contains("Stream active:")) {
                            onStatus?.invoke("对端音频活跃传输中", false)
                        } else if (line.contains("ERROR:") || line.contains("Permission Denial") || line.contains("failed with code")) {
                            onStatus?.invoke("对端音频捕获提示: $line", true)
                        }
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
            val err = "无法连接对端音频管道 (scrcpy_audio)，对端可能尚未启动录音"
            Log.w(TAG, err)
            onStatus?.invoke(err, true)
            stop()
            return
        }
        audioStream = stream

        initAudioTrackAndPlay(stream, "音频已连接 (48kHz 双声道低延迟)")

        setDeviceMuted(adbClient, muteDevice)
    }

    /**
     * 初始化 AudioTrack 并启动消费线程
     */
    private fun initAudioTrackAndPlay(stream: AdbStream, successMessage: String) {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        // 模式 0 (听歌高保真)：8倍缓冲 (约 300ms 动态防抖防爆音)；模式 1 (极速低延迟)：2倍缓冲 (约 30ms 响应)
        val bufferMultiplier = if (audioQualityMode == 0) 8 else 2
        val bufferSize = (minBufSize * bufferMultiplier).coerceAtLeast(if (audioQualityMode == 0) 32768 else 4096)

        // 关键：在主控端主动申请系统音频焦点 (Audio Focus)，确保底层 AudioPolicy 不会静音当前应用
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .build()
                focusRequest = req
                audioManager?.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (_: Exception) {}

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

        try {
            track.setVolume(1.0f)
        } catch (_: Exception) {}

        audioTrack = track
        track.play()
        val modeDesc = if (audioQualityMode == 0) "高保真听歌模式 (48kHz 防爆音)" else "极速低延迟模式 (30ms响应)"
        Log.i(TAG, "AudioTrack 初始化成功 ($modeDesc)，开始实时播放")
        onStatus?.invoke("$successMessage [$modeDesc]", false)

        playbackThread = Thread({
            val buffer = ByteArray(4096)
            val input = stream.inputStream
            var totalBytesReceived = 0L
            var lastDiagTime = System.currentTimeMillis()
            var periodMaxPeak = 0

            try {
                while (isPlaying.get()) {
                    val read = input.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // 实时计算本次 PCM 数据块的最大绝对值振幅
                        var blockPeak = 0
                        var idx = 0
                        val safeRead = read - (read % 2)
                        while (idx < safeRead) {
                            val low = buffer[idx].toInt() and 0xFF
                            val high = buffer[idx + 1].toInt()
                            val sample = ((high shl 8) or low).toShort().toInt()
                            val abs = Math.abs(sample)
                            if (abs > blockPeak) blockPeak = abs
                            idx += 2
                        }
                        if (blockPeak > periodMaxPeak) periodMaxPeak = blockPeak

                        // 动态软件数字增益算法 (16-bit PCM 立体声无损放大与削波平滑)
                        val effectiveGain = if (isDeviceMuted && audioGain <= 1.05f) {
                            // 当被控端处于微音量静音模式且未额外配置强增益时，自动补偿 3.0x 数字增益，保证控制端发声音量充沛
                            3.0f
                        } else {
                            audioGain
                        }

                        if (effectiveGain > 1.05f) {
                            applyPcmGain(buffer, read, effectiveGain)
                        }

                        track.write(buffer, 0, read)
                        totalBytesReceived += read

                        val now = System.currentTimeMillis()
                        if (now - lastDiagTime >= 2500L) {
                            lastDiagTime = now
                            val peak = periodMaxPeak
                            periodMaxPeak = 0
                            if (peak == 0) {
                                onStatus?.invoke("音频通道已就绪 [对端信号电平: 0% 静音，请播放音乐测试]", false)
                            } else {
                                val percent = (peak * 100 / 32767).coerceIn(1, 100)
                                onStatus?.invoke("音频实时播放中 [信号电平: $percent%]", false)
                            }
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

    /**
     * 对 16-bit PCM (Little-Endian) 字节数组进行实时平滑数字增益计算并做软削波保护
     */
    private fun applyPcmGain(data: ByteArray, length: Int, gain: Float) {
        val safeLen = length - (length % 2)
        var i = 0
        while (i < safeLen) {
            val low = data[i].toInt() and 0xFF
            val high = data[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()

            val amplified = (sample * gain).toInt().coerceIn(-32768, 32767)
            data[i] = (amplified and 0xFF).toByte()
            data[i + 1] = ((amplified shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * 停止音频播放并释放资源
     */
    fun stop() {
        if (!isPlaying.getAndSet(false)) return

        // 注意：严禁在音频流中断或停止播放时擅自调高被控端物理外放音量！
        // 用户的被控端静音设置属于全局持久化偏好，仅允许在用户明确取消静音勾选或销毁投屏时主动恢复。

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

        // 释放主控端申请的音频焦点
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? android.media.AudioFocusRequest)?.let {
                    audioManager?.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
        focusRequest = null

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
