package com.toolbox.adbhelper.core.adb

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AdbConnection(
    private val transport: AdbTransport,
    private val crypto: AdbCrypto
) : Closeable {

    companion object {
        private const val TAG = "AdbConnection"
    }

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)
    private val isConnected = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private var readThread: Thread? = null

    // 握手同步锁
    private val connectLock = Object()

    fun connect(timeoutMs: Long = 10000) {
        // 1. 发送 CNXN 握手消息
        val banner = "host::ADBToolbox\u0000".toByteArray(Charsets.UTF_8)
        val cnxnMsg = AdbMessage.create(
            command = AdbMessage.CMD_CNXN,
            arg0 = AdbMessage.CONNECT_VERSION,
            arg1 = AdbMessage.CONNECT_MAXDATA,
            payload = banner
        )
        cnxnMsg.writeToStream(transport.outputStream)

        // 启动消息接收循环
        startReaderThread()

        // 等待握手完成
        synchronized(connectLock) {
            val start = System.currentTimeMillis()
            while (!isConnected.get() && !isClosed.get()) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) {
                    close()
                    throw IOException("Connection timeout waiting for handshake")
                }
                connectLock.wait(1000)
            }
        }
    }

    private fun startReaderThread() {
        readThread = Thread({
            try {
                while (!isClosed.get()) {
                    val msg = AdbMessage.readFromStream(transport.inputStream)
                    handleMessage(msg)
                }
            } catch (e: Exception) {
                if (!isClosed.get()) {
                    Log.e(TAG, "Read error: ${e.message}")
                    close()
                }
            }
        }, "AdbReaderThread").apply { start() }
    }

    private fun handleMessage(msg: AdbMessage) {
        when (msg.command) {
            AdbMessage.CMD_CNXN -> {
                Log.d(TAG, "Device connected: ${String(msg.payload)}")
                isConnected.set(true)
                synchronized(connectLock) {
                    connectLock.notifyAll()
                }
            }
            AdbMessage.CMD_AUTH -> {
                if (msg.arg0 == AdbMessage.AUTH_TYPE_TOKEN) {
                    // 设备发来认证 token
                    try {
                        val signature = crypto.sign(msg.payload)
                        val reply = AdbMessage.create(
                            command = AdbMessage.CMD_AUTH,
                            arg0 = AdbMessage.AUTH_TYPE_SIGNATURE,
                            arg1 = 0,
                            payload = signature
                        )
                        reply.writeToStream(transport.outputStream)
                    } catch (e: Exception) {
                        Log.e(TAG, "Sign token failed, fallback to public key", e)
                        sendPublicKey()
                    }
                } else {
                    sendPublicKey()
                }
            }
            AdbMessage.CMD_OKAY -> {
                val localId = msg.arg1
                val remoteId = msg.arg0
                val stream = streams[localId]
                if (stream != null) {
                    stream.remoteId = remoteId
                    synchronized(stream) {
                        (stream as java.lang.Object).notifyAll()
                    }
                }
            }
            AdbMessage.CMD_WRTE -> {
                val localId = msg.arg1
                val stream = streams[localId]
                if (stream != null) {
                    stream.onDataReceived(msg.payload)
                }
            }
            AdbMessage.CMD_CLSE -> {
                val localId = msg.arg1
                val stream = streams[localId]
                if (stream != null) {
                    stream.onCloseReceived()
                    streams.remove(localId)
                }
            }
            else -> {
                Log.w(TAG, "Unhandled command: ${msg.command}")
            }
        }
    }

    private fun sendPublicKey() {
        val pubKey = crypto.getPublicKeyBytes()
        val authMsg = AdbMessage.create(
            command = AdbMessage.CMD_AUTH,
            arg0 = AdbMessage.AUTH_TYPE_RSAPUBLICKEY,
            arg1 = 0,
            payload = pubKey
        )
        authMsg.writeToStream(transport.outputStream)
    }

    fun openStream(destination: String, timeoutMs: Long = 5000): AdbStream {
        if (!isConnected.get() || isClosed.get()) {
            throw IOException("Adb is not connected")
        }
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(this, localId, 0)
        streams[localId] = stream

        val payload = "$destination\u0000".toByteArray(Charsets.UTF_8)
        val openMsg = AdbMessage.create(
            command = AdbMessage.CMD_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = payload
        )
        openMsg.writeToStream(transport.outputStream)

        // 等待 OKAY 响应获取 remoteId
        synchronized(stream) {
            val start = System.currentTimeMillis()
            while (stream.remoteId == 0 && !isClosed.get()) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) {
                    streams.remove(localId)
                    throw IOException("Timeout opening stream: $destination")
                }
                (stream as java.lang.Object).wait(500)
            }
        }
        return stream
    }

    fun sendWrite(localId: Int, remoteId: Int, data: ByteArray) {
        val msg = AdbMessage.create(
            command = AdbMessage.CMD_WRTE,
            arg0 = localId,
            arg1 = remoteId,
            payload = data
        )
        msg.writeToStream(transport.outputStream)
    }

    fun sendOkay(localId: Int, remoteId: Int) {
        val msg = AdbMessage.create(
            command = AdbMessage.CMD_OKAY,
            arg0 = localId,
            arg1 = remoteId
        )
        msg.writeToStream(transport.outputStream)
    }

    fun sendClose(localId: Int, remoteId: Int) {
        val msg = AdbMessage.create(
            command = AdbMessage.CMD_CLSE,
            arg0 = localId,
            arg1 = remoteId
        )
        try {
            msg.writeToStream(transport.outputStream)
        } catch (_: Exception) {}
    }

    fun removeStream(localId: Int) {
        streams.remove(localId)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            isConnected.set(false)
            synchronized(connectLock) {
                connectLock.notifyAll()
            }
            streams.values.forEach { it.onCloseReceived() }
            streams.clear()
            try {
                transport.close()
            } catch (_: Exception) {}
            readThread?.interrupt()
        }
    }
}
