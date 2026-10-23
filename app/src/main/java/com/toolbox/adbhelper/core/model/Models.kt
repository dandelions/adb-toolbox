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
