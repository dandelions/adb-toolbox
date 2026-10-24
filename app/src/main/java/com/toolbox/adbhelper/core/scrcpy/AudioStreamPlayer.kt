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
    }

    private val isPlaying = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var serverStream: AdbStream? = null
    private var audioStream: AdbStream? = null
    private var playbackThread: Thread? = null

    /**
     * 状态或错误回调通知
     */
    var onStatus: ((message: String, isError: Boolean) -> Unit)? = null

    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * 启动音频转发与本机播放
     * @param audioSource 0: 自动系统内录, 1: 麦克风音源, 2: 语音识别音源
     */
    suspend fun start(adbClient: AdbClient, audioSource: Int = 0) = withContext(Dispatchers.IO) {
        if (isPlaying.get()) return@withContext
        isPlaying.set(true)

        try {
            onStatus?.invoke("正在准备音频转发组件...", false)
            ensureHelperPushed(context, adbClient)

            // 检查本机媒体音量
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
            if (currentVol == 0) {
                onStatus?.invoke("提示：本机当前媒体音量为静音，请按音量键调大", false)
            }

            val modeName = when (audioSource) {
                1 -> "麦克风 (MIC)"
                2 -> "语音识别"
                else -> "自动 (系统内录优先)"
            }
            // 1. 在对端启动 AudioForwarder 服务进程
            onStatus?.invoke("启动对端音频服务 [$modeName]...", false)

            // 唤醒屏幕并确保 UID 2000 录音权限，同时主动清理残留的堆转储请求与文件
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
                return@withContext
            }

            // 异步消费 serverStream 输出并分析状态
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

            // 2. 轮询连接 localabstract:scrcpy_audio
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
                return@withContext
            }
            audioStream = stream

            // 3. 初始化本地 AudioTrack
            val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
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
            onStatus?.invoke("音频已连接 (48kHz 双声道低延迟)", false)

            // 4. 启动音频播放消费线程
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

        } catch (e: Exception) {
            val err = "音频转发启动失败: ${e.message}"
            Log.e(TAG, err, e)
            onStatus?.invoke(err, true)
            stop()
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

            // 严格比对文件大小，一旦版本不一致立即无缝更新推送
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
