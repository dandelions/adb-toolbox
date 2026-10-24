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

    // 报文发送线程安全互斥锁（杜绝多线程写 TCP 管道导致数据包交错损坏）
    private val writeLock = Any()

    // 握手同步锁
    private val connectLock = Object()

    @Volatile
    private var sentSignature = false

    @Volatile
    private var lastError: String? = null

    var onAuthPrompt: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null

    fun isConnected(): Boolean = isConnected.get() && !isClosed.get()

    /**
     * 线程安全、原子写入单个 ADB 报文
     */
    private fun sendMsg(msg: AdbMessage) {
        synchronized(writeLock) {
            if (!isClosed.get()) {
                try {
                    msg.writeToStream(transport.outputStream)
                } catch (e: Exception) {
                    if (!isClosed.get()) {
                        Log.e(TAG, "Write error: ${e.message}")
                        lastError = e.message ?: "发送 ADB 数据异常"
                        close()
                    }
                    throw e
                }
            }
        }
    }

    fun connect(timeoutMs: Long = 30000) {
        sentSignature = false
        lastError = null
        // 1. 发送标准 CNXN 握手消息
        val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
        val cnxnMsg = AdbMessage.create(
            command = AdbMessage.CMD_CNXN,
            arg0 = AdbMessage.CONNECT_VERSION,
            arg1 = AdbMessage.CONNECT_MAXDATA,
            payload = banner
        )
        sendMsg(cnxnMsg)

        // 启动消息接收循环
        startReaderThread()

        // 等待握手完成
        synchronized(connectLock) {
            val start = System.currentTimeMillis()
            while (!isConnected.get() && !isClosed.get()) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) {
                    close()
                    throw IOException("连接握手超时（等待设备授权）。若目标设备已弹出授权窗口，请勾选始终允许后重试。")
                }
                connectLock.wait(1000)
            }
            if (!isConnected.get()) {
                throw IOException(lastError ?: "连接握手失败：目标设备断开连接或拒绝授权")
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
                    lastError = e.message ?: "读取 ADB 响应异常"
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
                    if (sentSignature) {
                        // 已经发送过私钥签名，说明被控端尚未信任此密钥，转为发送公钥请求弹窗授权
                        Log.i(TAG, "Signature not trusted or pending, sending public key for authorization dialog")
                        try {
                            onAuthPrompt?.invoke()
                            sendPublicKey()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send public key", e)
                        }
                    } else {
                        // 首次收到 TOKEN 质询，使用私钥签名回复
                        try {
                            val signature = crypto.sign(msg.payload)
                            val reply = AdbMessage.create(
                                command = AdbMessage.CMD_AUTH,
                                arg0 = AdbMessage.AUTH_TYPE_SIGNATURE,
                                arg1 = 0,
                                payload = signature
                            )
                            sentSignature = true
                            sendMsg(reply)
                        } catch (e: Exception) {
                            Log.e(TAG, "Sign token failed, fallback to public key", e)
                            sentSignature = true
                            onAuthPrompt?.invoke()
                            sendPublicKey()
                        }
                    }
                } else {
                    onAuthPrompt?.invoke()
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
                val remoteId = msg.arg0
                val stream = streams[localId]
                if (stream != null) {
                    stream.onDataReceived(msg.payload)
                } else {
                    // 对端流在途中产生残留数据，回复 CLSE 告知该流已结束
                    sendClose(localId, remoteId)
                }
            }
            AdbMessage.CMD_CLSE -> {
                val localId = msg.arg1
                val stream = streams[localId]
                if (stream != null) {
                    stream.onCloseReceived()
                    synchronized(stream) {
                        (stream as java.lang.Object).notifyAll()
                    }
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
        sendMsg(authMsg)
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
        sendMsg(openMsg)

        // 等待 OKAY 响应获取 remoteId
        synchronized(stream) {
            val start = System.currentTimeMillis()
            while (stream.remoteId == 0 && !stream.isClosed() && !isClosed.get()) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) {
                    streams.remove(localId)
                    throw IOException("Timeout opening stream: $destination")
                }
                val waitTime = minOf(200L, maxOf(10L, timeoutMs - elapsed))
                (stream as java.lang.Object).wait(waitTime)
            }
            if (stream.isClosed()) {
                streams.remove(localId)
                throw IOException("Stream closed by remote peer: $destination")
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
        sendMsg(msg)
    }

    fun sendOkay(localId: Int, remoteId: Int) {
        val msg = AdbMessage.create(
            command = AdbMessage.CMD_OKAY,
            arg0 = localId,
            arg1 = remoteId
        )
        sendMsg(msg)
    }

    fun sendClose(localId: Int, remoteId: Int) {
        val msg = AdbMessage.create(
            command = AdbMessage.CMD_CLSE,
            arg0 = localId,
            arg1 = remoteId
        )
        try {
            sendMsg(msg)
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
            try {
                onDisconnect?.invoke()
            } catch (_: Exception) {}
        }
    }
}
