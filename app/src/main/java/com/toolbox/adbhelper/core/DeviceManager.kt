package com.toolbox.adbhelper.core

import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.fastboot.FastbootClient
import com.toolbox.adbhelper.core.model.ConnectedDevice

object DeviceManager {
    var currentDevice: ConnectedDevice? = null
    var adbClient: AdbClient? = null
        set(value) {
            field = value
            value?.connection?.onDisconnect = {
                // 底层 TCP/USB 管道发生异常或关闭时，同步清理状态
                if (adbClient == value) {
                    adbClient = null
                    currentDevice = null
                }
            }
        }
    var fastbootClient: FastbootClient? = null

    val isConnected: Boolean get() = (adbClient?.isConnected == true) || fastbootClient != null

    fun disconnect() {
        try {
            adbClient?.close()
        } catch (_: Exception) {}
        try {
            fastbootClient?.close()
        } catch (_: Exception) {}
        adbClient = null
        fastbootClient = null
        currentDevice = null
    }
}
