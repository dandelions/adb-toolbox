package com.toolbox.adbhelper.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.model.DeviceInfo
import com.toolbox.adbhelper.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceState()
    }

    private fun initViews() {
        binding.cardConnection.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }

        binding.btnConnect.setOnClickListener {
            if (DeviceManager.isConnected) {
                DeviceManager.disconnect()
                refreshDeviceState()
                Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ConnectActivity::class.java))
            }
        }

        binding.btnFeatureApps.setOnClickListener {
            checkConnectionAndRun {
                startActivity(Intent(this, AppManagerActivity::class.java))
            }
        }

        binding.btnFeatureFiles.setOnClickListener {
            checkConnectionAndRun {
                startActivity(Intent(this, FileManagerActivity::class.java))
            }
        }

        binding.btnFeatureMirror.setOnClickListener {
            checkConnectionAndRun {
                startActivity(Intent(this, ScrcpyActivity::class.java))
            }
        }

        binding.btnFeatureTerminal.setOnClickListener {
            checkConnectionAndRun {
                startActivity(Intent(this, TerminalActivity::class.java))
            }
        }

        binding.btnFeatureFastboot.setOnClickListener {
            startActivity(Intent(this, FastbootActivity::class.java))
        }

        binding.btnFeatureUtilities.setOnClickListener {
            checkConnectionAndRun {
                showUtilitiesDialog()
            }
        }
    }

    private fun checkConnectionAndRun(action: () -> Unit) {
        if (DeviceManager.adbClient == null) {
            Toast.makeText(this, "请先连接 ADB 设备", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, ConnectActivity::class.java))
        } else {
            action()
        }
    }

    private fun refreshDeviceState() {
        val dev = DeviceManager.currentDevice
        val client = DeviceManager.adbClient
        if (dev != null && client != null) {
            binding.tvDeviceName.text = "${dev.name} (${dev.type})"
            binding.tvDeviceStatus.text = "设备在线，点击可断开"
            binding.btnConnect.text = "断开"
            binding.cardDeviceInfo.visibility = View.VISIBLE

            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { client.getDeviceInfo() }
                updateDeviceInfoUI(info)
            }
        } else {
            binding.tvDeviceName.text = "未连接任何设备"
            binding.tvDeviceStatus.text = "点击连接设备 (WiFi / OTG)"
            binding.btnConnect.text = "连接"
            binding.cardDeviceInfo.visibility = View.GONE
        }
    }

    private fun updateDeviceInfoUI(info: DeviceInfo) {
        binding.tvInfoAndroid.text = "机型系统: ${info.brand} ${info.model} (Android ${info.androidVersion}, SDK ${info.sdkVersion})"
        binding.tvInfoBattery.text = "电池状态: 电量 ${info.batteryLevel}%, 温度 ${info.batteryTemp}°C, 状态: ${info.batteryStatus}"
        binding.tvInfoScreen.text = "屏幕显示: 分辨率 ${info.resolution}, 像素密度 ${info.density}"
        binding.tvInfoStorage.text = "内置存储: 总空间 ${info.storageTotal}, 剩余空间 ${info.storageFree}"
    }

    private fun showUtilitiesDialog() {
        val client = DeviceManager.adbClient ?: return
        val options = arrayOf(
            "修改屏幕 DPI",
            "重置屏幕 DPI",
            "一键截取屏幕",
            "免Root激活: Shizuku",
            "免Root激活: 黑阈",
            "重启: 重启到系统",
            "重启: 重启到 Recovery",
            "重启: 重启到 Bootloader"
        )

        AlertDialog.Builder(this)
            .setTitle("实用工具箱")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDpiDialog(client)
                    1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            client.modifyDensity(0)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "DPI 已重置", Toast.LENGTH_SHORT).show()
                                refreshDeviceState()
                            }
                        }
                    }
                    2 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            client.executeShell("screencap -p /sdcard/screenshot.png")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "截图已保存到对端 /sdcard/screenshot.png", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    3 -> activateApp(client, "shizuku")
                    4 -> activateApp(client, "blackroom")
                    5 -> rebootDevice(client, "")
                    6 -> rebootDevice(client, "recovery")
                    7 -> rebootDevice(client, "bootloader")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDpiDialog(client: com.toolbox.adbhelper.core.adb.AdbClient) {
        val input = EditText(this).apply {
            hint = "输入 DPI 数值 (如 420)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("修改屏幕 DPI")
            .setView(input)
            .setPositiveButton("应用") { _, _ ->
                val dpi = input.text.toString().toIntOrNull() ?: 0
                if (dpi > 0) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        client.modifyDensity(dpi)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "DPI 设置成功: $dpi", Toast.LENGTH_SHORT).show()
                            refreshDeviceState()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun activateApp(client: com.toolbox.adbhelper.core.adb.AdbClient, type: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = client.activateAppService(type)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("激活结果")
                    .setMessage(if (res.isBlank()) "激活命令已执行！" else res)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun rebootDevice(client: com.toolbox.adbhelper.core.adb.AdbClient, mode: String) {
        AlertDialog.Builder(this)
            .setTitle("重启确认")
            .setMessage("确定要重启对端设备吗？")
            .setPositiveButton("确认") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    client.reboot(mode)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
