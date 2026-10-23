package com.toolbox.adbhelper.core.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

interface AdbTransport : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
}
