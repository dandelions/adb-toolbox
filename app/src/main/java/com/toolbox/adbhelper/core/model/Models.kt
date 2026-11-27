package com.toolbox.adbhelper.core.model

import androidx.annotation.StringRes
import com.toolbox.adbhelper.App
import com.toolbox.adbhelper.R
import java.io.Serializable

enum class ConnectionType {
    WIFI,
    USB_OTG,
    FASTBOOT
}

data class ConnectedDevice(
    val id: String, // IP:Port 或 USB 序列号
    val name: String,
    val type: ConnectionType,
    val isConnected: Boolean = true,
    val lastConnectedTime: Long = System.currentTimeMillis()
) : Serializable

data class DeviceInfo(
    var model: String = "Unknown",
    var brand: String = "Unknown",
    var manufacturer: String = "Unknown",
    var deviceCode: String = "Unknown",
    var androidVersion: String = "Unknown",
    var sdkVersion: Int = 0,
    var securityPatch: String = "Unknown",
    var abiList: String = "Unknown",
    var batteryLevel: Int = -1,
    var batteryTemp: Float = -1f,
    var batteryStatus: String = "Unknown",
    var resolution: String = "Unknown",
    var density: String = "Unknown",
    var storageTotal: String = "Unknown",
    var storageFree: String = "Unknown",
    var ipAddress: String = "Unknown",
    var wifiSsid: String = "Unknown"
) : Serializable

data class AppItem(
    val packageName: String,
    var appName: String,
    val versionName: String = "",
    val versionCode: Long = 0,
    val isSystem: Boolean = false,
    val isEnabled: Boolean = true,
    val apkPath: String = "",
    val uid: String = "",
    val minSdk: String = "",
    val targetSdk: String = ""
) : Serializable

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String,
    val owner: String,
    val lastModified: String
) : Serializable

enum class NavPosition {
    BOTTOM,
    TOP,
    LEFT,
    RIGHT,
    FLOATING,
    HIDE
}

data class ScrcpyConfig(
    var maxSize: Int = -1, // -1 为自动适配本机屏幕，0 为原画，常见 1920 (1080P), 1280 (720P), 854 (480P)
    var bitRate: Int = 8_000_000, // 码率 (2M, 4M, 8M, 16M)，默认 8M 确保高清细腻
    var maxFps: Int = 60, // 默认 60 帧标准流畅，广泛兼容省电
    var turnScreenOff: Boolean = false, // 熄屏运行
    var stayAwake: Boolean = true,
    var navPosition: NavPosition = NavPosition.BOTTOM,
    var isFullscreen: Boolean = false,
    var enableAudio: Boolean = true, // 声音传输 (音频转发，默认开启)
    var audioSource: Int = 3, // 音频源：3=SNDCPY高清内录(推荐，Android 10+全机型系统内录)，0=免安装系统内录(Android 13+推荐/低版本降级MIC)，1=麦克风(MIC)，2=语音识别
    var audioQualityMode: Int = 0, // 音质模式：0=高保真音乐听歌模式(大缓冲防爆音)，1=极速低延迟模式(游戏控制)
    var muteDeviceAudio: Boolean = false, // 被控机静音外放(仅传远端发声)
    var backgroundAudio: Boolean = true, // 主控机按 Home 键切后台继续播放音频(不传画面)
    var audioGain: Float = 1.0f, // 音频数字增益：1.0x 正常, 1.5x 增强, 2.0x 强力放大, 3.0x 超强放大
    var silenceTimeoutMs: Long = 120_000L, // 后台连续无声自动断开超时(毫秒)，0 表示从不断开
    var gestureGuard: Boolean = true // 全屏手势防误触 (主控机底部上滑回桌面时，拦截边缘手势防止误触对端Home/返回)
) : Serializable

data class ScrcpyOption<T>(
    @StringRes val labelRes: Int,
    val value: T
) {
    val label: String get() = App.instance.getString(labelRes)
}

object ScrcpyPresets {
    val AUDIO_QUALITY_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_quality_hifi, 0),
        ScrcpyOption(R.string.preset_quality_low_latency, 1)
    )

    fun getAudioQualityLabel(mode: Int): String {
        return AUDIO_QUALITY_OPTIONS.find { it.value == mode }?.label ?: AUDIO_QUALITY_OPTIONS[0].label
    }

    val AUDIO_GAIN_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_gain_1, 1.0f),
        ScrcpyOption(R.string.preset_gain_15, 1.5f),
        ScrcpyOption(R.string.preset_gain_2, 2.0f),
        ScrcpyOption(R.string.preset_gain_3, 3.0f)
    )

    fun getAudioGainLabel(gain: Float): String {
        return AUDIO_GAIN_OPTIONS.find { Math.abs(it.value - gain) < 0.1f }?.label ?: "${gain}x"
    }

    val AUDIO_SOURCE_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_audio_sndcpy, 3),
        ScrcpyOption(R.string.preset_audio_system, 0),
        ScrcpyOption(R.string.preset_audio_mic, 1),
        ScrcpyOption(R.string.preset_audio_voice, 2)
    )

    fun getAudioSourceLabel(source: Int): String {
        return AUDIO_SOURCE_OPTIONS.find { it.value == source }?.label ?: AUDIO_SOURCE_OPTIONS[0].label
    }
    val RESOLUTION_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_resolution_auto, -1),
        ScrcpyOption(R.string.preset_resolution_720, 1280),
        ScrcpyOption(R.string.preset_resolution_1080, 1920),
        ScrcpyOption(R.string.preset_resolution_original, 0),
        ScrcpyOption(R.string.preset_resolution_480, 854)
    )

    val BITRATE_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_bitrate_4, 4_000_000),
        ScrcpyOption(R.string.preset_bitrate_2, 2_000_000),
        ScrcpyOption(R.string.preset_bitrate_8, 8_000_000),
        ScrcpyOption(R.string.preset_bitrate_16, 16_000_000)
    )

    val FPS_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_fps_60, 60),
        ScrcpyOption(R.string.preset_fps_120, 120),
        ScrcpyOption(R.string.preset_fps_90, 90),
        ScrcpyOption(R.string.preset_fps_30, 30)
    )

    val NAV_POSITION_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_nav_bottom, NavPosition.BOTTOM),
        ScrcpyOption(R.string.preset_nav_top, NavPosition.TOP),
        ScrcpyOption(R.string.preset_nav_left, NavPosition.LEFT),
        ScrcpyOption(R.string.preset_nav_right, NavPosition.RIGHT),
        ScrcpyOption(R.string.preset_nav_hide, NavPosition.HIDE)
    )

    fun getResolutionLabel(size: Int): String {
        return RESOLUTION_OPTIONS.find { it.value == size }?.label
            ?: if (size == 0) App.instance.getString(R.string.preset_resolution_original)
            else if (size == -1) App.instance.getString(R.string.preset_resolution_auto) else "${size}P"
    }

    fun getBitrateLabel(bitrate: Int): String {
        return BITRATE_OPTIONS.find { it.value == bitrate }?.label
            ?: "${bitrate / 1_000_000} Mbps"
    }

    fun getFpsLabel(fps: Int): String {
        return FPS_OPTIONS.find { it.value == fps }?.label ?: "${fps} FPS"
    }

    fun getNavPositionLabel(position: NavPosition): String {
        return NAV_POSITION_OPTIONS.find { it.value == position }?.label
            ?: App.instance.getString(R.string.preset_nav_bottom)
    }

    val SILENCE_TIMEOUT_OPTIONS = listOf(
        ScrcpyOption(R.string.preset_timeout_30, 30_000L),
        ScrcpyOption(R.string.preset_timeout_60, 60_000L),
        ScrcpyOption(R.string.preset_timeout_120, 120_000L),
        ScrcpyOption(R.string.preset_timeout_300, 300_000L),
        ScrcpyOption(R.string.preset_timeout_600, 600_000L),
        ScrcpyOption(R.string.preset_timeout_never, 0L)
    )

    fun getSilenceTimeoutLabel(timeoutMs: Long): String {
        return SILENCE_TIMEOUT_OPTIONS.find { it.value == timeoutMs }?.label
            ?: if (timeoutMs <= 0L) App.instance.getString(R.string.preset_timeout_never)
            else if (timeoutMs % 60_000L == 0L) "${timeoutMs / 60_000L} min"
            else "${timeoutMs / 1000L} sec"
    }

    fun getSilenceTimeoutShortLabel(timeoutMs: Long): String {
        return when {
            timeoutMs <= 0L -> App.instance.getString(R.string.preset_timeout_never_short)
            timeoutMs % 60_000L == 0L -> App.instance.getString(R.string.preset_timeout_minutes_short, timeoutMs / 60_000L)
            else -> App.instance.getString(R.string.preset_timeout_seconds_short, timeoutMs / 1000L)
        }
    }
}
