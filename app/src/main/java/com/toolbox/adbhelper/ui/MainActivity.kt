package com.toolbox.adbhelper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
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
import com.toolbox.adbhelper.core.model.ScrcpyPresets
import com.toolbox.adbhelper.databinding.ActivityMainBinding
import com.toolbox.adbhelper.databinding.DialogScrcpySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 避免从系统桌面图标或通知栏重复启动时覆盖已有子页面任务栈
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN == intent.action) {
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceState()
        updateMirrorSubtitle()

        if (!DeviceManager.isConnected && !DeviceManager.isExplicitDisconnect) {
            DeviceManager.loadLastDevice(this)
            if (!DeviceManager.lastEndpoint.isNullOrBlank()) {
                lifecycleScope.launch {
                    val success = DeviceManager.tryReconnect(this@MainActivity)
                    if (success && isActive) {
                        refreshDeviceState()
                        updateMirrorSubtitle()
                    }
                }
            }
        }
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
            val client = DeviceManager.adbClient
            if (client == null || !client.isConnected) {
                lifecycleScope.launch {
                    val reconnected = DeviceManager.getOrReconnectClient(this@MainActivity)
                    if (reconnected != null && reconnected.isConnected) {
                        refreshDeviceState()
                        startActivity(Intent(this@MainActivity, ScrcpyActivity::class.java))
                    } else {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("屏幕投屏")
                            .setMessage("当前尚未连接 ADB 设备。\n您可以前往连接，或者在连接前配置投屏的分辨率与码率预设。")
                            .setPositiveButton("连接设备") { _, _ ->
                                startActivity(Intent(this@MainActivity, ConnectActivity::class.java))
                            }
                            .setNeutralButton("配置投屏预设") { _, _ ->
                                showScrcpyPresetDialog()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
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
        val client = DeviceManager.adbClient
        if (client == null || !client.isConnected) {
            lifecycleScope.launch {
                val reconnectedClient = DeviceManager.getOrReconnectClient(this@MainActivity)
                if (reconnectedClient != null && reconnectedClient.isConnected) {
                    refreshDeviceState()
                    action()
                } else {
                    Toast.makeText(this@MainActivity, "请先连接 ADB 设备", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, ConnectActivity::class.java))
                }
            }
        } else {
            action()
        }
    }

    private var lastLoadedDeviceId: String? = null

    private fun refreshDeviceState() {
        val dev = DeviceManager.currentDevice
        val client = DeviceManager.adbClient
        if (dev != null && client != null && client.isConnected) {
            binding.tvDeviceName.text = "${dev.name} (${dev.type})"
            binding.tvDeviceStatus.text = "设备在线，点击可断开"
            binding.btnConnect.text = "断开"
            binding.cardDeviceInfo.visibility = View.VISIBLE

            // 如果已经加载过当前设备信息，无需重复发起多条 shell 命令，避免 socket 竞争与阻塞
            if (lastLoadedDeviceId != dev.id) {
                lifecycleScope.launch {
                    try {
                        val info = withContext(Dispatchers.IO) { client.getDeviceInfo() }
                        lastLoadedDeviceId = dev.id
                        updateDeviceInfoUI(info)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "读取设备详细信息失败: ${e.message}")
                        // 注意：获取设备信息失败绝不能粗暴断开 ADB 全局连接！保持在线状态即可
                    }
                }
            }
        } else {
            lastLoadedDeviceId = null
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
        val bitrate = prefs.getInt("scrcpy_bitrate", 8_000_000)
        val fps = prefs.getInt("scrcpy_max_fps", 60)
        val silenceTimeout = prefs.getLong("scrcpy_silence_timeout", 120_000L)
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
        val timeoutStr = ScrcpyPresets.getSilenceTimeoutShortLabel(silenceTimeout)
        binding.tvMirrorSubtitle.text = "预设: $resStr / $bitStr / ${fps}FPS · $timeoutStr"
    }

    private fun showScrcpyPresetDialog() {
        val prefs = getSharedPreferences("scrcpy_prefs", Context.MODE_PRIVATE)
        val dialogBinding = DialogScrcpySettingsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        val currentSize = prefs.getInt("scrcpy_max_size", 1280)
        val currentBitrate = prefs.getInt("scrcpy_bitrate", 8_000_000)
        val currentFps = prefs.getInt("scrcpy_max_fps", 60)
        val navPosName = prefs.getString("scrcpy_nav_pos", NavPosition.BOTTOM.name) ?: NavPosition.BOTTOM.name
        val isFullscreen = prefs.getBoolean("scrcpy_fullscreen", false)
        val isGestureGuard = prefs.getBoolean("scrcpy_gesture_guard", true)
        val turnScreenOff = prefs.getBoolean("scrcpy_screen_off", false)
        val currentAudio = prefs.getBoolean("scrcpy_audio_forward", true)
        val currentBgAudio = prefs.getBoolean("scrcpy_bg_audio", true)
        val currentMuteDevice = prefs.getBoolean("scrcpy_mute_device", false)
        val currentAudioSource = prefs.getInt("scrcpy_audio_source", 3)
        val currentQuality = prefs.getInt("scrcpy_audio_quality", 0)
        val currentGain = prefs.getFloat("scrcpy_audio_gain", 1.0f)
        val currentSilenceTimeout = prefs.getLong("scrcpy_silence_timeout", 120_000L)

        var selectedSize = currentSize
        var selectedBitrate = currentBitrate
        var selectedFps = currentFps
        var selectedNavPos = navPosName
        var selectedAudioSource = currentAudioSource
        var selectedQuality = currentQuality
        var selectedGain = currentGain
        var selectedSilenceTimeout = currentSilenceTimeout

        // 1. 初始化分辨率下拉
        val resLabels = ScrcpyPresets.RESOLUTION_OPTIONS.map { it.label }
        val resAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, resLabels)
        dialogBinding.actvResolution.setAdapter(resAdapter)
        dialogBinding.actvResolution.setText(ScrcpyPresets.getResolutionLabel(currentSize), false)
        dialogBinding.actvResolution.setOnItemClickListener { _, _, position, _ ->
            selectedSize = ScrcpyPresets.RESOLUTION_OPTIONS[position].value
        }

        // 2. 初始化码率下拉
        val bitLabels = ScrcpyPresets.BITRATE_OPTIONS.map { it.label }
        val bitAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, bitLabels)
        dialogBinding.actvBitrate.setAdapter(bitAdapter)
        dialogBinding.actvBitrate.setText(ScrcpyPresets.getBitrateLabel(currentBitrate), false)
        dialogBinding.actvBitrate.setOnItemClickListener { _, _, position, _ ->
            selectedBitrate = ScrcpyPresets.BITRATE_OPTIONS[position].value
        }

        // 3. 初始化帧率下拉
        val fpsLabels = ScrcpyPresets.FPS_OPTIONS.map { it.label }
        val fpsAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, fpsLabels)
        dialogBinding.actvFps.setAdapter(fpsAdapter)
        dialogBinding.actvFps.setText(ScrcpyPresets.getFpsLabel(currentFps), false)
        dialogBinding.actvFps.setOnItemClickListener { _, _, position, _ ->
            selectedFps = ScrcpyPresets.FPS_OPTIONS[position].value
        }

        // 4. 初始化导航栏位置下拉
        val navPosEnum = try { NavPosition.valueOf(navPosName) } catch (_: Exception) { NavPosition.BOTTOM }
        val navLabels = ScrcpyPresets.NAV_POSITION_OPTIONS.map { it.label }
        val navAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, navLabels)
        dialogBinding.actvNavPosition.setAdapter(navAdapter)
        dialogBinding.actvNavPosition.setText(ScrcpyPresets.getNavPositionLabel(navPosEnum), false)
        dialogBinding.actvNavPosition.setOnItemClickListener { _, _, position, _ ->
            selectedNavPos = ScrcpyPresets.NAV_POSITION_OPTIONS[position].value.name
        }

        // 5. 初始化音频源下拉
        val audioLabels = ScrcpyPresets.AUDIO_SOURCE_OPTIONS.map { it.label }
        val audioAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, audioLabels)
        dialogBinding.actvAudioSource.setAdapter(audioAdapter)
        dialogBinding.actvAudioSource.setText(ScrcpyPresets.getAudioSourceLabel(currentAudioSource), false)
        dialogBinding.actvAudioSource.setOnItemClickListener { _, _, position, _ ->
            selectedAudioSource = ScrcpyPresets.AUDIO_SOURCE_OPTIONS[position].value
        }

        // 6. 初始化音质品质模式下拉
        val qualityLabels = ScrcpyPresets.AUDIO_QUALITY_OPTIONS.map { it.label }
        val qualityAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, qualityLabels)
        dialogBinding.actvAudioQuality.setAdapter(qualityAdapter)
        dialogBinding.actvAudioQuality.setText(ScrcpyPresets.getAudioQualityLabel(currentQuality), false)
        dialogBinding.actvAudioQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQuality = ScrcpyPresets.AUDIO_QUALITY_OPTIONS[position].value
        }

        // 7. 初始化远端数字音量增益下拉
        val gainLabels = ScrcpyPresets.AUDIO_GAIN_OPTIONS.map { it.label }
        val gainAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, gainLabels)
        dialogBinding.actvAudioGain.setAdapter(gainAdapter)
        dialogBinding.actvAudioGain.setText(ScrcpyPresets.getAudioGainLabel(currentGain), false)
        dialogBinding.actvAudioGain.setOnItemClickListener { _, _, position, _ ->
            selectedGain = ScrcpyPresets.AUDIO_GAIN_OPTIONS[position].value
        }

        // 8. 初始化后台静音断开超时下拉
        val timeoutLabels = ScrcpyPresets.SILENCE_TIMEOUT_OPTIONS.map { it.label }
        val timeoutAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, timeoutLabels)
        dialogBinding.actvSilenceTimeout.setAdapter(timeoutAdapter)
        dialogBinding.actvSilenceTimeout.setText(ScrcpyPresets.getSilenceTimeoutLabel(currentSilenceTimeout), false)
        dialogBinding.actvSilenceTimeout.setOnItemClickListener { _, _, position, _ ->
            selectedSilenceTimeout = ScrcpyPresets.SILENCE_TIMEOUT_OPTIONS[position].value
        }

        // 9. 开关状态
        dialogBinding.switchFullscreen.isChecked = isFullscreen
        dialogBinding.switchGestureGuard.isChecked = isGestureGuard
        dialogBinding.switchScreenOff.isChecked = turnScreenOff
        dialogBinding.switchAudioForward.isChecked = currentAudio
        dialogBinding.switchBackgroundAudio.isChecked = currentBgAudio
        dialogBinding.switchMuteDevice.isChecked = currentMuteDevice

        val isDeviceConnected = DeviceManager.adbClient != null
        if (isDeviceConnected) {
            dialogBinding.btnApplySettings.text = "保存并开始投屏"
        } else {
            dialogBinding.btnApplySettings.text = "保存投屏预设"
        }

        dialogBinding.btnApplySettings.setOnClickListener {
            prefs.edit()
                .putInt("scrcpy_max_size", selectedSize)
                .putInt("scrcpy_bitrate", selectedBitrate)
                .putInt("scrcpy_max_fps", selectedFps)
                .putString("scrcpy_nav_pos", selectedNavPos)
                .putInt("scrcpy_audio_source", selectedAudioSource)
                .putInt("scrcpy_audio_quality", selectedQuality)
                .putFloat("scrcpy_audio_gain", selectedGain)
                .putLong("scrcpy_silence_timeout", selectedSilenceTimeout)
                .putBoolean("scrcpy_fullscreen", dialogBinding.switchFullscreen.isChecked)
                .putBoolean("scrcpy_gesture_guard", dialogBinding.switchGestureGuard.isChecked)
                .putBoolean("scrcpy_screen_off", dialogBinding.switchScreenOff.isChecked)
                .putBoolean("scrcpy_audio_forward", dialogBinding.switchAudioForward.isChecked)
                .putBoolean("scrcpy_bg_audio", dialogBinding.switchBackgroundAudio.isChecked)
                .putBoolean("scrcpy_mute_device", dialogBinding.switchMuteDevice.isChecked)
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
