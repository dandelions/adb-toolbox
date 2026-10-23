package com.toolbox.adbhelper.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.adb.AdbClient
import com.toolbox.adbhelper.core.adb.AdbConnection
import com.toolbox.adbhelper.core.adb.AdbCrypto
import com.toolbox.adbhelper.core.adb.TcpTransport
import com.toolbox.adbhelper.core.model.ConnectedDevice
import com.toolbox.adbhelper.core.model.ConnectionType
import com.toolbox.adbhelper.core.usb.UsbManagerHelper
import com.toolbox.adbhelper.databinding.ActivityConnectBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var usbHelper: UsbManagerHelper

    companion object {
        private const val PREFS_NAME = "adb_connect_prefs"
        private const val KEY_HISTORY = "history_devices"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbHelper = UsbManagerHelper(this)

        initViews()
        loadHistoryDevices()
        scanUsbDevices()
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 监听 IP 输入框，如果用户粘贴或输入带冒号的 IP:端口，自动分离到端口框
        binding.etIpAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: return
                val normalized = text.replace("：", ":").trim()
                if (normalized.contains(":") && !normalized.startsWith("[")) {
                    val parts = normalized.split(":")
                    if (parts.size == 2 && parts[1].isNotEmpty()) {
                        val host = parts[0].trim()
                        val port = parts[1].trim()
                        if (port.toIntOrNull() != null) {
                            binding.etIpAddress.removeTextChangedListener(this)
                            binding.etIpAddress.setText(host)
                            binding.etIpAddress.setSelection(host.length)
                            binding.etPort.setText(port)
                            binding.etIpAddress.addTextChangedListener(this)
                        }
                    }
                }
            }
        })

        binding.btnConnectWifi.setOnClickListener {
            val rawIp = binding.etIpAddress.text.toString()
            val rawPort = binding.etPort.text.toString()

            val (ip, port) = parseIpAndPort(rawIp, rawPort)
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入正确的设备 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            connectWifiDevice(ip, port)
        }

        binding.btnScanUsb.setOnClickListener {
            scanUsbDevices()
        }
    }

    /**
     * 智能解析输入的 IP 与端口，支持以下格式：
     * - "192.168.1.100", "5555" -> ("192.168.1.100", 5555)
     * - "192.168.1.100:38421", "" -> ("192.168.1.100", 38421)
     * - "192.168.1.100：5555", "" (中文冒号) -> ("192.168.1.100", 5555)
     * - "tcp://192.168.1.100:5555" -> ("192.168.1.100", 5555)
     */
    private fun parseIpAndPort(rawIp: String, rawPort: String): Pair<String, Int> {
        var cleanIp = rawIp.trim()
            .replace("tcp://", "", ignoreCase = true)
            .replace("http://", "", ignoreCase = true)
            .replace("https://", "", ignoreCase = true)
            .replace("：", ":")
            .trim()

        var port = 5555

        if (cleanIp.contains(":")) {
            val lastColon = cleanIp.lastIndexOf(':')
            val hostPart = cleanIp.substring(0, lastColon).trim()
            val portPart = cleanIp.substring(lastColon + 1).trim()
            val parsedPort = portPart.toIntOrNull()
            if (parsedPort != null && parsedPort in 1..65535) {
                cleanIp = hostPart
                port = parsedPort
            }
        } else {
            val cleanPort = rawPort.trim().toIntOrNull()
            if (cleanPort != null && cleanPort in 1..65535) {
                port = cleanPort
            }
        }

        // 去除中括号（如果是 IPv6）
        if (cleanIp.startsWith("[") && cleanIp.endsWith("]")) {
            cleanIp = cleanIp.substring(1, cleanIp.length - 1)
        }

        return Pair(cleanIp, port)
    }

    private fun connectWifiDevice(ip: String, port: Int) {
        binding.btnConnectWifi.isEnabled = false
        binding.tvWifiStatus.visibility = View.VISIBLE
        binding.tvWifiStatus.text = "正在连接目标设备 [$ip:$port]..."
        binding.btnConnectWifi.text = "正在连接中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val transport = TcpTransport(ip, port)
                val crypto = AdbCrypto.loadOrGenerate(this@ConnectActivity)
                val conn = AdbConnection(transport, crypto)

                conn.onAuthPrompt = {
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.tvWifiStatus.text = "已发起连接请求！\n请在目标设备屏幕上查看授权弹窗，并点击【始终允许】"
                    }
                }

                conn.connect()

                val client = AdbClient(conn)
                DeviceManager.adbClient = client
                DeviceManager.currentDevice = ConnectedDevice(
                    id = "$ip:$port",
                    name = "$ip:$port",
                    type = ConnectionType.WIFI
                )

                saveHistoryDevice("$ip:$port")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ConnectActivity, "连接成功！", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.btnConnectWifi.isEnabled = true
                    binding.btnConnectWifi.text = "连接无线设备"
                    binding.tvWifiStatus.visibility = View.VISIBLE
                    binding.tvWifiStatus.text = e.message ?: "连接失败"
                    Toast.makeText(this@ConnectActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadHistoryDevices() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet()
        if (historySet.isEmpty()) {
            binding.tvHistoryTitle.visibility = View.GONE
            binding.chipGroupHistory.visibility = View.GONE
            return
        }

        binding.tvHistoryTitle.visibility = View.VISIBLE
        binding.chipGroupHistory.visibility = View.VISIBLE
        binding.chipGroupHistory.removeAllViews()

        historySet.sorted().forEach { endpoint ->
            val chip = Chip(this).apply {
                text = endpoint
                isClickable = true
                isCheckable = false
                setOnClickListener {
                    val parts = endpoint.split(":")
                    if (parts.size == 2) {
                        binding.etIpAddress.setText(parts[0])
                        binding.etPort.setText(parts[1])
                    } else {
                        binding.etIpAddress.setText(endpoint)
                    }
                }
            }
            binding.chipGroupHistory.addView(chip)
        }
    }

    private fun saveHistoryDevice(endpoint: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_HISTORY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(endpoint)
        prefs.edit().putStringSet(KEY_HISTORY, current).apply()
    }

    private fun scanUsbDevices() {
        val targets = usbHelper.findSupportedDevices()
        if (targets.isEmpty()) {
            binding.tvUsbDevices.text = "未检测到已连接的 USB OTG 设备。\n请插入 OTG 线并开启被控端 USB 调试。"
        } else {
            val target = targets.first()
            val modeName = if (target.isFastboot) "Fastboot 模式" else "ADB 模式"
            binding.tvUsbDevices.text = "发现设备: ${target.device.deviceName} (Vendor: ${target.device.vendorId}, $modeName)"

            if (!usbHelper.hasPermission(target.device)) {
                binding.btnScanUsb.text = "请求 USB 权限并连接"
                binding.btnScanUsb.setOnClickListener {
                    usbHelper.requestPermission(target.device)
                }
            } else {
                binding.btnScanUsb.text = "连接该 USB 设备"
                binding.btnScanUsb.setOnClickListener {
                    connectUsbDevice(target)
                }
            }
        }
    }

    private fun connectUsbDevice(target: UsbManagerHelper.UsbTarget) {
        binding.btnScanUsb.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val transport = usbHelper.openConnection(target)
                    ?: throw java.io.IOException("无法打开 USB 接口连接")

                val crypto = AdbCrypto.loadOrGenerate(this@ConnectActivity)
                val conn = AdbConnection(transport, crypto)
                conn.connect()

                DeviceManager.adbClient = AdbClient(conn)
                DeviceManager.currentDevice = ConnectedDevice(
                    id = target.device.deviceName,
                    name = "USB Device (${target.device.productName ?: "OTG"})",
                    type = ConnectionType.USB_OTG
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ConnectActivity, "USB OTG 连接成功！", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.btnScanUsb.isEnabled = true
                    Toast.makeText(this@ConnectActivity, "USB 连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
