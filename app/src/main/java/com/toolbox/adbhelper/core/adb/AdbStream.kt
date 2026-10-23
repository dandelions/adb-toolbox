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

    val inputStream: InputStream by lazy {
        object : InputStream() {
            private var currentBuffer: ByteArray? = null
            private var offset = 0

            override fun read(): Int {
                while (currentBuffer == null || offset >= currentBuffer!!.size) {
                    currentBuffer = this@AdbStream.read() ?: return -1
                    offset = 0
                }
                return currentBuffer!![offset++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (currentBuffer == null || offset >= currentBuffer!!.size) {
                    currentBuffer = this@AdbStream.read() ?: return -1
                    offset = 0
                }
                val available = currentBuffer!!.size - offset
                val toRead = minOf(len, available)
                System.arraycopy(currentBuffer!!, offset, b, off, toRead)
                offset += toRead
                return toRead
            }

            override fun close() {
                this@AdbStream.close()
            }
        }
    }

    val outputStream: OutputStream by lazy {
        object : OutputStream() {
            override fun write(b: Int) {
                this@AdbStream.write(byteArrayOf(b.toByte()))
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (off == 0 && len == b.size) {
                    this@AdbStream.write(b)
                } else {
                    val slice = ByteArray(len)
                    System.arraycopy(b, off, slice, 0, len)
                    this@AdbStream.write(slice)
                }
            }

            override fun close() {
                this@AdbStream.close()
            }
        }
    }

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
