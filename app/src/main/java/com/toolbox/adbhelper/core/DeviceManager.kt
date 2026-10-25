package com.toolbox.adbhelper.core

import android.content.Context
import android.util.Log
import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.adb.AdbConnection
import com.toolbox.adbhelper.core.adb.AdbCrypto
import com.toolbox.adbhelper.core.adb.TcpTransport
import com.toolbox.adbhelper.core.fastboot.FastbootClient
import com.toolbox.adbhelper.core.model.ConnectedDevice
import com.toolbox.adbhelper.core.model.ConnectionType
import com.toolbox.adbhelper.core.usb.UsbManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object DeviceManager {
    private const val TAG = "DeviceManager"
    private const val PREFS_NAME = "device_manager_prefs"
    private const val KEY_LAST_ENDPOINT = "last_connected_endpoint"
    private const val KEY_LAST_TYPE = "last_connected_type"

    var currentDevice: ConnectedDevice? = null
    var adbClient: AdbClient? = null
        set(value) {
            field = value
            value?.connection?.onDisconnect = {
                // 底层 TCP/USB 管道发生异常或关闭时，同步清理运行时连接
                if (adbClient == value) {
                    adbClient = null
                    currentDevice = null
                    Log.i(TAG, "ADB 连接已断开 (底层通知)")
                }
            }
        }
    var fastbootClient: FastbootClient? = null

    val isConnected: Boolean get() = (adbClient?.isConnected == true) || fastbootClient != null

    var lastEndpoint: String? = null
        private set
    var lastConnectionType: ConnectionType? = null
        private set
    var isExplicitDisconnect: Boolean = false

    private val reconnectMutex = Mutex()

    fun recordConnection(client: AdbClient, device: ConnectedDevice, context: Context?) {
        isExplicitDisconnect = false
        adbClient = client
        currentDevice = device
        lastEndpoint = device.id
        lastConnectionType = device.type
        if (context != null) {
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_LAST_ENDPOINT, device.id)
                    .putString(KEY_LAST_TYPE, device.type.name)
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist last device: ${e.message}")
            }
        }
    }

    fun loadLastDevice(context: Context) {
        if (lastEndpoint == null) {
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                lastEndpoint = prefs.getString(KEY_LAST_ENDPOINT, null)
                val typeStr = prefs.getString(KEY_LAST_TYPE, null)
                lastConnectionType = if (typeStr != null) {
                    try { ConnectionType.valueOf(typeStr) } catch (_: Exception) { ConnectionType.WIFI }
                } else null
            } catch (_: Exception) {}
        }
    }

    suspend fun getOrReconnectClient(context: Context): AdbClient? {
        val client = adbClient
        if (client != null && client.isConnected) {
            return client
        }
        if (!isExplicitDisconnect) {
            loadLastDevice(context)
            if (!lastEndpoint.isNullOrBlank()) {
                val ok = tryReconnect(context)
                if (ok) {
                    return adbClient
                }
            }
        }
        return null
    }

    suspend fun tryReconnect(context: Context, force: Boolean = false): Boolean = reconnectMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!force && adbClient?.isConnected == true) {
                // 发送快速 ping 探测连接真实性，防止底层 TCP 处于半关状态导致假在线
                try {
                    val ping = adbClient?.executeShell("echo ok", 2500)
                    if (ping?.contains("ok") == true) {
                        return@withContext true
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "Existing ADB client failed ping probe, forcing full reconnect")
                    try { adbClient?.connection?.close() } catch (_: Exception) {}
                    adbClient = null
                }
            } else if (force) {
                try { adbClient?.connection?.close() } catch (_: Exception) {}
                adbClient = null
            }

            loadLastDevice(context)
            val endpoint = lastEndpoint
            val connType = lastConnectionType ?: ConnectionType.WIFI

            if (endpoint.isNullOrBlank()) {
                Log.w(TAG, "No previous device endpoint recorded for reconnect")
                return@withContext false
            }

            Log.i(TAG, "Attempting to reconnect to $endpoint ($connType)...")

            try {
                if (connType == ConnectionType.WIFI) {
                    val parts = endpoint.split(":")
                    val ip = parts[0].trim()
                    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 5555 else 5555

                    val transport = TcpTransport(ip, port)
                    val crypto = AdbCrypto.loadOrGenerate(context.applicationContext)
                    val conn = AdbConnection(transport, crypto)
                    conn.connect()

                    val client = AdbClient(conn)
                    recordConnection(
                        client = client,
                        device = ConnectedDevice(id = endpoint, name = endpoint, type = ConnectionType.WIFI),
                        context = context
                    )
                    Log.i(TAG, "Reconnect to $endpoint succeeded")
                    return@withContext true
                } else if (connType == ConnectionType.USB_OTG) {
                    val usbHelper = UsbManagerHelper(context.applicationContext)
                    val supported = usbHelper.findSupportedDevices().filter { !it.isFastboot }
                    if (supported.isNotEmpty()) {
                        val target = supported.first()
                        if (usbHelper.hasPermission(target.device)) {
                            val transport = usbHelper.openConnection(target)
                            if (transport != null) {
                                val crypto = AdbCrypto.loadOrGenerate(context.applicationContext)
                                val conn = AdbConnection(transport, crypto)
                                conn.connect()
                                val client = AdbClient(conn)
                                recordConnection(
                                    client = client,
                                    device = ConnectedDevice(
                                        id = target.device.deviceName,
                                        name = target.device.productName ?: target.device.deviceName,
                                        type = ConnectionType.USB_OTG
                                    ),
                                    context = context
                                )
                                Log.i(TAG, "USB reconnect succeeded")
                                return@withContext true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect failed: ${e.message}")
            }
            return@withContext false
        }
    }

    fun disconnect() {
        isExplicitDisconnect = true
        try {
            adbClient?.close()
        } catch (_: Exception) {}
        try {
            fastbootClient?.close()
        } catch (_: Exception) {}
        adbClient = null
        fastbootClient = null
        currentDevice = null
    }
}
