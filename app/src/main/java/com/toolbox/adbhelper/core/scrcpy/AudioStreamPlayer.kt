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

    /**
     * 状态或错误回调通知
     */
    var onStatus: ((message: String, isError: Boolean) -> Unit)? = null

    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * 动态设置被控机外放静音（仅留1格微音量传输给远端，防止硬件扬声器扰民）
     */
    fun setDeviceMuted(client: AdbClient?, muted: Boolean) {
        val targetClient = client ?: lastAdbClient ?: return
        isDeviceMuted = muted
        Thread({
            try {
                if (muted) {
                    targetClient.executeShell("cmd media_session volume --stream 3 --set 1 2>/dev/null || media volume --stream 3 --set 1 2>/dev/null")
                    Log.i(TAG, "已设置被控端媒体外放微音量/静音")
                } else {
                    targetClient.executeShell("cmd media_session volume --stream 3 --set 10 2>/dev/null || media volume --stream 3 --set 10 2>/dev/null")
                    Log.i(TAG, "已恢复被控端正常媒体外放音量")
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

        if (muteDevice) {
            setDeviceMuted(adbClient, true)
        }

        try {
            // 检查本机媒体音量
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
            if (currentVol == 0) {
                onStatus?.invoke("提示：本机当前媒体音量为静音，请按音量键调大", false)
            }

            // 模式 3: Sndcpy 伴侣模式 (Android 10+ 官方稳定系统内录)
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

        // 1. 检查被控端是否已安装 Sndcpy APK，未安装则自动无感安装
        val checkInstalled = adbClient.executeShell("pm path $SNDCPY_PACKAGE 2>/dev/null")
        if (!checkInstalled.contains("package:")) {
            onStatus?.invoke("正在向被控端安装 Sndcpy 录音组件...", false)
            val installSuccess = installSndcpyApk(adbClient)
            if (!installSuccess) {
                onStatus?.invoke("Sndcpy 组件安装失败，正尝试切换至麦克风...", true)
                startAudioForwarder(adbClient, 1)
                return
            }
        }

        // 2. 授权 PROJECT_MEDIA (截取系统音频权限)
        try {
            adbClient.executeShell("cmd appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null")
            adbClient.executeShell("appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null")
            adbClient.executeShell("pm grant $SNDCPY_PACKAGE android.permission.RECORD_AUDIO 2>/dev/null")
            adbClient.executeShell("cmd appops set $SNDCPY_PACKAGE RECORD_AUDIO allow 2>/dev/null")
        } catch (_: Exception) {}

        // 3. 启动 Sndcpy 捕获服务
        onStatus?.invoke("正在唤醒 Sndcpy 服务 (若被控端弹出录屏/录音授权请点击允许)...", false)
        adbClient.executeShell("am start -n $SNDCPY_PACKAGE/.MainActivity 2>/dev/null")

        // 4. 连接 localabstract:sndcpy
        var stream: AdbStream? = null
        for (i in 0 until 40) {
            if (!isPlaying.get()) break
            try {
                stream = adbClient.connection.openStream("localabstract:sndcpy", 1000)
                break
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }

        if (stream == null) {
            val err = "未能连接 sndcpy 服务 (请确认被控端屏幕已允许音频捕获弹窗)"
            Log.w(TAG, err)
            onStatus?.invoke(err, true)
            stop()
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
    }

    /**
     * 初始化 AudioTrack 并启动消费线程
     */
    private fun initAudioTrackAndPlay(stream: AdbStream, successMessage: String) {
        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        // 模式 0 (听歌高保真)：8倍缓冲 (约 300ms 动态防抖防爆音)；模式 1 (极速低延迟)：2倍缓冲 (约 30ms 响应)
        val bufferMultiplier = if (audioQualityMode == 0) 8 else 2
        val bufferSize = (minBufSize * bufferMultiplier).coerceAtLeast(if (audioQualityMode == 0) 32768 else 4096)

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
        val modeDesc = if (audioQualityMode == 0) "高保真听歌模式 (48kHz 防爆音)" else "极速低延迟模式 (30ms响应)"
        Log.i(TAG, "AudioTrack 初始化成功 ($modeDesc)，开始实时播放")
        onStatus?.invoke("$successMessage [$modeDesc]", false)

        playbackThread = Thread({
            val buffer = ByteArray(4096)
            val input = stream.inputStream
            var totalBytesReceived = 0L
            var hasNotifiedFirstData = false

            try {
                while (isPlaying.get()) {
                    val read = input.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // 动态软件数字增益算法 (16-bit PCM 立体声无损放大与削波平滑)
                        val currentGain = audioGain
                        if (currentGain > 1.05f) {
                            applyPcmGain(buffer, read, currentGain)
                        }

                        track.write(buffer, 0, read)
                        totalBytesReceived += read

                        if (!hasNotifiedFirstData && totalBytesReceived >= 4096) {
                            hasNotifiedFirstData = true
                            onStatus?.invoke("音频稳定传输中 (48kHz立体声)", false)
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
            var sample = (high shl 8) or low

            var amplified = (sample * gain).toInt()
            if (amplified > 32767) {
                amplified = 32767
            } else if (amplified < -32768) {
                amplified = -32768
            }

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

        // 若之前设置了静音，尝试恢复被控端外放音量
        if (isDeviceMuted) {
            setDeviceMuted(lastAdbClient, false)
        }

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
