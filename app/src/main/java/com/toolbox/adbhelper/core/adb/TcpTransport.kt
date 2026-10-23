package com.toolbox.adbhelper.core.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class TcpTransport(
    val host: String,
    val port: Int,
    timeoutMs: Int = 8000
) : AdbTransport {

    private val socket = Socket()

    init {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {}
            throw wrapSocketException(e, host, port)
        }
    }

    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun wrapSocketException(e: Exception, host: String, port: Int): IOException {
            val rawMsg = e.message ?: e.toString()
            // 过滤系统抛出的类似 "from /192.168.1.5 (port 0)" 这种本地临时端口，避免用户误解为端口未识别
            val sanitizedMsg = rawMsg
                .replace(Regex("""from\s+/[^\s]+\s+\(port\s+0\)"""), "")
                .replace("(port 0)", "")
                .trim()

            val friendlyMsg = when {
                sanitizedMsg.contains("ECONNREFUSED", ignoreCase = true) || sanitizedMsg.contains("Connection refused", ignoreCase = true) ->
                    "目标设备 [$host:$port] 拒绝连接。\n\n排查建议：\n1. 请检查被控端是否已开启【无线调试】；\n2. 检查端口号：Android 11+ 原生无线调试每次启动会分配随机 5 位端口（如 38xxx），请将对端屏幕上显示的五位端口填入端口框；\n3. 若使用 5555 端口，请确保对端已通过 USB 激活网络调试或系统已开启 5555 监听。"
                e is SocketTimeoutException || sanitizedMsg.contains("ETIMEDOUT", ignoreCase = true) || sanitizedMsg.contains("timed out", ignoreCase = true) ->
                    "连接目标设备 [$host:$port] 超时。\n\n请确认：\n1. 本机与对端设备处于同一 WiFi 局域网（关闭路由器 AP 隔离）；\n2. 对端 IP ($host) 是否输入正确。"
                sanitizedMsg.contains("EHOSTUNREACH", ignoreCase = true) || sanitizedMsg.contains("No route to host", ignoreCase = true) ->
                    "无法访问目标设备 [$host:$port]，路由不可达。\n请检查当前 WiFi 连接状态或对端 IP 是否正确。"
                sanitizedMsg.contains("Unable to resolve host", ignoreCase = true) || sanitizedMsg.contains("UnknownHost", ignoreCase = true) ->
                    "无法解析目标地址: $host，请检查 IP 格式。"
                else ->
                    "连接目标设备 [$host:$port] 失败: $sanitizedMsg"
            }
            return IOException(friendlyMsg, e)
        }
    }
}
