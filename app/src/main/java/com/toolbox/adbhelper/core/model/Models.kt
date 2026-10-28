package com.toolbox.adbhelper.core.model

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
    var silenceTimeoutMs: Long = 120_000L // 后台连续无声自动断开超时(毫秒)，0 表示从不断开
) : Serializable

data class ScrcpyOption<T>(
    val label: String,
    val value: T
)

object ScrcpyPresets {
    val AUDIO_QUALITY_OPTIONS = listOf(
        ScrcpyOption("高保真音乐模式 (推荐听歌/观影，48kHz大缓冲防抖零爆音)", 0),
        ScrcpyOption("极速低延迟模式 (游戏控制推荐，约30ms超低延迟)", 1)
    )

    fun getAudioQualityLabel(mode: Int): String {
        return AUDIO_QUALITY_OPTIONS.find { it.value == mode }?.label ?: AUDIO_QUALITY_OPTIONS[0].label
    }

    val AUDIO_GAIN_OPTIONS = listOf(
        ScrcpyOption("1.0x (原始原声)", 1.0f),
        ScrcpyOption("1.5x (+3.5dB 适度增强)", 1.5f),
        ScrcpyOption("2.0x (+6.0dB 明显放大，静音听歌推荐)", 2.0f),
        ScrcpyOption("3.0x (+9.5dB 超强推力放大)", 3.0f)
    )

    fun getAudioGainLabel(gain: Float): String {
        return AUDIO_GAIN_OPTIONS.find { Math.abs(it.value - gain) < 0.1f }?.label ?: "${gain}x"
    }

    val AUDIO_SOURCE_OPTIONS = listOf(
        ScrcpyOption("Sndcpy 高清伴侣系统内录 (推荐，Android 10+ 纯净应用音乐/游戏内录)", 3),
        ScrcpyOption("免安装系统内录 (免装免弹窗，Android 13+支持/低版本降级MIC)", 0),
        ScrcpyOption("被控端麦克风 (MIC，采集环境音/扬声器外放)", 1),
        ScrcpyOption("语音识别音源 (VOICE_RECOGNITION)", 2)
    )

    fun getAudioSourceLabel(source: Int): String {
        return AUDIO_SOURCE_OPTIONS.find { it.value == source }?.label ?: AUDIO_SOURCE_OPTIONS[0].label
    }
    val RESOLUTION_OPTIONS = listOf(
        ScrcpyOption("自动适配本机屏幕 (推荐，等比完整容纳)", -1),
        ScrcpyOption("720P (标清流畅，等比适配)", 1280),
        ScrcpyOption("1080P (全高清，等比适配)", 1920),
        ScrcpyOption("原画 (原生分辨率，不压缩尺寸)", 0),
        ScrcpyOption("480P (极速低延迟，等比适配)", 854)
    )

    val BITRATE_OPTIONS = listOf(
        ScrcpyOption("4 Mbps (平衡推荐，WiFi优先)", 4_000_000),
        ScrcpyOption("2 Mbps (低码率，抗丢包低延迟)", 2_000_000),
        ScrcpyOption("8 Mbps (高清画质，局域网推荐)", 8_000_000),
        ScrcpyOption("16 Mbps (超清画质，极高码率)", 16_000_000)
    )

    val FPS_OPTIONS = listOf(
        ScrcpyOption("60 FPS (标准流畅，广泛兼容省电)", 60),
        ScrcpyOption("120 FPS (极速高刷，极致丝滑)", 120),
        ScrcpyOption("90 FPS (高刷流畅，省电推荐)", 90),
        ScrcpyOption("30 FPS (省电低功耗)", 30)
    )

    val NAV_POSITION_OPTIONS = listOf(
        ScrcpyOption("底部停靠 (标准推荐)", NavPosition.BOTTOM),
        ScrcpyOption("顶部停靠", NavPosition.TOP),
        ScrcpyOption("左侧悬浮停靠", NavPosition.LEFT),
        ScrcpyOption("右侧悬浮停靠", NavPosition.RIGHT),
        ScrcpyOption("隐藏控制栏 (完全沉浸)", NavPosition.HIDE)
    )

    fun getResolutionLabel(size: Int): String {
        return RESOLUTION_OPTIONS.find { it.value == size }?.label
            ?: if (size == 0) "原画" else if (size == -1) "自动适配本机屏幕" else "${size}P"
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
            ?: "底部停靠"
    }

    val SILENCE_TIMEOUT_OPTIONS = listOf(
        ScrcpyOption("30 秒 (极速断开省电)", 30_000L),
        ScrcpyOption("1 分钟 (快速断开)", 60_000L),
        ScrcpyOption("2 分钟 (默认推荐，防切歌误断)", 120_000L),
        ScrcpyOption("5 分钟 (适中保活时间)", 300_000L),
        ScrcpyOption("10 分钟 (超长保活等待)", 600_000L),
        ScrcpyOption("从不断开 (持续后台音频)", 0L)
    )

    fun getSilenceTimeoutLabel(timeoutMs: Long): String {
        return SILENCE_TIMEOUT_OPTIONS.find { it.value == timeoutMs }?.label
            ?: if (timeoutMs <= 0L) "从不断开" else if (timeoutMs % 60_000L == 0L) "${timeoutMs / 60_000L} 分钟" else "${timeoutMs / 1000L} 秒"
    }

    fun getSilenceTimeoutShortLabel(timeoutMs: Long): String {
        return when {
            timeoutMs <= 0L -> "静音不断开"
            timeoutMs % 60_000L == 0L -> "静音${timeoutMs / 60_000L}分"
            else -> "静音${timeoutMs / 1000L}秒"
        }
    }
}
