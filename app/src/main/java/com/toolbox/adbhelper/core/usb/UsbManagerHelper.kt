package com.toolbox.adbhelper.core.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.Build

class UsbManagerHelper(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        const val ACTION_USB_PERMISSION = "com.toolbox.adbhelper.USB_PERMISSION"
        const val ADB_CLASS = 255
        const val ADB_SUBCLASS = 66
        const val ADB_PROTOCOL = 1
        const val FASTBOOT_PROTOCOL = 3
    }

    data class UsbTarget(
        val device: UsbDevice,
        val iface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        val isFastboot: Boolean
    )

    fun findSupportedDevices(): List<UsbTarget> {
        val targets = mutableListOf<UsbTarget>()
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == ADB_CLASS && iface.interfaceSubclass == ADB_SUBCLASS) {
                    val isFastboot = iface.interfaceProtocol == FASTBOOT_PROTOCOL
                    val isAdb = iface.interfaceProtocol == ADB_PROTOCOL || !isFastboot

                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) {
                                inEp = ep
                            } else {
                                outEp = ep
                            }
                        }
                    }
                    if (inEp != null && outEp != null) {
                        targets.add(UsbTarget(device, iface, inEp, outEp, isFastboot))
                    }
                }
            }
        }
        return targets
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0
        val intent = Intent(ACTION_USB_PERMISSION)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    fun openConnection(target: UsbTarget): UsbTransport? {
        val conn = usbManager.openDevice(target.device) ?: return null
        if (!conn.claimInterface(target.iface, true)) {
            conn.close()
            return null
        }
        return UsbTransport(conn, target.inEndpoint, target.outEndpoint)
    }
}
