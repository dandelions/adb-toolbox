package com.toolbox.adbhelper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.toolbox.adbhelper.App
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
        setSupportActionBar(binding.toolbar)

        initViews()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(R.string.language)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.title == getString(R.string.language)) {
            val tags = arrayOf(getString(R.string.language_chinese), getString(R.string.language_english))
            val currentTag = resources.configuration.locales[0].language
            val checked = if (currentTag == "en") 1 else 0
            AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(tags, checked) { dialog, which ->
                    App.setLanguage(if (which == 1) "en" else "zh")
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceState()

        if (!DeviceManager.isConnected && !DeviceManager.isExplicitDisconnect) {
            DeviceManager.loadLastDevice(this)
            if (!DeviceManager.lastEndpoint.isNullOrBlank()) {
                lifecycleScope.launch {
                    val success = DeviceManager.tryReconnect(this@MainActivity)
                    if (success && isActive) {
                        refreshDeviceState()
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
                Toast.makeText(this, R.string.device_disconnected_toast, Toast.LENGTH_SHORT).show()
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
                            .setTitle(R.string.mirror)
                            .setMessage(R.string.mirror_not_connected_message)
                            .setPositiveButton(R.string.connect_device) { _, _ ->
                                startActivity(Intent(this@MainActivity, ConnectActivity::class.java))
                            }
                            .setNeutralButton(R.string.configure_mirror_preset) { _, _ ->
                                showScrcpyPresetDialog()
                            }
                            .setNegativeButton(R.string.cancel, null)
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
                    Toast.makeText(this@MainActivity, R.string.connect_adb_first, Toast.LENGTH_SHORT).show()
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
            binding.tvDeviceStatus.text = getString(R.string.device_online_click_disconnect)
            binding.btnConnect.text = getString(R.string.disconnect)
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
            binding.tvDeviceName.text = getString(R.string.not_connected)
            binding.tvDeviceStatus.text = getString(R.string.connect_device_wifi_otg)
            binding.btnConnect.text = getString(R.string.connect)
            binding.cardDeviceInfo.visibility = View.GONE
        }
    }

    private fun updateDeviceInfoUI(info: DeviceInfo) {
        binding.tvInfoAndroid.text = getString(R.string.device_android_info, info.brand, info.model, info.androidVersion, info.sdkVersion)
        binding.tvInfoBattery.text = getString(R.string.device_battery_info, info.batteryLevel, info.batteryTemp, info.batteryStatus)
        binding.tvInfoScreen.text = getString(R.string.device_screen_info, info.resolution, info.density)
        val total = com.toolbox.adbhelper.core.adb.AdbClient.formatStorageToGb(info.storageTotal)
        val free = com.toolbox.adbhelper.core.adb.AdbClient.formatStorageToGb(info.storageFree)
        binding.tvInfoStorage.text = if (total != "Unknown" && free != "Unknown") {
            getString(R.string.storage_info, total, free)
        } else if (total != "Unknown") {
            getString(R.string.storage_total_info, total)
        } else {
            getString(R.string.storage_unknown)
        }
    }

    private fun showUtilitiesDialog() {
        val client = DeviceManager.adbClient ?: return
        val options = arrayOf(
            getString(R.string.change_dpi),
            getString(R.string.dpi_reset_action),
            getString(R.string.screenshot_action),
            getString(R.string.activate_shizuku),
            getString(R.string.activate_blackroom),
            getString(R.string.reboot_system_action),
            getString(R.string.reboot_recovery_action),
            getString(R.string.reboot_bootloader_action)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.utilities)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDpiDialog(client)
                    1 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            client.modifyDensity(0)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, R.string.dpi_reset, Toast.LENGTH_SHORT).show()
                                refreshDeviceState()
                            }
                        }
                    }
                    2 -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            client.executeShell("screencap -p /sdcard/screenshot.png")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, R.string.screenshot_saved, Toast.LENGTH_LONG).show()
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
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDpiDialog(client: com.toolbox.adbhelper.core.adb.AdbClient) {
        val input = EditText(this).apply {
            hint = getString(R.string.dpi_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_dpi)
            .setView(input)
            .setPositiveButton(R.string.apply) { _, _ ->
                val dpi = input.text.toString().toIntOrNull() ?: 0
                if (dpi > 0) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        client.modifyDensity(dpi)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.dpi_set_success, dpi), Toast.LENGTH_SHORT).show()
                            refreshDeviceState()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun activateApp(client: com.toolbox.adbhelper.core.adb.AdbClient, type: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val res = client.activateAppService(type)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.activation_result)
                    .setMessage(if (res.isBlank()) getString(R.string.activation_done) else res)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            }
        }
    }

    private fun rebootDevice(client: com.toolbox.adbhelper.core.adb.AdbClient, mode: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reboot_confirmation)
            .setMessage(R.string.reboot_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    client.reboot(mode)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            dialogBinding.btnApplySettings.setText(R.string.save_and_start_mirror)
        } else {
            dialogBinding.btnApplySettings.setText(R.string.save_mirror_preset)
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

            dialog.dismiss()

            if (isDeviceConnected) {
                startActivity(Intent(this, ScrcpyActivity::class.java))
            } else {
                Toast.makeText(this, R.string.mirror_preset_saved, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
