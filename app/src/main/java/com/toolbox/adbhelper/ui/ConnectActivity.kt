package com.toolbox.adbhelper.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbHelper = UsbManagerHelper(this)

        initViews()
        scanUsbDevices()
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnConnectWifi.setOnClickListener {
            val ip = binding.etIpAddress.text.toString().trim()
            val portStr = binding.etPort.text.toString().trim()
            val port = portStr.toIntOrNull() ?: 5555

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

    private fun connectWifiDevice(ip: String, port: Int) {
        binding.btnConnectWifi.isEnabled = false
        binding.btnConnectWifi.text = "正在连接中..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val transport = TcpTransport(ip, port)
                val crypto = AdbCrypto.loadOrGenerate(this@ConnectActivity)
                val conn = AdbConnection(transport, crypto)
                conn.connect()

                val client = AdbClient(conn)
                DeviceManager.adbClient = client
                DeviceManager.currentDevice = ConnectedDevice(
                    id = "$ip:$port",
                    name = "$ip:$port",
                    type = ConnectionType.WIFI
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ConnectActivity, "连接成功！", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.btnConnectWifi.isEnabled = true
                    binding.btnConnectWifi.text = "连接无线设备"
                    Toast.makeText(this@ConnectActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
