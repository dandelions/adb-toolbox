package com.toolbox.adbhelper.core

import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.fastboot.FastbootClient
import com.toolbox.adbhelper.core.model.ConnectedDevice

object DeviceManager {
    var currentDevice: ConnectedDevice? = null
    var adbClient: AdbClient? = null
    var fastbootClient: FastbootClient? = null

    val isConnected: Boolean get() = adbClient != null || fastbootClient != null

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
