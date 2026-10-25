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
     * 动态设置被控机外放静音/恢复
     */
    fun setDeviceMuted(client: AdbClient?, muted: Boolean) {
        val targetClient = client ?: lastAdbClient ?: return
        isDeviceMuted = muted
        Thread({
            try {
                if (muted) {
                    targetClient.executeShell("cmd media_session volume --stream 3 --set 1 2>/dev/null; media volume --stream 3 --set 1 2>/dev/null", 2000)
                    Log.i(TAG, "已设置被控端微外放音量")
                } else {
                    targetClient.executeShell("cmd audio set-volume-key-mute 3 0 2>/dev/null; cmd media_session volume --stream 3 --set 10 2>/dev/null; media volume --stream 3 --set 10 2>/dev/null; input keyevent 24 24 24 2>/dev/null", 2000)
                    Log.i(TAG, "已恢复被控端正常媒体外放音量")
                }
            } catch (e: Exception) {
                Log.w(TAG, "设置对端静音失败: ${e.message}")
            }
        }, "MuteDeviceThread").start()
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
            // 确保被控端音量正常，解除此前残留的静音状态
            ensureDeviceVolumeNormal(adbClient)

            if (muteDevice) {
                setDeviceMuted(adbClient, true)
            }

            // 模式 3: Sndcpy 伴侣模式 (Android 10+ 官方稳定系统内录，全机型支持)
            if (audioSource == 3) {
                startSndcpy(adbClient)
                if (muteDevice) {
                    setDeviceMuted(adbClient, true)
                }
                return@withContext
            }

            // 模式 0/1/2: 免安装 AudioForwarder 模式
            startAudioForwarder(adbClient, audioSource)
            if (muteDevice) {
                setDeviceMuted(adbClient, true)
            }

        } catch (e: Exception) {
            val err = "音频转发启动失败: ${e.message}"
            Log.e(TAG, err, e)
            onStatus?.invoke(err, true)
            stop()
        }
    }

    private fun ensureDeviceVolumeNormal(client: AdbClient) {
        Thread({
            try {
                val cmd = "cmd audio set-volume-key-mute 3 0 2>/dev/null; " +
                        "cmd media_session volume --stream 3 --set 10 2>/dev/null; " +
                        "media volume --stream 3 --set 10 2>/dev/null; " +
                        "settings put system volume_music 10 2>/dev/null; " +
                        "input keyevent 24 24 24 2>/dev/null"
                client.executeShell(cmd, 2500)
            } catch (_: Exception) {}
        }, "RestoreVolumeThread").start()
    }

    /**
     * 通过 Sndcpy 伴侣应用捕获纯系统声音 (Android 10+)
     */
    private suspend fun startSndcpy(adbClient: AdbClient) {
        onStatus?.invoke("正在连接 Sndcpy 音频服务...", false)

        // 1. 优先尝试直连已有服务 (若被控端已有 Sndcpy 正在运行或等待连接中，则无需重新拉起弹窗)
        var stream: AdbStream? = null
        for (i in 1..8) {
            if (!isPlaying.get()) break
            try {
                stream = adbClient.connection.openStream("localabstract:sndcpy", 1200)
                Log.i(TAG, "检测到对端已有 Sndcpy 等待连接中，直连握手成功！")
                break
            } catch (_: Exception) {
                Thread.sleep(200)
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
                    onStatus?.invoke("Sndcpy 组件安装失败，正在切换至原生系统内录...", false)
                    startAudioForwarder(adbClient, 0)
                    return
                }
            }

            // 唤醒屏幕 (保留已有服务状态，绝不盲目 force-stop 扼杀已就绪的等待服务)
            try {
                adbClient.executeShell("input keyevent 224 2>/dev/null")
            } catch (_: Exception) {}

            // 预先尝试授权 PROJECT_MEDIA 与 RECORD_AUDIO
            try {
                adbClient.executeShell("cmd appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null")
                adbClient.executeShell("appops set $SNDCPY_PACKAGE PROJECT_MEDIA allow 2>/dev/null")
                adbClient.executeShell("pm grant $SNDCPY_PACKAGE android.permission.RECORD_AUDIO 2>/dev/null")
                adbClient.executeShell("cmd appops set $SNDCPY_PACKAGE RECORD_AUDIO allow 2>/dev/null")
            } catch (_: Exception) {}

            // 启动 Sndcpy 捕获服务
            onStatus?.invoke("正在唤醒 Sndcpy 服务 (若被控端提示等待或弹出授权请允许)...", false)
            adbClient.executeShell("am start -n $SNDCPY_PACKAGE/.MainActivity 2>/dev/null")

            // 异步尝试自动确认系统录屏/录音安全弹窗 (右键定位“立即开始/允许”并回车确认)
            Thread({
                for (step in 1..4) {
                    if (!isPlaying.get() || audioStream != null) break
                    try {
                        Thread.sleep(800)
                        if (!isPlaying.get() || audioStream != null) break
                        adbClient.executeShell("input keyevent 22 && input keyevent 66 2>/dev/null")
                    } catch (_: Exception) {}
                }
            }, "AutoAcceptProjectionThread").start()

            // 充裕时间轮询等待连接 localabstract:sndcpy (60 次，每次 350ms，总计约 22 秒)
            for (i in 1..60) {
                if (!isPlaying.get()) break
                try {
                    stream = adbClient.connection.openStream("localabstract:sndcpy", 1200)
                    Log.i(TAG, "Sndcpy 服务已成功连接！")
                    break
                } catch (_: Exception) {
                    if (i % 3 == 0) {
                        val passedSec = (i * 350) / 1000
                        onStatus?.invoke("等待 Sndcpy 握手连接 (${passedSec}s/22s，被控端显示等待请保持)...", false)
                    }
                    Thread.sleep(350)
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

        playbackThread = Thread({
            val buffer = ByteArray(4096)
            val input = stream.inputStream
            var totalBytesReceived = 0L
            var hasNotifiedFirstData = false

            try {
                while (isPlaying.get()) {
                    val read = input.read(buffer, 0, buffer.size)
                    if (read > 0) {
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

    /**
     * 停止音频播放并释放资源
     */
    fun stop() {
        if (!isPlaying.getAndSet(false)) return

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
