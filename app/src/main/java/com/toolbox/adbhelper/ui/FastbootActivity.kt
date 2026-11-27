package com.toolbox.adbhelper.ui

import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.R
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
                    .setTitle(R.string.high_risk_warning)
                    .setMessage(R.string.unlock_warning)
                    .setPositiveButton(R.string.unlock_now) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            val res = client.unlockBootloader()
                            withContext(Dispatchers.Main) {
                                binding.tvFastbootLog.append("解锁结果: ${res.status} ${res.message}\n")
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
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
            Toast.makeText(this, R.string.fastboot_not_found, Toast.LENGTH_LONG).show()
            binding.tvFastbootLog.text = "未检测到处于 Fastboot 模式的设备。\n"
            return
        }

        if (!usbHelper.hasPermission(fastbootTarget.device)) {
            usbHelper.requestPermission(fastbootTarget.device)
            Toast.makeText(this, R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                val conn = usbManager.openDevice(fastbootTarget.device)
                    ?: throw java.io.IOException(getString(R.string.fastboot_usb_open_failed))
                if (!conn.claimInterface(fastbootTarget.iface, true)) {
                    throw java.io.IOException(getString(R.string.fastboot_interface_claim_failed))
                }
                val client = FastbootClient(conn, fastbootTarget.inEndpoint, fastbootTarget.outEndpoint)
                DeviceManager.fastbootClient = client

                withContext(Dispatchers.Main) {
                    binding.tvFastbootLog.text = "Fastboot 设备连接成功！\n设备名: ${fastbootTarget.device.deviceName}\n"
                    Toast.makeText(this@FastbootActivity, R.string.fastboot_connection_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvFastbootLog.text = "连接失败: ${e.message}\n"
                    Toast.makeText(this@FastbootActivity, getString(R.string.connection_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkFastbootAndRun(action: (FastbootClient) -> Unit) {
        val client = DeviceManager.fastbootClient
        if (client == null) {
            Toast.makeText(this, R.string.connect_fastboot_first, Toast.LENGTH_SHORT).show()
        } else {
            action(client)
        }
    }
}
