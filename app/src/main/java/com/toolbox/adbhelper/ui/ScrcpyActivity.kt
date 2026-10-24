package com.toolbox.adbhelper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.R
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.model.NavPosition
import com.toolbox.adbhelper.core.model.ScrcpyConfig
import com.toolbox.adbhelper.core.model.ScrcpyPresets
import com.toolbox.adbhelper.core.scrcpy.ScrcpyManager
import com.toolbox.adbhelper.databinding.ActivityScrcpyBinding
import com.toolbox.adbhelper.databinding.DialogScrcpySettingsBinding
import kotlinx.coroutines.*

class ScrcpyActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityScrcpyBinding
    private var scrcpyManager: ScrcpyManager? = null
    private var streamJob: Job? = null

    private lateinit var prefs: SharedPreferences
    private val config = ScrcpyConfig()

    private var deviceWidth = 1080
    private var deviceHeight = 2400

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    private var isScreenOff = false
    private var surfaceCreated = false

    companion object {
        private const val PREFS_NAME = "scrcpy_prefs"
        private const val KEY_MAX_SIZE = "scrcpy_max_size"
        private const val KEY_BITRATE = "scrcpy_bitrate"
        private const val KEY_NAV_POS = "scrcpy_nav_pos"
        private const val KEY_FULLSCREEN = "scrcpy_fullscreen"
        private const val KEY_SCREEN_OFF = "scrcpy_screen_off"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScrcpyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val client = DeviceManager.adbClient
        if (client == null) {
            Toast.makeText(this, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadConfig()

        scrcpyManager = ScrcpyManager(this, client)

        initViews()
        initTouchControl()

        binding.surfaceView.holder.addCallback(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val (w, h) = scrcpyManager?.getDeviceResolution() ?: Pair(1080, 2400)
            deviceWidth = w
            deviceHeight = h

            if (config.turnScreenOff) {
                scrcpyManager?.turnScreenOff()
                isScreenOff = true
            }

            withContext(Dispatchers.Main) {
                if (surfaceCreated && scrcpyManager?.isStreamingNow() != true) {
                    startStreaming()
                }
            }
        }
    }

    private fun loadConfig() {
        config.maxSize = prefs.getInt(KEY_MAX_SIZE, 1280)
        config.bitRate = prefs.getInt(KEY_BITRATE, 4_000_000)
        val navPosName = prefs.getString(KEY_NAV_POS, NavPosition.BOTTOM.name) ?: NavPosition.BOTTOM.name
        config.navPosition = try {
            NavPosition.valueOf(navPosName)
        } catch (_: Exception) {
            NavPosition.BOTTOM
        }
        config.isFullscreen = prefs.getBoolean(KEY_FULLSCREEN, false)
        config.turnScreenOff = prefs.getBoolean(KEY_SCREEN_OFF, false)
    }

    private fun saveConfig() {
        prefs.edit()
            .putInt(KEY_MAX_SIZE, config.maxSize)
            .putInt(KEY_BITRATE, config.bitRate)
            .putString(KEY_NAV_POS, config.navPosition.name)
            .putBoolean(KEY_FULLSCREEN, config.isFullscreen)
            .putBoolean(KEY_SCREEN_OFF, config.turnScreenOff)
            .apply()
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        val manager = scrcpyManager ?: return
        binding.btnBack.setOnClickListener { lifecycleScope.launch { manager.sendBack() } }
        binding.btnHome.setOnClickListener { lifecycleScope.launch { manager.sendHome() } }
        binding.btnRecent.setOnClickListener { lifecycleScope.launch { manager.sendAppSwitch() } }
        binding.btnVolUp.setOnClickListener { lifecycleScope.launch { manager.sendVolumeUp() } }
        binding.btnVolDown.setOnClickListener { lifecycleScope.launch { manager.sendVolumeDown() } }

        // 熄屏控制
        binding.btnScreenOff.setOnClickListener {
            toggleDeviceScreen()
        }

        // 全屏切换
        binding.btnToolbarFullscreen.setOnClickListener {
            config.isFullscreen = !config.isFullscreen
            applyFullscreen(config.isFullscreen)
            saveConfig()
        }

        // 设置入口
        binding.btnToolbarSettings.setOnClickListener { showSettingsDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        // 浮动悬浮球（当导航栏隐藏或浮动时使用）
        binding.fabQuickMenu.setOnClickListener {
            showQuickMenuDialog()
        }

        applyNavPosition(config.navPosition)
        applyFullscreen(config.isFullscreen)
    }

    private fun toggleDeviceScreen() {
        val manager = scrcpyManager ?: return
        lifecycleScope.launch {
            if (isScreenOff) {
                manager.turnScreenOn()
                isScreenOff = false
                Toast.makeText(this@ScrcpyActivity, "已唤醒被控端屏幕", Toast.LENGTH_SHORT).show()
            } else {
                manager.turnScreenOff()
                isScreenOff = true
                Toast.makeText(this@ScrcpyActivity, "已熄灭被控端屏幕 (投屏后台运行)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFullscreen(fullscreen: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (fullscreen) {
            binding.toolbar.visibility = View.GONE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            binding.toolbar.visibility = View.VISIBLE
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyNavPosition(position: NavPosition) {
        val wrapperParams = binding.navBarWrapper.layoutParams as FrameLayout.LayoutParams
        val content = binding.navBarContent

        when (position) {
            NavPosition.BOTTOM -> {
                binding.navBarWrapper.visibility = View.VISIBLE
                binding.fabQuickMenu.visibility = View.GONE
                wrapperParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                wrapperParams.setMargins(0, 0, 0, 16)
                content.orientation = LinearLayout.HORIZONTAL
            }
            NavPosition.TOP -> {
                binding.navBarWrapper.visibility = View.VISIBLE
                binding.fabQuickMenu.visibility = View.GONE
                wrapperParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val topMargin = if (config.isFullscreen) 16 else 140
                wrapperParams.setMargins(0, topMargin, 0, 0)
                content.orientation = LinearLayout.HORIZONTAL
            }
            NavPosition.LEFT -> {
                binding.navBarWrapper.visibility = View.VISIBLE
                binding.fabQuickMenu.visibility = View.GONE
                wrapperParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                wrapperParams.setMargins(16, 0, 0, 0)
                content.orientation = LinearLayout.VERTICAL
            }
            NavPosition.RIGHT -> {
                binding.navBarWrapper.visibility = View.VISIBLE
                binding.fabQuickMenu.visibility = View.GONE
                wrapperParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                wrapperParams.setMargins(0, 0, 16, 0)
                content.orientation = LinearLayout.VERTICAL
            }
            NavPosition.FLOATING, NavPosition.HIDE -> {
                binding.navBarWrapper.visibility = View.GONE
                binding.fabQuickMenu.visibility = View.VISIBLE
            }
        }
        binding.navBarWrapper.layoutParams = wrapperParams
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchControl() {
        val touchListener = View.OnTouchListener { v, event ->
            val manager = scrcpyManager ?: return@OnTouchListener false
            val viewWidth = v.width
            val viewHeight = v.height
            if (viewWidth <= 0 || viewHeight <= 0) return@OnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val upX = event.x
                    val upY = event.y
                    val duration = System.currentTimeMillis() - touchDownTime
                    val dx = upX - touchDownX
                    val dy = upY - touchDownY

                    val targetDownX = (touchDownX / viewWidth * deviceWidth).toInt().coerceIn(0, deviceWidth)
                    val targetDownY = (touchDownY / viewHeight * deviceHeight).toInt().coerceIn(0, deviceHeight)
                    val targetUpX = (upX / viewWidth * deviceWidth).toInt().coerceIn(0, deviceWidth)
                    val targetUpY = (upY / viewHeight * deviceHeight).toInt().coerceIn(0, deviceHeight)

                    lifecycleScope.launch(Dispatchers.IO) {
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) < 25) {
                            manager.sendTap(targetDownX, targetDownY)
                        } else {
                            manager.sendSwipe(targetDownX, targetDownY, targetUpX, targetUpY, duration.toInt().coerceAtLeast(100))
                        }
                    }
                    true
                }
                else -> false
            }
        }

        binding.surfaceView.setOnTouchListener(touchListener)
        binding.ivScreen.setOnTouchListener(touchListener)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceCreated = true
        if (deviceWidth > 0 && deviceHeight > 0 && scrcpyManager?.isStreamingNow() != true) {
            startStreaming()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceCreated = false
        stopStreaming()
    }

    private var watchdogJob: Job? = null

    private fun startStreaming() {
        stopStreaming()
        binding.surfaceView.visibility = View.VISIBLE
        binding.ivScreen.visibility = View.GONE
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvStreamStatus.visibility = View.VISIBLE
        binding.tvStreamStatus.text = "正在启动高清投屏..."

        val manager = scrcpyManager ?: return
        val surface = binding.surfaceView.holder.surface
        if (!surface.isValid) {
            binding.tvStreamStatus.text = "等待显示窗口就绪..."
            return
        }

        var receivedFirstH264Frame = false
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(3500)
            if (!receivedFirstH264Frame && isActive) {
                runOnUiThread {
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = "流式连接超时，正在切换至稳定截屏模式..."
                    startFallbackImageStream()
                }
            }
        }

        manager.startH264Stream(
            surface = surface,
            config = config,
            deviceW = deviceWidth,
            deviceH = deviceHeight,
            onFirstFrame = {
                receivedFirstH264Frame = true
                watchdogJob?.cancel()
                runOnUiThread {
                    binding.pbLoading.visibility = View.GONE
                    binding.tvStreamStatus.visibility = View.GONE
                }
            },
            onError = { errMsg ->
                watchdogJob?.cancel()
                runOnUiThread {
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = errMsg
                    startFallbackImageStream()
                }
            }
        )
    }

    private fun stopStreaming() {
        watchdogJob?.cancel()
        watchdogJob = null
        scrcpyManager?.stopStream()
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * 兼容截图模式 (当硬件解码失败或备用)
     */
    private fun startFallbackImageStream() {
        watchdogJob?.cancel()
        scrcpyManager?.stopStream()
        streamJob?.cancel()

        binding.surfaceView.visibility = View.GONE
        binding.ivScreen.visibility = View.VISIBLE
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvStreamStatus.visibility = View.VISIBLE
        binding.tvStreamStatus.text = "正在获取屏幕画面..."

        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            val manager = scrcpyManager ?: return@launch
            val (targetW, targetH) = manager.calculateTargetSize(deviceWidth, deviceHeight, config.maxSize)
            var isFirst = true
            var consecutiveFailures = 0

            while (isActive) {
                val frame = manager.captureFrame(targetW, targetH)
                if (frame != null) {
                    consecutiveFailures = 0
                    withContext(Dispatchers.Main) {
                        binding.ivScreen.setImageBitmap(frame)
                        if (isFirst) {
                            binding.pbLoading.visibility = View.GONE
                            binding.tvStreamStatus.visibility = View.GONE
                            isFirst = false
                        }
                    }
                } else {
                    consecutiveFailures++
                    if (isFirst && consecutiveFailures > 3) {
                        withContext(Dispatchers.Main) {
                            binding.tvStreamStatus.visibility = View.VISIBLE
                            binding.tvStreamStatus.text = "正在重试获取被控端画面 (请确保设备未处于熄屏锁定)..."
                        }
                    }
                }
                delay(30)
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogScrcpySettingsBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        var selectedSize = config.maxSize
        var selectedBitrate = config.bitRate
        var selectedNavPos = config.navPosition

        // 1. 初始化分辨率下拉
        val resLabels = ScrcpyPresets.RESOLUTION_OPTIONS.map { it.label }
        val resAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, resLabels)
        dialogBinding.actvResolution.setAdapter(resAdapter)
        dialogBinding.actvResolution.setText(ScrcpyPresets.getResolutionLabel(config.maxSize), false)
        dialogBinding.actvResolution.setOnItemClickListener { _, _, position, _ ->
            selectedSize = ScrcpyPresets.RESOLUTION_OPTIONS[position].value
        }

        // 2. 初始化码率下拉
        val bitLabels = ScrcpyPresets.BITRATE_OPTIONS.map { it.label }
        val bitAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, bitLabels)
        dialogBinding.actvBitrate.setAdapter(bitAdapter)
        dialogBinding.actvBitrate.setText(ScrcpyPresets.getBitrateLabel(config.bitRate), false)
        dialogBinding.actvBitrate.setOnItemClickListener { _, _, position, _ ->
            selectedBitrate = ScrcpyPresets.BITRATE_OPTIONS[position].value
        }

        // 3. 初始化导航栏位置下拉
        val navLabels = ScrcpyPresets.NAV_POSITION_OPTIONS.map { it.label }
        val navAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, navLabels)
        dialogBinding.actvNavPosition.setAdapter(navAdapter)
        dialogBinding.actvNavPosition.setText(ScrcpyPresets.getNavPositionLabel(config.navPosition), false)
        dialogBinding.actvNavPosition.setOnItemClickListener { _, _, position, _ ->
            selectedNavPos = ScrcpyPresets.NAV_POSITION_OPTIONS[position].value
        }

        // 4. 初始化开关
        dialogBinding.switchFullscreen.isChecked = config.isFullscreen
        dialogBinding.switchScreenOff.isChecked = config.turnScreenOff

        // 应用按钮
        dialogBinding.btnApplySettings.setOnClickListener {
            config.maxSize = selectedSize
            config.bitRate = selectedBitrate
            config.navPosition = selectedNavPos

            val oldFullscreen = config.isFullscreen
            config.isFullscreen = dialogBinding.switchFullscreen.isChecked
            config.turnScreenOff = dialogBinding.switchScreenOff.isChecked

            saveConfig()
            applyNavPosition(config.navPosition)
            if (oldFullscreen != config.isFullscreen) {
                applyFullscreen(config.isFullscreen)
            }

            dialog.dismiss()
            Toast.makeText(this, "投屏参数已更新，正在重载画面...", Toast.LENGTH_SHORT).show()
            startStreaming()
        }

        dialog.show()
    }

    private fun showQuickMenuDialog() {
        val items = arrayOf("显示底部控制栏", "切换全屏显示", "被控端熄屏/亮屏", "切换投屏模式 (流式/截图)", "投屏设置", "返回主页")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("控制菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        config.navPosition = NavPosition.BOTTOM
                        applyNavPosition(config.navPosition)
                        saveConfig()
                    }
                    1 -> {
                        config.isFullscreen = !config.isFullscreen
                        applyFullscreen(config.isFullscreen)
                        saveConfig()
                    }
                    2 -> toggleDeviceScreen()
                    3 -> {
                        if (binding.surfaceView.visibility == View.VISIBLE) {
                            Toast.makeText(this, "正在切换至兼容截图投屏模式...", Toast.LENGTH_SHORT).show()
                            startFallbackImageStream()
                        } else {
                            Toast.makeText(this, "正在切换至硬件解码流式投屏...", Toast.LENGTH_SHORT).show()
                            startStreaming()
                        }
                    }
                    4 -> showSettingsDialog()
                    5 -> finish()
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
