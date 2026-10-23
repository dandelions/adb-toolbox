package com.toolbox.adbhelper.ui

import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.fastboot.FastbootClient
import com.toolbox.adbhelper.core.usb.UsbManagerHelper
import com.toolbox.adbhelper.databinding.ActivityFastbootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FastbootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFastbootBinding
    private lateinit var usbHelper: UsbManagerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFastbootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbHelper = UsbManagerHelper(this)
        initViews()
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnConnectFastboot.setOnClickListener {
            connectFastboot()
        }

        binding.btnGetVars.setOnClickListener {
            checkFastbootAndRun { client ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val vars = client.getAllVars()
                        val sb = StringBuilder("=== Fastboot 变量列表 ===\n")
                        vars.forEach { (k, v) -> sb.append("$k: $v\n") }
                        withContext(Dispatchers.Main) {
                            binding.tvFastbootLog.text = sb.toString()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            binding.tvFastbootLog.text = "读取失败: ${e.message}\n"
                        }
                    }
                }
            }
        }

        binding.btnUnlockBootloader.setOnClickListener {
            checkFastbootAndRun { client ->
                AlertDialog.Builder(this)
                    .setTitle("高危警告")
                    .setMessage("解锁 Bootloader 将会清除设备所有数据并重启！确定要继续吗？")
                    .setPositiveButton("立即解锁") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val res = client.unlockBootloader()
                            withContext(Dispatchers.Main) {
                                binding.tvFastbootLog.append("解锁结果: ${res.status} ${res.message}\n")
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        binding.btnRebootSystem.setOnClickListener {
            checkFastbootAndRun { client ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = client.reboot()
                    withContext(Dispatchers.Main) {
                        binding.tvFastbootLog.append("重启到系统: ${res.status}\n")
                    }
                }
            }
        }

        binding.btnRebootRecovery.setOnClickListener {
            checkFastbootAndRun { client ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = client.reboot("recovery")
                    withContext(Dispatchers.Main) {
                        binding.tvFastbootLog.append("重启到 Recovery: ${res.status}\n")
                    }
                }
            }
        }

        binding.btnRebootBootloader.setOnClickListener {
            checkFastbootAndRun { client ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val res = client.reboot("bootloader")
                    withContext(Dispatchers.Main) {
                        binding.tvFastbootLog.append("重启到 Bootloader: ${res.status}\n")
                    }
                }
            }
        }
    }

    private fun connectFastboot() {
        val targets = usbHelper.findSupportedDevices()
        val fastbootTarget = targets.firstOrNull { it.isFastboot }
        if (fastbootTarget == null) {
            Toast.makeText(this, "未发现 Fastboot 设备，请确认已进入 Bootloader 并连接 OTG 线", Toast.LENGTH_LONG).show()
            binding.tvFastbootLog.text = "未检测到处于 Fastboot 模式的设备。\n"
            return
        }

        if (!usbHelper.hasPermission(fastbootTarget.device)) {
            usbHelper.requestPermission(fastbootTarget.device)
            Toast.makeText(this, "正在请求 USB 权限，请在弹窗中允许", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                val conn = usbManager.openDevice(fastbootTarget.device)
                    ?: throw java.io.IOException("无法打开 USB 设备")
                if (!conn.claimInterface(fastbootTarget.iface, true)) {
                    throw java.io.IOException("无法独占 Fastboot 接口")
                }
                val client = FastbootClient(conn, fastbootTarget.inEndpoint, fastbootTarget.outEndpoint)
                DeviceManager.fastbootClient = client

                withContext(Dispatchers.Main) {
                    binding.tvFastbootLog.text = "Fastboot 设备连接成功！\n设备名: ${fastbootTarget.device.deviceName}\n"
                    Toast.makeText(this@FastbootActivity, "Fastboot 连接成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvFastbootLog.text = "连接失败: ${e.message}\n"
                    Toast.makeText(this@FastbootActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkFastbootAndRun(action: (FastbootClient) -> Unit) {
        val client = DeviceManager.fastbootClient
        if (client == null) {
            Toast.makeText(this, "请先连接 Fastboot 设备", Toast.LENGTH_SHORT).show()
        } else {
            action(client)
        }
    }
}
