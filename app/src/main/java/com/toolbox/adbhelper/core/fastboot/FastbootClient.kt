package com.toolbox.adbhelper.core.fastboot

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.Closeable
import java.io.IOException

class FastbootClient(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) : Closeable {

    data class FastbootResponse(
        val isSuccess: Boolean,
        val status: String,
        val message: String,
        val infoList: List<String>
    )

    fun sendCommand(command: String, timeoutMs: Int = 10000): FastbootResponse {
        val cmdBytes = command.toByteArray(Charsets.US_ASCII)
        val sent = connection.bulkTransfer(endpointOut, cmdBytes, cmdBytes.size, timeoutMs)
        if (sent < 0) {
            throw IOException("Failed to send fastboot command: $command")
        }

        val infoMessages = mutableListOf<String>()
        val buffer = ByteArray(4096)

        while (true) {
            val read = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)
            if (read < 0) {
                return FastbootResponse(false, "ERROR", "Timeout or read failure", infoMessages)
            }
            if (read < 4) continue

            val prefix = String(buffer, 0, 4, Charsets.US_ASCII)
            val msg = if (read > 4) String(buffer, 4, read - 4, Charsets.UTF_8).trim() else ""

            when (prefix) {
                "INFO" -> {
                    infoMessages.add(msg)
                }
                "OKAY" -> {
                    return FastbootResponse(true, prefix, msg, infoMessages)
                }
                "FAIL" -> {
                    return FastbootResponse(false, prefix, msg, infoMessages)
                }
                "DATA" -> {
                    return FastbootResponse(true, prefix, msg, infoMessages)
                }
                else -> {
                    infoMessages.add(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
        }
    }

    fun getAllVars(): Map<String, String> {
        val resp = sendCommand("getvar:all")
        val result = mutableMapOf<String, String>()
        for (line in resp.infoList) {
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim()] = parts[1].trim()
            } else {
                result[line] = ""
            }
        }
        return result
    }

    fun reboot(target: String = ""): FastbootResponse {
        return if (target.isEmpty()) {
            sendCommand("reboot")
        } else {
            sendCommand("reboot-$target")
        }
    }

    fun unlockBootloader(): FastbootResponse {
        var resp = sendCommand("flashing unlock")
        if (!resp.isSuccess) {
            resp = sendCommand("oem unlock")
        }
        return resp
    }

    override fun close() {
        try {
            connection.close()
        } catch (_: Exception) {}
    }
}
