package com.toolbox.adbhelper.core.adb

import com.toolbox.adbhelper.core.model.FileItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbSync(private val stream: AdbStream) {

    companion object {
        val ID_STAT = "STAT".toByteArray(Charsets.US_ASCII)
        val ID_LIST = "LIST".toByteArray(Charsets.US_ASCII)
        val ID_DENT = "DENT".toByteArray(Charsets.US_ASCII)
        val ID_SEND = "SEND".toByteArray(Charsets.US_ASCII)
        val ID_RECV = "RECV".toByteArray(Charsets.US_ASCII)
        val ID_DATA = "DATA".toByteArray(Charsets.US_ASCII)
        val ID_DONE = "DONE".toByteArray(Charsets.US_ASCII)
        val ID_OKAY = "OKAY".toByteArray(Charsets.US_ASCII)
        val ID_FAIL = "FAIL".toByteArray(Charsets.US_ASCII)
        val ID_QUIT = "QUIT".toByteArray(Charsets.US_ASCII)
    }

    private val inputBuffer = ByteArrayOutputStream()

    private fun readExact(count: Int): ByteArray {
        while (inputBuffer.size() < count) {
            val chunk = stream.read() ?: throw java.io.EOFException("Sync stream closed")
            inputBuffer.write(chunk)
        }
        val all = inputBuffer.toByteArray()
        val result = ByteArray(count)
        System.arraycopy(all, 0, result, 0, count)
        inputBuffer.reset()
        if (all.size > count) {
            inputBuffer.write(all, count, all.size - count)
        }
        return result
    }

    fun pushFile(localFile: File, remotePath: String, progress: ((Float) -> Unit)? = null) {
        val pathBytes = "$remotePath,0644".toByteArray(Charsets.UTF_8)
        val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.put(ID_SEND)
        req.putInt(pathBytes.size)
        req.put(pathBytes)
        stream.write(req.array())

        val totalLength = localFile.length()
        var sentLength = 0L
        val buffer = ByteArray(64 * 1024)

        localFile.inputStream().use { fis ->
            while (true) {
                val r = fis.read(buffer)
                if (r <= 0) break
                val dataHeader = ByteBuffer.allocate(8 + r).order(ByteOrder.LITTLE_ENDIAN)
                dataHeader.put(ID_DATA)
                dataHeader.putInt(r)
                dataHeader.put(buffer, 0, r)
                stream.write(dataHeader.array())
                sentLength += r
                if (totalLength > 0) {
                    progress?.invoke(sentLength.toFloat() / totalLength)
                }
            }
        }

        // 发送 DONE
        val doneBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        doneBuf.put(ID_DONE)
        doneBuf.putInt((System.currentTimeMillis() / 1000).toInt())
        stream.write(doneBuf.array())

        // 检查 OKAY
        val respHeader = readExact(8)
        val cmd = String(respHeader, 0, 4, Charsets.US_ASCII)
        if (cmd != "OKAY") {
            val len = ByteBuffer.wrap(respHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val msg = if (len > 0) String(readExact(len), Charsets.UTF_8) else "Push failed"
            throw java.io.IOException("Push error: $msg")
        }
    }

    fun pullFile(remotePath: String, localFile: File, progress: ((Float) -> Unit)? = null) {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        val req = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        req.put(ID_RECV)
        req.putInt(pathBytes.size)
        req.put(pathBytes)
        stream.write(req.array())

        localFile.outputStream().use { fos ->
            while (true) {
                val header = readExact(8)
                val cmd = String(header, 0, 4, Charsets.US_ASCII)
                val len = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (cmd == "DATA") {
                    val data = readExact(len)
                    fos.write(data)
                } else if (cmd == "DONE") {
                    break
                } else {
                    val msg = if (len > 0) String(readExact(len), Charsets.UTF_8) else "Pull failed"
                    throw java.io.IOException("Pull error: $msg")
                }
            }
        }
    }

    fun close() {
        try {
            val quit = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            quit.put(ID_QUIT)
            quit.putInt(0)
            stream.write(quit.array())
            stream.close()
        } catch (_: Exception) {}
    }
}
