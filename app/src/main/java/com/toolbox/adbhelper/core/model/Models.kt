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
    var audioSource: Int = 3 // 音频源：3=SNDCPY伴侣系统内录(Android 10+全机型支持)，0=自动(系统内录优先)，1=麦克风(MIC)，2=语音识别
) : Serializable

data class ScrcpyOption<T>(
    val label: String,
    val value: T
)

object ScrcpyPresets {
    val AUDIO_SOURCE_OPTIONS = listOf(
        ScrcpyOption("Sndcpy 系统声音内录 (推荐，Android 10+ 全兼容)", 3),
        ScrcpyOption("自动/原生系统内录 (AudioPolicy/Submix)", 0),
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
}
