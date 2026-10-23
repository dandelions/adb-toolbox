package com.toolbox.adbhelper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.*
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
                if (surfaceCreated) {
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
        startStreaming()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceCreated = false
        stopStreaming()
    }

    private fun startStreaming() {
        stopStreaming()
        binding.surfaceView.visibility = View.VISIBLE
        binding.ivScreen.visibility = View.GONE
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvStreamStatus.visibility = View.GONE

        val manager = scrcpyManager ?: return
        val surface = binding.surfaceView.holder.surface
        if (!surface.isValid) {
            return
        }

        manager.startH264Stream(
            surface = surface,
            config = config,
            deviceW = deviceWidth,
            deviceH = deviceHeight,
            onError = { errMsg ->
                runOnUiThread {
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = errMsg
                    startFallbackImageStream()
                }
            }
        )

        lifecycleScope.launch {
            delay(1200)
            binding.pbLoading.visibility = View.GONE
        }
    }

    private fun stopStreaming() {
        scrcpyManager?.stopStream()
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * 兼容截图模式 (当硬件解码失败或备用)
     */
    private fun startFallbackImageStream() {
        binding.surfaceView.visibility = View.GONE
        binding.ivScreen.visibility = View.VISIBLE
        binding.pbLoading.visibility = View.VISIBLE

        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            val manager = scrcpyManager ?: return@launch
            val (targetW, targetH) = manager.calculateTargetSize(deviceWidth, deviceHeight, config.maxSize)

            withContext(Dispatchers.Main) {
                binding.pbLoading.visibility = View.GONE
            }

            while (isActive) {
                val frame = manager.captureFrame(targetW, targetH)
                if (frame != null) {
                    withContext(Dispatchers.Main) {
                        binding.ivScreen.setImageBitmap(frame)
                    }
                }
                delay(150)
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogScrcpySettingsBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        // 1. 初始化分辨率
        when (config.maxSize) {
            0 -> dialogBinding.btnResNative.isChecked = true
            1920 -> dialogBinding.btnRes1080.isChecked = true
            1280 -> dialogBinding.btnRes720.isChecked = true
            854 -> dialogBinding.btnRes480.isChecked = true
            else -> dialogBinding.btnRes720.isChecked = true
        }

        // 2. 初始化码率
        when (config.bitRate) {
            2_000_000 -> dialogBinding.btnBitrate2M.isChecked = true
            4_000_000 -> dialogBinding.btnBitrate4M.isChecked = true
            8_000_000 -> dialogBinding.btnBitrate8M.isChecked = true
            16_000_000 -> dialogBinding.btnBitrate16M.isChecked = true
            else -> dialogBinding.btnBitrate4M.isChecked = true
        }

        // 3. 初始化导航栏位置
        when (config.navPosition) {
            NavPosition.BOTTOM -> dialogBinding.btnNavBottom.isChecked = true
            NavPosition.TOP -> dialogBinding.btnNavTop.isChecked = true
            NavPosition.LEFT -> dialogBinding.btnNavLeft.isChecked = true
            NavPosition.RIGHT -> dialogBinding.btnNavRight.isChecked = true
            NavPosition.FLOATING, NavPosition.HIDE -> dialogBinding.btnNavHide.isChecked = true
        }

        // 4. 初始化开关
        dialogBinding.switchFullscreen.isChecked = config.isFullscreen
        dialogBinding.switchScreenOff.isChecked = config.turnScreenOff

        // 应用按钮
        dialogBinding.btnApplySettings.setOnClickListener {
            // 解析分辨率
            config.maxSize = when (dialogBinding.toggleResolution.checkedButtonId) {
                R.id.btnResNative -> 0
                R.id.btnRes1080 -> 1920
                R.id.btnRes720 -> 1280
                R.id.btnRes480 -> 854
                else -> 1280
            }

            // 解析码率
            config.bitRate = when (dialogBinding.toggleBitrate.checkedButtonId) {
                R.id.btnBitrate2M -> 2_000_000
                R.id.btnBitrate4M -> 4_000_000
                R.id.btnBitrate8M -> 8_000_000
                R.id.btnBitrate16M -> 16_000_000
                else -> 4_000_000
            }

            // 解析导航栏位置
            config.navPosition = when (dialogBinding.toggleNavPosition.checkedButtonId) {
                R.id.btnNavBottom -> NavPosition.BOTTOM
                R.id.btnNavTop -> NavPosition.TOP
                R.id.btnNavLeft -> NavPosition.LEFT
                R.id.btnNavRight -> NavPosition.RIGHT
                R.id.btnNavHide -> NavPosition.HIDE
                else -> NavPosition.BOTTOM
            }

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
        val items = arrayOf("显示底部控制栏", "切换全屏显示", "被控端熄屏/亮屏", "投屏设置", "返回主页")
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
                    3 -> showSettingsDialog()
                    4 -> finish()
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
