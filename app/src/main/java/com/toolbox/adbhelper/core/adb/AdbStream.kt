package com.toolbox.adbhelper.core.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class AdbStream(
    val connection: AdbConnection,
    val localId: Int,
    var remoteId: Int
) : Closeable {

    private val isClosed = AtomicBoolean(false)
    private val receiveQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val sendLock = Any()

    fun onDataReceived(data: ByteArray) {
        if (!isClosed.get()) {
            receiveQueue.put(data)
            // 发送 OKAY 确认接收
            connection.sendOkay(localId, remoteId)
        }
    }

    fun onCloseReceived() {
        isClosed.set(true)
        receiveQueue.put(ByteArray(0)) // 结束标记
    }

    fun write(data: ByteArray) {
        if (isClosed.get()) throw IllegalStateException("Stream is closed")
        synchronized(sendLock) {
            connection.sendWrite(localId, remoteId, data)
        }
    }

    fun read(): ByteArray? {
        if (isClosed.get() && receiveQueue.isEmpty()) return null
        val data = receiveQueue.take()
        if (data.isEmpty() && isClosed.get()) return null
        return data
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            connection.sendClose(localId, remoteId)
            connection.removeStream(localId)
        }
    }
}
