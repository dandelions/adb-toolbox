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
    val appName: String,
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
    var bitRate: Int = 4_000_000, // 码率 (2M, 4M, 8M, 16M)
    var maxFps: Int = 60,
    var turnScreenOff: Boolean = false, // 熄屏运行
    var stayAwake: Boolean = true,
    var navPosition: NavPosition = NavPosition.BOTTOM,
    var isFullscreen: Boolean = false
) : Serializable

data class ScrcpyOption<T>(
    val label: String,
    val value: T
)

object ScrcpyPresets {
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

    fun getNavPositionLabel(position: NavPosition): String {
        return NAV_POSITION_OPTIONS.find { it.value == position }?.label
            ?: "底部停靠"
    }
}
