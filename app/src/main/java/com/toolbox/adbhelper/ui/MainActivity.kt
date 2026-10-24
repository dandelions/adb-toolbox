package com.toolbox.adbhelper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.toolbox.adbhelper.R
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.model.DeviceInfo
import com.toolbox.adbhelper.core.model.NavPosition
import com.toolbox.adbhelper.databinding.ActivityMainBinding
import com.toolbox.adbhelper.databinding.DialogScrcpySettingsBinding
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
        updateMirrorSubtitle()
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
            if (DeviceManager.adbClient == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("屏幕投屏")
                    .setMessage("当前尚未连接 ADB 设备。\n您可以前往连接，或者在连接前配置投屏的分辨率与码率预设。")
                    .setPositiveButton("连接设备") { _, _ ->
                        startActivity(Intent(this, ConnectActivity::class.java))
                    }
                    .setNeutralButton("配置投屏预设") { _, _ ->
                        showScrcpyPresetDialog()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                startActivity(Intent(this, ScrcpyActivity::class.java))
            }
        }

        binding.btnFeatureMirror.setOnLongClickListener {
            showScrcpyPresetDialog()
            true
        }

        binding.btnMirrorSettings.setOnClickListener {
            showScrcpyPresetDialog()
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

    private fun updateMirrorSubtitle() {
        val prefs = getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
        val size = prefs.getInt("scrcpy_max_size", 1280)
        val bitrate = prefs.getInt("scrcpy_bitrate", 4_000_000)
        val resStr = when (size) {
            0 -> "原画"
            1920 -> "1080P"
            1280 -> "720P"
            854 -> "480P"
            else -> "${size}P"
        }
        val bitStr = when (bitrate) {
            2_000_000 -> "2M"
            4_000_000 -> "4M"
            8_000_000 -> "8M"
            16_000_000 -> "16M"
            else -> "${bitrate / 1_000_000}M"
        }
        binding.tvMirrorSubtitle.text = "预设: $resStr / $bitStr"
    }

    private fun showScrcpyPresetDialog() {
        val prefs = getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
        val dialogBinding = DialogScrcpySettingsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        val currentSize = prefs.getInt("scrcpy_max_size", 1280)
        val currentBitrate = prefs.getInt("scrcpy_bitrate", 4_000_000)
        val navPosName = prefs.getString("scrcpy_nav_pos", NavPosition.BOTTOM.name) ?: NavPosition.BOTTOM.name
        val isFullscreen = prefs.getBoolean("scrcpy_fullscreen", false)
        val turnScreenOff = prefs.getBoolean("scrcpy_screen_off", false)

        // 1. 初始化分辨率
        when (currentSize) {
            0 -> dialogBinding.btnResNative.isChecked = true
            1920 -> dialogBinding.btnRes1080.isChecked = true
            1280 -> dialogBinding.btnRes720.isChecked = true
            854 -> dialogBinding.btnRes480.isChecked = true
            else -> dialogBinding.btnRes720.isChecked = true
        }

        // 2. 初始化码率
        when (currentBitrate) {
            2_000_000 -> dialogBinding.btnBitrate2M.isChecked = true
            4_000_000 -> dialogBinding.btnBitrate4M.isChecked = true
            8_000_000 -> dialogBinding.btnBitrate8M.isChecked = true
            16_000_000 -> dialogBinding.btnBitrate16M.isChecked = true
            else -> dialogBinding.btnBitrate4M.isChecked = true
        }

        // 3. 初始化导航栏位置
        when (navPosName) {
            NavPosition.BOTTOM.name -> dialogBinding.btnNavBottom.isChecked = true
            NavPosition.TOP.name -> dialogBinding.btnNavTop.isChecked = true
            NavPosition.LEFT.name -> dialogBinding.btnNavLeft.isChecked = true
            NavPosition.RIGHT.name -> dialogBinding.btnNavRight.isChecked = true
            else -> dialogBinding.btnNavBottom.isChecked = true
        }

        // 4. 开关
        dialogBinding.switchFullscreen.isChecked = isFullscreen
        dialogBinding.switchScreenOff.isChecked = turnScreenOff

        val isDeviceConnected = DeviceManager.adbClient != null
        if (isDeviceConnected) {
            dialogBinding.btnApplySettings.text = "保存并开始投屏"
        } else {
            dialogBinding.btnApplySettings.text = "保存投屏预设"
        }

        dialogBinding.btnApplySettings.setOnClickListener {
            val newSize = when (dialogBinding.toggleResolution.checkedButtonId) {
                R.id.btnResNative -> 0
                R.id.btnRes1080 -> 1920
                R.id.btnRes720 -> 1280
                R.id.btnRes480 -> 854
                else -> 1280
            }

            val newBitrate = when (dialogBinding.toggleBitrate.checkedButtonId) {
                R.id.btnBitrate2M -> 2_000_000
                R.id.btnBitrate4M -> 4_000_000
                R.id.btnBitrate8M -> 8_000_000
                R.id.btnBitrate16M -> 16_000_000
                else -> 4_000_000
            }

            val newNavPos = when (dialogBinding.toggleNavPosition.checkedButtonId) {
                R.id.btnNavBottom -> NavPosition.BOTTOM.name
                R.id.btnNavTop -> NavPosition.TOP.name
                R.id.btnNavLeft -> NavPosition.LEFT.name
                R.id.btnNavRight -> NavPosition.RIGHT.name
                R.id.btnNavHide -> NavPosition.HIDE.name
                else -> NavPosition.BOTTOM.name
            }

            prefs.edit()
                .putInt("scrcpy_max_size", newSize)
                .putInt("scrcpy_bitrate", newBitrate)
                .putString("scrcpy_nav_pos", newNavPos)
                .putBoolean("scrcpy_fullscreen", dialogBinding.switchFullscreen.isChecked)
                .putBoolean("scrcpy_screen_off", dialogBinding.switchScreenOff.isChecked)
                .apply()

            updateMirrorSubtitle()
            dialog.dismiss()

            if (isDeviceConnected) {
                startActivity(Intent(this, ScrcpyActivity::class.java))
            } else {
                Toast.makeText(this, "投屏预设配置已保存！", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
