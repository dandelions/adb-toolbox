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

    private var physicalWidth = 1080
    private var physicalHeight = 2400
    private var videoWidth = 1080
    private var videoHeight = 2400

    /**
     * 根据当前视频画面横竖屏方向，获取被控端当前方向下的真实物理屏幕宽高
     */
    private fun getCurrentDeviceDimensions(): Pair<Int, Int> {
        val isLandscape = videoWidth > videoHeight
        val devW = if (isLandscape) maxOf(physicalWidth, physicalHeight) else minOf(physicalWidth, physicalHeight)
        val devH = if (isLandscape) minOf(physicalWidth, physicalHeight) else maxOf(physicalWidth, physicalHeight)
        return Pair(devW, devH)
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    private var isScreenOff = false
    private var surfaceCreated = false
    private var isFallbackMode = false

    companion object {
        private const val PREFS_NAME = "scrcpy_prefs"
        private const val KEY_MAX_SIZE = "scrcpy_max_size"
        private const val KEY_BITRATE = "scrcpy_bitrate"
        private const val KEY_MAX_FPS = "scrcpy_max_fps"
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

        binding.surfaceView.setZOrderMediaOverlay(true)
        binding.surfaceView.holder.addCallback(this)

        lifecycleScope.launch(Dispatchers.IO) {
            // 主动唤醒屏幕并解锁无密码锁屏，防止对端黑屏/息屏导致捕获全黑
            scrcpyManager?.turnScreenOn()

            val (w, h) = scrcpyManager?.getDeviceResolution() ?: Pair(1080, 2400)
            physicalWidth = w
            physicalHeight = h
            videoWidth = w
            videoHeight = h

            withContext(Dispatchers.Main) {
                if (surfaceCreated && scrcpyManager?.isStreamingNow() != true) {
                    startStreaming()
                }
            }
        }
    }

    private fun loadConfig() {
        config.maxSize = prefs.getInt(KEY_MAX_SIZE, -1)
        config.bitRate = prefs.getInt(KEY_BITRATE, 8_000_000)
        config.maxFps = prefs.getInt(KEY_MAX_FPS, 60)
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
            .putInt(KEY_MAX_FPS, config.maxFps)
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

        // 浮动悬浮球默认隐藏，远程画面保持干净无遮挡
        binding.fabQuickMenu.visibility = View.GONE

        // 诊断栏快捷按钮
        binding.btnQuickSwitchMode.setOnClickListener {
            toggleStreamMode()
        }
        binding.btnQuickWake.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                scrcpyManager?.turnScreenOn()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScrcpyActivity, "已向对端发送唤醒与解锁指令", Toast.LENGTH_SHORT).show()
                }
            }
        }

        applyNavPosition(config.navPosition)
        applyFullscreen(config.isFullscreen)

        // 监听容器尺寸变动（如分屏调整、折叠屏展开/合上、横竖屏旋转），动态等比重绘
        binding.flScreenContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (w > 0 && h > 0 && (w != oldW || h != oldH)) {
                val (curDevW, curDevH) = getCurrentDeviceDimensions()
                val (targetW, targetH) = manager.calculateTargetSize(curDevW, curDevH, config.maxSize, w, h)
                adjustViewAspectRatio(targetW, targetH)
            }
        }
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
            binding.layoutDiagBar.visibility = View.GONE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            binding.toolbar.visibility = View.VISIBLE
            binding.layoutDiagBar.visibility = View.VISIBLE
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
                binding.fabQuickMenu.visibility = View.GONE
            }
        }
        binding.navBarWrapper.layoutParams = wrapperParams
    }

    private fun getMappedDeviceCoords(touchX: Float, touchY: Float, view: View): Pair<Int, Int>? {
        val (curDevW, curDevH) = getCurrentDeviceDimensions()
        if (curDevW <= 0 || curDevH <= 0) return null

        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0 || h <= 0) return null

        val normX = (touchX / w).coerceIn(0f, 1f)
        val normY = (touchY / h).coerceIn(0f, 1f)

        val targetX = (normX * curDevW).toInt().coerceIn(0, curDevW - 1)
        val targetY = (normY * curDevH).toInt().coerceIn(0, curDevH - 1)
        return Pair(targetX, targetY)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchControl() {
        val touchListener = View.OnTouchListener { v, event ->
            val manager = scrcpyManager ?: return@OnTouchListener false

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

                    val downCoords = getMappedDeviceCoords(touchDownX, touchDownY, v) ?: return@OnTouchListener false
                    val upCoords = getMappedDeviceCoords(upX, upY, v) ?: return@OnTouchListener false

                    val targetDownX = downCoords.first
                    val targetDownY = downCoords.second
                    val targetUpX = upCoords.first
                    val targetUpY = upCoords.second

                    lifecycleScope.launch(Dispatchers.IO) {
                        val distance = Math.hypot(dx.toDouble(), dy.toDouble())
                        if (distance < 25) {
                            if (duration > 500) {
                                // 长按：原地按压
                                manager.sendSwipe(targetDownX, targetDownY, targetDownX, targetDownY, duration.toInt().coerceAtMost(1000))
                            } else {
                                manager.sendTap(targetDownX, targetDownY)
                            }
                        } else {
                            // 极速滑动：限制在 60ms ~ 160ms，消除被控机缓慢模拟造成的迟滞
                            val fastDuration = minOf(duration.toInt(), 160).coerceAtLeast(60)
                            manager.sendSwipe(targetDownX, targetDownY, targetUpX, targetUpY, fastDuration)
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
        if (physicalWidth > 0 && physicalHeight > 0 && scrcpyManager?.isStreamingNow() != true) {
            startStreaming()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceCreated = false
        // 关键修复：绝不在 surfaceDestroyed 中 stopStream()！
        // 动态等比适配、进入全屏、分屏调整均会触发 Surface 重建。
        // 流生命周期交由 Activity onStop/onDestroy 管理，杜绝几秒后误杀流卡死！
    }

    private var watchdogJob: Job? = null

    private fun getContainerDimensions(): Pair<Int, Int> {
        val cw = binding.flScreenContainer.width
        val ch = binding.flScreenContainer.height
        if (cw > 0 && ch > 0) return Pair(cw, ch)
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /**
     * 保持原始纵横比 (Aspect Ratio) 动态调整 SurfaceView 与 ImageView 的显示尺寸并居中
     */
    private fun adjustViewAspectRatio(targetW: Int, targetH: Int) {
        val (cw, ch) = getContainerDimensions()
        if (cw <= 0 || ch <= 0 || targetW <= 0 || targetH <= 0) return

        val scale = minOf(cw.toFloat() / targetW, ch.toFloat() / targetH)
        val viewW = (targetW * scale).toInt().coerceAtLeast(16)
        val viewH = (targetH * scale).toInt().coerceAtLeast(16)

        val svParams = binding.surfaceView.layoutParams as FrameLayout.LayoutParams
        if (svParams.width != viewW || svParams.height != viewH) {
            svParams.width = viewW
            svParams.height = viewH
            svParams.gravity = Gravity.CENTER
            binding.surfaceView.layoutParams = svParams
        }

        val ivParams = binding.ivScreen.layoutParams as FrameLayout.LayoutParams
        if (ivParams.width != viewW || ivParams.height != viewH) {
            ivParams.width = viewW
            ivParams.height = viewH
            ivParams.gravity = Gravity.CENTER
            binding.ivScreen.layoutParams = ivParams
        }
    }

    private fun updateDiagBar(indicatorColor: String, text: String) {
        try {
            binding.vStatusIndicator.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(indicatorColor))
            binding.tvDiagText.text = text
        } catch (_: Exception) {}
    }

    private fun toggleStreamMode() {
        if (isFallbackMode) {
            Toast.makeText(this, "正在切换至极速硬件推流(H264)...", Toast.LENGTH_SHORT).show()
            startStreaming()
        } else {
            Toast.makeText(this, "正在切换至稳定截屏兼容模式...", Toast.LENGTH_SHORT).show()
            startFallbackImageStream()
        }
    }

    private fun startStreaming() {
        isFallbackMode = false
        watchdogJob?.cancel()
        watchdogJob = null
        scrcpyManager?.stopStream()
        streamJob?.cancel()
        streamJob = null

        binding.surfaceView.visibility = View.VISIBLE
        binding.ivScreen.visibility = View.GONE
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvStreamStatus.visibility = View.VISIBLE
        binding.tvStreamStatus.text = "正在启动高清硬件投屏..."
        updateDiagBar("#FFB300", "模式: 极速硬件流(H264) | 正在建立连接...")

        val manager = scrcpyManager ?: return
        val surface = binding.surfaceView.holder.surface
        if (!surface.isValid) {
            binding.tvStreamStatus.text = "等待显示窗口就绪..."
            updateDiagBar("#FFB300", "模式: 极速硬件流 | 等待显示画布就绪...")
            return
        }

        val (cw, ch) = getContainerDimensions()
        val (curDevW, curDevH) = getCurrentDeviceDimensions()
        val (targetW, targetH) = manager.calculateTargetSize(curDevW, curDevH, config.maxSize, cw, ch)
        adjustViewAspectRatio(targetW, targetH)

        var receivedFirstH264Frame = false
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(8000)
            if (!receivedFirstH264Frame && isActive) {
                runOnUiThread {
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = "硬件流建立超时，正在切换至稳定截屏模式..."
                    updateDiagBar("#FFB300", "硬件流超时，已自动切至稳定截屏模式")
                    startFallbackImageStream()
                }
            }
        }

        manager.startH264Stream(
            surface = surface,
            config = config,
            deviceW = curDevW,
            deviceH = curDevH,
            localW = cw,
            localH = ch,
            onFirstFrame = { w, h ->
                receivedFirstH264Frame = true
                watchdogJob?.cancel()
                runOnUiThread {
                    if (w > 0 && h > 0) {
                        videoWidth = w
                        videoHeight = h
                        if (config.maxSize <= 0) {
                            physicalWidth = w
                            physicalHeight = h
                        }
                        adjustViewAspectRatio(w, h)
                    }
                    binding.pbLoading.visibility = View.GONE
                    binding.tvStreamStatus.visibility = View.GONE
                    updateDiagBar("#4CAF50", "模式: 极速硬件流(H264) | ${w}x${h} ${config.maxFps}fps")

                    if (config.turnScreenOff && !isScreenOff) {
                        isScreenOff = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            scrcpyManager?.turnScreenOff()
                        }
                    }
                }
            },
            onError = { errMsg ->
                watchdogJob?.cancel()
                runOnUiThread {
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = errMsg
                    updateDiagBar("#F44336", "硬件流异常: $errMsg")
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
        isFallbackMode = true
        watchdogJob?.cancel()
        watchdogJob = null
        scrcpyManager?.stopStream()
        streamJob?.cancel()
        streamJob = null

        binding.surfaceView.visibility = View.GONE
        binding.ivScreen.visibility = View.VISIBLE
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvStreamStatus.visibility = View.VISIBLE
        val manager = scrcpyManager
        val methodName = manager?.getPreferredCaptureMethodName() ?: "自动探测"
        binding.tvStreamStatus.text = "正在以稳定截屏模式获取屏幕画面..."
        updateDiagBar("#FFB300", "模式: 稳定截屏($methodName) | 正在连接...")

        // 支持点击状态文字手动唤醒被控端并重试
        binding.tvStreamStatus.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                manager?.turnScreenOn()
            }
            Toast.makeText(this, "正在重新拉取画面并唤醒屏幕...", Toast.LENGTH_SHORT).show()
        }

        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            val mgr = scrcpyManager ?: return@launch
            val (cw, ch) = getContainerDimensions()
            val (curDevW, curDevH) = getCurrentDeviceDimensions()
            val (targetW, targetH) = mgr.calculateTargetSize(curDevW, curDevH, config.maxSize, cw, ch)
            withContext(Dispatchers.Main) {
                adjustViewAspectRatio(targetW, targetH)
            }

            var isFirst = true
            var consecutiveFailures = 0
            var frameCount = 0L
            var lastFpsCalcTime = System.currentTimeMillis()
            var currentFps = 0

            while (isActive) {
                val frame = mgr.captureFrame(targetW, targetH)
                if (frame != null) {
                    consecutiveFailures = 0
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsCalcTime >= 1000) {
                        currentFps = ((frameCount * 1000) / maxOf(1, now - lastFpsCalcTime)).toInt()
                        frameCount = 0
                        lastFpsCalcTime = now
                    }

                    withContext(Dispatchers.Main) {
                        if (videoWidth != frame.width || videoHeight != frame.height) {
                            videoWidth = frame.width
                            videoHeight = frame.height
                            if (config.maxSize <= 0) {
                                physicalWidth = frame.width
                                physicalHeight = frame.height
                            }
                            adjustViewAspectRatio(videoWidth, videoHeight)
                        }
                        binding.ivScreen.setImageBitmap(frame)
                        updateDiagBar(
                            indicatorColor = "#4CAF50",
                            text = "模式: 截屏(${mgr.getPreferredCaptureMethodName()}) | 帧率: ${currentFps} fps"
                        )
                        if (isFirst) {
                            binding.pbLoading.visibility = View.GONE
                            binding.tvStreamStatus.visibility = View.GONE
                            isFirst = false

                            if (config.turnScreenOff && !isScreenOff) {
                                isScreenOff = true
                                lifecycleScope.launch(Dispatchers.IO) {
                                    mgr.turnScreenOff()
                                }
                            }
                        }
                    }
                } else {
                    consecutiveFailures++
                    val lastErr = mgr.getLastCaptureError()
                    withContext(Dispatchers.Main) {
                        val msg = if (lastErr.isNotBlank()) "正在获取画面: $lastErr" else "正在重试获取被控端画面 (请确保设备未锁定)..."
                        binding.tvStreamStatus.visibility = View.VISIBLE
                        binding.tvStreamStatus.text = msg
                        updateDiagBar(
                            indicatorColor = "#FFB300",
                            text = "截屏重试中($consecutiveFailures): ${lastErr.take(24)}"
                        )
                    }

                    // 遇到连续失败时主动点亮并解锁
                    if (consecutiveFailures % 3 == 0) {
                        mgr.turnScreenOn()
                    }

                    // 连续多次失败自动轮换截屏模式
                    if (consecutiveFailures in 4..6) {
                        val cur = mgr.getPreferredCaptureMethod()
                        val next = when (cur) {
                            1 -> 2
                            2 -> 3
                            3 -> 1
                            else -> 3
                        }
                        mgr.setPreferredCaptureMethod(next)
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
        var selectedFps = config.maxFps
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

        // 3. 初始化帧率下拉
        val fpsLabels = ScrcpyPresets.FPS_OPTIONS.map { it.label }
        val fpsAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, fpsLabels)
        dialogBinding.actvFps.setAdapter(fpsAdapter)
        dialogBinding.actvFps.setText(ScrcpyPresets.getFpsLabel(config.maxFps), false)
        dialogBinding.actvFps.setOnItemClickListener { _, _, position, _ ->
            selectedFps = ScrcpyPresets.FPS_OPTIONS[position].value
        }

        // 4. 初始化导航栏位置下拉
        val navLabels = ScrcpyPresets.NAV_POSITION_OPTIONS.map { it.label }
        val navAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, navLabels)
        dialogBinding.actvNavPosition.setAdapter(navAdapter)
        dialogBinding.actvNavPosition.setText(ScrcpyPresets.getNavPositionLabel(config.navPosition), false)
        dialogBinding.actvNavPosition.setOnItemClickListener { _, _, position, _ ->
            selectedNavPos = ScrcpyPresets.NAV_POSITION_OPTIONS[position].value
        }

        // 5. 初始化开关
        dialogBinding.switchFullscreen.isChecked = config.isFullscreen
        dialogBinding.switchScreenOff.isChecked = config.turnScreenOff

        // 应用按钮
        dialogBinding.btnApplySettings.setOnClickListener {
            config.maxSize = selectedSize
            config.bitRate = selectedBitrate
            config.maxFps = selectedFps
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
