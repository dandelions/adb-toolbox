package com.toolbox.adbhelper.core.adb

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TcpTransport(
    private val host: String,
    private val port: Int,
    timeoutMs: Int = 5000
) : AdbTransport {

    private val socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), timeoutMs)
    }

    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
