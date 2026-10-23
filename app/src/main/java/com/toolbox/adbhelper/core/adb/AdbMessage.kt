package com.toolbox.adbhelper.core.adb

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payloadLength: Int,
    val checksum: Int,
    val magic: Int,
    val payload: ByteArray = ByteArray(0)
) {
    companion object {
        const val HEADER_LENGTH = 24

        const val CMD_SYNC = 0x434e5953
        const val CMD_CNXN = 0x4e584e43
        const val CMD_AUTH = 0x48545541
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257

        const val AUTH_TYPE_TOKEN = 1
        const val AUTH_TYPE_SIGNATURE = 2
        const val AUTH_TYPE_RSAPUBLICKEY = 3

        const val CONNECT_VERSION = 0x01000000
        const val CONNECT_MAXDATA = 1024 * 1024

        fun create(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): AdbMessage {
            var sum = 0
            for (b in payload) {
                sum += (b.toInt() and 0xFF)
            }
            return AdbMessage(
                command = command,
                arg0 = arg0,
                arg1 = arg1,
                payloadLength = payload.size,
                checksum = sum,
                magic = command xor -1,
                payload = payload
            )
        }

        fun parseHeader(headerBytes: ByteArray): AdbMessage {
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = buf.int
            val a0 = buf.int
            val a1 = buf.int
            val len = buf.int
            val sum = buf.int
            val mag = buf.int
            return AdbMessage(cmd, a0, a1, len, sum, mag)
        }

        fun readFromStream(input: InputStream): AdbMessage {
            val header = ByteArray(HEADER_LENGTH)
            var read = 0
            while (read < HEADER_LENGTH) {
                val r = input.read(header, read, HEADER_LENGTH - read)
                if (r < 0) throw IllegalStateException("Unexpected end of ADB stream")
                read += r
            }
            val msg = parseHeader(header)
            if (msg.payloadLength > 0) {
                val payload = ByteArray(msg.payloadLength)
                var pRead = 0
                while (pRead < msg.payloadLength) {
                    val r = input.read(payload, pRead, msg.payloadLength - pRead)
                    if (r < 0) throw IllegalStateException("Unexpected end of payload")
                    pRead += r
                }
                return AdbMessage(
                    msg.command,
                    msg.arg0,
                    msg.arg1,
                    msg.payloadLength,
                    msg.checksum,
                    msg.magic,
                    payload
                )
            }
            return msg
        }
    }

    fun writeToStream(output: OutputStream) {
        val buf = ByteBuffer.allocate(HEADER_LENGTH + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payloadLength)
        buf.putInt(checksum)
        buf.putInt(magic)
        if (payload.isNotEmpty()) {
            buf.put(payload)
        }
        output.write(buf.array())
        output.flush()
    }
}
