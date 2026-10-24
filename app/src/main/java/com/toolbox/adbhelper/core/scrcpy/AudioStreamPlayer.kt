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
import java.io.FileOutputStream
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

    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * 启动音频转发与本机播放
     */
    suspend fun start(adbClient: AdbClient) = withContext(Dispatchers.IO) {
        if (isPlaying.get()) return@withContext
        isPlaying.set(true)

        try {
            ensureHelperPushed(context, adbClient)

            // 1. 在对端启动 AudioForwarder
            val serverCmd = "CLASSPATH=/data/local/tmp/app-helper.jar exec app_process / com.toolbox.adbhelper.server.AudioForwarder"
            serverStream = try {
                adbClient.connection.openStream("shell:$serverCmd", 5000)
            } catch (e: Exception) {
                Log.w(TAG, "启动对端音频服务进程失败: ${e.message}")
                stop()
                return@withContext
            }

            // 消费 serverStream 标准输出防止阻塞
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

            // 2. 轮询连接 localabstract:scrcpy_audio
            var stream: AdbStream? = null
            for (i in 0 until 25) {
                if (!isPlaying.get()) break
                try {
                    stream = adbClient.connection.openStream("localabstract:scrcpy_audio", 800)
                    break
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }

            if (stream == null) {
                Log.w(TAG, "无法连接对端音频 Socket (scrcpy_audio)")
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
            Log.i(TAG, "AudioTrack 初始化成功，开始实时播放对端音频")

            // 4. 启动音频播放消费线程
            playbackThread = Thread({
                val buffer = ByteArray(4096)
                val input = stream.inputStream
                try {
                    while (isPlaying.get()) {
                        val read = input.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            track.write(buffer, 0, read)
                        } else if (read < 0) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (isPlaying.get()) {
                        Log.w(TAG, "音频流读取异常: ${e.message}")
                    }
                } finally {
                    stop()
                }
            }, "AudioPlaybackThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "音频转发启动失败", e)
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
        Log.i(TAG, "音频转发已停止并释放")
    }

    private fun ensureHelperPushed(context: Context, client: AdbClient) {
        try {
            val check = client.executeShell("ls -l /data/local/tmp/app-helper.jar 2>/dev/null")
            if (check.contains("app-helper.jar") && check.contains("5350")) {
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
            Log.w(TAG, "推送 app-helper.jar 异常: ${e.message}")
        }
    }
}
