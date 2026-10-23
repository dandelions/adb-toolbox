package com.toolbox.adbhelper.core.usb

import android.hardware.usb.*
import com.toolbox.adbhelper.core.adb.AdbTransport
import java.io.InputStream
import java.io.OutputStream

class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) : AdbTransport {

    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            val r = read(single, 0, 1)
            return if (r > 0) single[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            val temp = if (off == 0) b else ByteArray(len)
            val transferred = connection.bulkTransfer(endpointIn, temp, len, 5000)
            if (transferred > 0 && off != 0) {
                System.arraycopy(temp, 0, b, off, transferred)
            }
            return if (transferred >= 0) transferred else -1
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val temp = if (off == 0 && len == b.size) b else {
                val sub = ByteArray(len)
                System.arraycopy(b, off, sub, 0, len)
                sub
            }
            val transferred = connection.bulkTransfer(endpointOut, temp, len, 5000)
            if (transferred < 0) {
                throw java.io.IOException("USB bulk write failed")
            }
        }
    }

    override fun close() {
        try {
            connection.close()
        } catch (_: Exception) {}
    }
}
