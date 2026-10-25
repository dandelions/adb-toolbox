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
import com.toolbox.adbhelper.core.scrcpy.AudioPlaybackService
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
    private var userSelectedFallback = false
    private var isActivityResumed = false
    private var isActivityStopped = false
    private var isReconnecting = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var backgroundTimeoutJob: Job? = null
    private var streamRetryCount = 0

    companion object {
        private const val PREFS_NAME = "scrcpy_prefs"
        private const val KEY_MAX_SIZE = "scrcpy_max_size"
        private const val KEY_BITRATE = "scrcpy_bitrate"
        private const val KEY_MAX_FPS = "scrcpy_max_fps"
        private const val KEY_NAV_POS = "scrcpy_nav_pos"
        private const val KEY_FULLSCREEN = "scrcpy_fullscreen"
        private const val KEY_SCREEN_OFF = "scrcpy_screen_off"
        private const val KEY_AUDIO_FORWARD = "scrcpy_audio_forward"
        private const val KEY_AUDIO_SOURCE = "scrcpy_audio_source"
        private const val KEY_AUDIO_QUALITY = "scrcpy_audio_quality"
        private const val KEY_MUTE_DEVICE = "scrcpy_mute_device"
        private const val KEY_BG_AUDIO = "scrcpy_bg_audio"
        private const val KEY_AUDIO_GAIN = "scrcpy_audio_gain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScrcpyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        var client = DeviceManager.adbClient
        if (client == null || !client.isConnected) {
            // 给瞬时状态切换预留短暂的缓冲时间
            for (i in 0 until 3) {
                if (DeviceManager.adbClient?.isConnected == true) {
                    client = DeviceManager.adbClient
                    break
                }
                Thread.sleep(150)
            }
        }

        if (client == null || !client.isConnected) {
            Toast.makeText(this, "ADB 设备未连接或已断开", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadConfig()

        scrcpyManager = ScrcpyManager(this, client).apply {
            onAudioStatus = { msg, isError ->
                runOnUiThread {
                    if (isError) {
                        Toast.makeText(this@ScrcpyActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                    updateAudioDiag(msg, isError)
                }
            }
        }

        AudioPlaybackService.onServiceStopRequested = {
            runOnUiThread {
                scrcpyManager?.stopAudioStream()
                Toast.makeText(this@ScrcpyActivity, "后台音频播放已停止", Toast.LENGTH_SHORT).show()
            }
        }

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
                if (isActivityResumed && surfaceCreated && scrcpyManager?.isStreamingNow() != true) {
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
        config.enableAudio = prefs.getBoolean(KEY_AUDIO_FORWARD, true) // 默认开启声音传输
        config.audioSource = prefs.getInt(KEY_AUDIO_SOURCE, 0) // 默认采用免安装免弹窗的系统原生内录优先
        config.audioQualityMode = prefs.getInt(KEY_AUDIO_QUALITY, 0) // 默认高保真音乐听歌模式
        config.muteDeviceAudio = prefs.getBoolean(KEY_MUTE_DEVICE, false) // 默认不强制被控端静音
        config.backgroundAudio = prefs.getBoolean(KEY_BG_AUDIO, true) // 默认开启后台听歌
        config.audioGain = prefs.getFloat(KEY_AUDIO_GAIN, 1.0f) // 默认 1.0x 正常音量
    }

    private fun saveConfig() {
        prefs.edit()
            .putInt(KEY_MAX_SIZE, config.maxSize)
            .putInt(KEY_BITRATE, config.bitRate)
            .putInt(KEY_MAX_FPS, config.maxFps)
            .putString(KEY_NAV_POS, config.navPosition.name)
            .putBoolean(KEY_FULLSCREEN, config.isFullscreen)
            .putBoolean(KEY_SCREEN_OFF, config.turnScreenOff)
            .putBoolean(KEY_AUDIO_FORWARD, config.enableAudio)
            .putInt(KEY_AUDIO_SOURCE, config.audioSource)
            .putInt(KEY_AUDIO_QUALITY, config.audioQualityMode)
            .putBoolean(KEY_MUTE_DEVICE, config.muteDeviceAudio)
            .putBoolean(KEY_BG_AUDIO, config.backgroundAudio)
            .putFloat(KEY_AUDIO_GAIN, config.audioGain)
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
        binding.btnQuickAudio.setOnClickListener {
            showAudioSourceQuickDialog()
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

    private fun getMappedDeviceCoords(touchX: Float, touchY: Float, view: View, targetW: Int, targetH: Int): Pair<Int, Int>? {
        if (targetW <= 0 || targetH <= 0) return null

        val displayView: View = if (binding.surfaceView.visibility == View.VISIBLE && binding.surfaceView.width > 0) {
            binding.surfaceView
        } else if (binding.ivScreen.visibility == View.VISIBLE && binding.ivScreen.width > 0) {
            binding.ivScreen
        } else {
            binding.flScreenContainer
        }

        val localX: Float
        val localY: Float
        if (view == displayView) {
            localX = touchX
            localY = touchY
        } else {
            // view 为 flScreenContainer，displayView 为其居中子 View
            localX = touchX - displayView.left
            localY = touchY - displayView.top
        }

        val dw = displayView.width.toFloat()
        val dh = displayView.height.toFloat()
        if (dw <= 0f || dh <= 0f) return null

        val normX = (localX / dw).coerceIn(0f, 1f)
        val normY = (localY / dh).coerceIn(0f, 1f)

        val targetX = (normX * (targetW - 1)).toInt().coerceIn(0, targetW - 1)
        val targetY = (normY * (targetH - 1)).toInt().coerceIn(0, targetH - 1)
        return Pair(targetX, targetY)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchControl() {
        val touchListener = View.OnTouchListener { v, event ->
            val manager = scrcpyManager ?: return@OnTouchListener false
            val (curDevW, curDevH) = getCurrentDeviceDimensions()
            val useControl = manager.hasControl()

            // 极为关键：若是 scrcpy 原生控制流，目标坐标系与尺寸必须是服务端的真实视频编码尺寸 (serverVideoWidth x serverVideoHeight)
            // 绝不能传物理分辨率，否则服务端 Device.getPhysicalPoint 会因为 Size 不匹配直接抛出 Ignore touch event 并丢弃！
            val targetW = if (useControl) {
                if (manager.serverVideoWidth > 0) manager.serverVideoWidth else videoWidth
            } else {
                curDevW
            }
            val targetH = if (useControl) {
                if (manager.serverVideoHeight > 0) manager.serverVideoHeight else videoHeight
            } else {
                curDevH
            }
            if (targetW <= 0 || targetH <= 0) return@OnTouchListener false

            // 1. 优先通过原生 scrcpy 36 字节极速控制流注入（微秒级实时响应，带时间戳与多点触控支持，彻底解决滑动不跟手）
            if (useControl) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val pId = event.getPointerId(0).toLong()
                        val coords = getMappedDeviceCoords(event.getX(0), event.getY(0), v, targetW, targetH) ?: return@OnTouchListener false
                        manager.sendTouchEvent(
                            action = MotionEvent.ACTION_DOWN,
                            x = coords.first,
                            y = coords.second,
                            devW = targetW,
                            devH = targetH,
                            pointerId = pId,
                            buttons = if (event.buttonState == 0) 1 else event.buttonState,
                            timeMs = event.eventTime
                        )
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        val actionIndex = event.actionIndex
                        val pId = event.getPointerId(actionIndex).toLong()
                        val coords = getMappedDeviceCoords(event.getX(actionIndex), event.getY(actionIndex), v, targetW, targetH) ?: return@OnTouchListener false
                        manager.sendTouchEvent(
                            action = MotionEvent.ACTION_DOWN,
                            x = coords.first,
                            y = coords.second,
                            devW = targetW,
                            devH = targetH,
                            pointerId = pId,
                            buttons = if (event.buttonState == 0) 1 else event.buttonState,
                            timeMs = event.eventTime
                        )
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        for (i in 0 until event.pointerCount) {
                            val pId = event.getPointerId(i).toLong()
                            val coords = getMappedDeviceCoords(event.getX(i), event.getY(i), v, targetW, targetH) ?: continue
                            manager.sendTouchEvent(
                                action = MotionEvent.ACTION_MOVE,
                                x = coords.first,
                                y = coords.second,
                                devW = targetW,
                                devH = targetH,
                                pointerId = pId,
                                buttons = if (event.buttonState == 0) 1 else event.buttonState,
                                timeMs = event.eventTime
                            )
                        }
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val actionIndex = event.actionIndex
                        val pId = event.getPointerId(actionIndex).toLong()
                        val coords = getMappedDeviceCoords(event.getX(actionIndex), event.getY(actionIndex), v, targetW, targetH) ?: return@OnTouchListener false
                        manager.sendTouchEvent(
                            action = MotionEvent.ACTION_UP,
                            x = coords.first,
                            y = coords.second,
                            devW = targetW,
                            devH = targetH,
                            pointerId = pId,
                            buttons = 0,
                            timeMs = event.eventTime
                        )
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val pId = event.getPointerId(0).toLong()
                        val coords = getMappedDeviceCoords(event.getX(0), event.getY(0), v, targetW, targetH) ?: return@OnTouchListener false
                        manager.sendTouchEvent(
                            action = MotionEvent.ACTION_UP,
                            x = coords.first,
                            y = coords.second,
                            devW = targetW,
                            devH = targetH,
                            pointerId = pId,
                            buttons = 0,
                            timeMs = event.eventTime
                        )
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        for (i in 0 until event.pointerCount) {
                            val pId = event.getPointerId(i).toLong()
                            val coords = getMappedDeviceCoords(event.getX(i), event.getY(i), v, targetW, targetH) ?: continue
                            manager.sendTouchEvent(
                                action = MotionEvent.ACTION_CANCEL,
                                x = coords.first,
                                y = coords.second,
                                devW = targetW,
                                devH = targetH,
                                pointerId = pId,
                                buttons = 0,
                                timeMs = event.eventTime
                            )
                        }
                        true
                    }
                    else -> false
                }
            } else {
                // 2. 截图兼容模式下的降级模拟
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.x
                        touchDownY = event.y
                        touchDownTime = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchDownTime
                        val dx = event.x - touchDownX
                        val dy = event.y - touchDownY

                        val downCoords = getMappedDeviceCoords(touchDownX, touchDownY, v, targetW, targetH) ?: return@OnTouchListener false
                        val upCoords = getMappedDeviceCoords(event.x, event.y, v, targetW, targetH) ?: return@OnTouchListener false

                        lifecycleScope.launch(Dispatchers.IO) {
                            val distance = Math.hypot(dx.toDouble(), dy.toDouble())
                            if (distance < 25) {
                                if (duration > 500) {
                                    manager.sendSwipe(downCoords.first, downCoords.second, downCoords.first, downCoords.second, duration.toInt().coerceAtMost(1000))
                                } else {
                                    manager.sendTap(downCoords.first, downCoords.second)
                                }
                            } else {
                                val fastDuration = minOf(duration.toInt(), 160).coerceAtLeast(60)
                                manager.sendSwipe(downCoords.first, downCoords.second, upCoords.first, upCoords.second, fastDuration)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        binding.flScreenContainer.setOnTouchListener(touchListener)
        binding.surfaceView.setOnTouchListener(touchListener)
        binding.ivScreen.setOnTouchListener(touchListener)
    }

    override fun onStart() {
        super.onStart()
        isActivityStopped = false
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        backgroundTimeoutJob?.cancel()
        backgroundTimeoutJob = null

        // 从后台切回前台时，关闭常驻通知，主动检查连接并恢复推流
        AudioPlaybackService.stop(this)
        checkAndResumeStream()
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onStop() {
        super.onStop()
        isActivityStopped = true
        backgroundTimeoutJob?.cancel()
        backgroundTimeoutJob = null

        val isAudioActive = scrcpyManager?.isAudioPlaying() == true
        if (config.enableAudio && config.backgroundAudio && isAudioActive) {
            // 用户开启了后台听歌：仅暂停画面视频推流以极大省电省流，持续保持被控端音频传输与播放
            scrcpyManager?.stopVideoStream()
            streamJob?.cancel()
            watchdogJob?.cancel()
            AudioPlaybackService.start(this)
        } else {
            // 未开启后台音频或音频未激活，保持原有的超时保护策略
            backgroundTimeoutJob = lifecycleScope.launch {
                delay(60000)
                if (isActivityStopped) {
                    stopStreaming()
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceCreated = true
        // 画布创建就绪，若当前在前台则启动或恢复推流
        if (isActivityResumed && !isActivityStopped) {
            checkAndResumeStream()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceCreated = false
        // Surface 被销毁时通知 ScrcpyManager 解绑 Surface，避免向无效画布写入
        scrcpyManager?.updateSurface(null)
    }

    /**
     * 检查 ADB 连接与 Surface 状态，自动恢复画面推流
     */
    private fun checkAndResumeStream() {
        if (isActivityStopped) return

        // 1. 检查 ADB 连接状态
        val client = DeviceManager.adbClient
        if (client == null || !client.isConnected) {
            triggerAutoReconnect(force = true)
            return
        }

        // 2. 连接有效，同步更新 manager 的 client
        scrcpyManager?.updateAdbClient(client)

        // 3. 主动唤醒屏幕
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scrcpyManager?.turnScreenOn()
            } catch (_: Exception) {}
        }

        // 4. 按照用户当前选择的模式拉起画面
        if (userSelectedFallback) {
            if (isFallbackMode && streamJob?.isActive == true) return
            startFallbackImageStream()
        } else {
            binding.surfaceView.visibility = View.VISIBLE
            binding.ivScreen.visibility = View.GONE

            val surface = binding.surfaceView.holder.surface
            if (surfaceCreated && surface != null && surface.isValid) {
                // 如果当前已经在推流中，尝试直接动态无缝更新输出 Surface
                if (scrcpyManager?.isStreamingNow() == true) {
                    val updated = scrcpyManager?.updateSurface(surface) ?: false
                    if (updated) {
                        binding.pbLoading.visibility = View.GONE
                        binding.tvStreamStatus.visibility = View.GONE
                        updateDiagBar("#4CAF50", "模式: 极速硬件流(H264) | ${videoWidth}x${videoHeight} ${config.maxFps}fps")
                        return
                    }
                }
                startStreaming()
            } else {
                binding.pbLoading.visibility = View.VISIBLE
                binding.tvStreamStatus.visibility = View.VISIBLE
                binding.tvStreamStatus.text = "等待显示画面画布就绪..."
                updateDiagBar("#FFB300", "正在等待显示画布就绪...")
            }
        }
    }

    /**
     * ADB 连接异常或后台断开时，自动发起快速重连
     */
    private fun triggerAutoReconnect(force: Boolean = true) {
        if (isReconnecting || isActivityStopped) return
        isReconnecting = true

        stopStreaming()
        binding.surfaceView.visibility = View.VISIBLE
        binding.ivScreen.visibility = View.GONE
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvStreamStatus.visibility = View.VISIBLE

        reconnectAttempts++
        val attemptMsg = if (reconnectAttempts <= 1) "连接已断开，正在自动重新连接..." else "正在尝试重新连接被控设备 (第 ${reconnectAttempts} 次)..."
        binding.tvStreamStatus.text = attemptMsg
        updateDiagBar("#FFB300", "连接断开 | 正在自动重连...")

        // 点击文字立即重试
        binding.tvStreamStatus.setOnClickListener {
            if (isReconnecting) return@setOnClickListener
            reconnectAttempts = 0
            triggerAutoReconnect(force = true)
        }

        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            var reconnected = false
            for (i in 1..4) {
                if (isActivityStopped || !isActive) break
                val success = DeviceManager.tryReconnect(this@ScrcpyActivity, force = (force || i > 1))
                if (success) {
                    reconnected = true
                    break
                }
                delay(1200)
            }

            withContext(Dispatchers.Main) {
                isReconnecting = false
                if (isActivityStopped) return@withContext

                val client = DeviceManager.adbClient
                if (reconnected && client != null && client.isConnected) {
                    scrcpyManager?.updateAdbClient(client)
                    reconnectAttempts = 0
                    Toast.makeText(this@ScrcpyActivity, "设备重连成功，正在恢复投屏画面...", Toast.LENGTH_SHORT).show()
                    checkAndResumeStream()
                } else {
                    binding.pbLoading.visibility = View.GONE
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = "自动重连失败，设备可能已离线或网络断开。\n【点击此处重新尝试连接】"
                    updateDiagBar("#F44336", "重连失败 | 请检查设备网络")
                    binding.tvStreamStatus.setOnClickListener {
                        reconnectAttempts = 0
                        triggerAutoReconnect(force = true)
                    }
                }
            }
        }
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

    private var currentVideoDiag: String = "正在连接..."
    private var currentAudioDiag: String = "音频准备中"
    private var currentDiagColor: String = "#FFB300"

    private fun updateCombinedDiag() {
        val combined = if (config.enableAudio) {
            "$currentVideoDiag | $currentAudioDiag"
        } else {
            "$currentVideoDiag | 音频:已关"
        }
        try {
            binding.vStatusIndicator.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(currentDiagColor))
            binding.tvDiagText.text = combined
        } catch (_: Exception) {}
    }

    private fun updateDiagBar(indicatorColor: String, text: String) {
        currentDiagColor = indicatorColor
        currentVideoDiag = text
        updateCombinedDiag()
    }

    private fun updateAudioDiag(text: String, isError: Boolean) {
        currentAudioDiag = text
        if (isError) {
            currentDiagColor = "#F44336"
        }
        updateCombinedDiag()
    }

    private fun toggleStreamMode() {
        if (isFallbackMode) {
            userSelectedFallback = false
            Toast.makeText(this, "正在切换至极速硬件推流(H264)...", Toast.LENGTH_SHORT).show()
            startStreaming()
        } else {
            userSelectedFallback = true
            Toast.makeText(this, "正在切换至稳定截屏兼容模式...", Toast.LENGTH_SHORT).show()
            startFallbackImageStream()
        }
    }

    private fun startStreaming() {
        if (isActivityStopped) return
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
            if (!receivedFirstH264Frame && isActive && isActivityResumed && !isActivityStopped) {
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
                streamRetryCount = 0
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

                    // 正常远程后平稳拉起音频流（错峰启动，杜绝底层并发争抢，且弹窗在主控端立即可见可点）
                    if (config.enableAudio && scrcpyManager?.isAudioPlaying() != true) {
                        scrcpyManager?.startAudioStream(
                            config.audioSource,
                            config.audioQualityMode,
                            config.audioGain,
                            config.muteDeviceAudio
                        )
                    }
                }
            },
            onError = { errMsg ->
                watchdogJob?.cancel()
                runOnUiThread {
                    if (isActivityStopped || !isActivityResumed) {
                        return@runOnUiThread
                    }

                    val client = DeviceManager.adbClient
                    if (client == null || !client.isConnected) {
                        updateDiagBar("#F44336", "连接已断开: $errMsg")
                        triggerAutoReconnect(force = true)
                        return@runOnUiThread
                    }

                    streamRetryCount++
                    if (streamRetryCount > 2) {
                        streamRetryCount = 0
                        updateDiagBar("#FFB300", "硬件流异常，已自动切至稳定截屏模式")
                        startFallbackImageStream()
                        return@runOnUiThread
                    }

                    updateDiagBar("#FFB300", "流传输中断，正在自动恢复...")
                    binding.tvStreamStatus.visibility = View.VISIBLE
                    binding.tvStreamStatus.text = "投屏流异常中断，正在自动恢复..."

                    lifecycleScope.launch {
                        delay(800)
                        if (isActivityResumed && !isActivityStopped) {
                            if (DeviceManager.adbClient?.isConnected == true) {
                                startStreaming()
                            } else {
                                triggerAutoReconnect(force = true)
                            }
                        }
                    }
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
        scrcpyManager?.stopVideoStream()
        if (config.enableAudio && scrcpyManager?.isAudioPlaying() != true) {
            scrcpyManager?.startAudioStream(
                audioSource = config.audioSource,
                qualityMode = config.audioQualityMode,
                gain = config.audioGain,
                muteDevice = config.muteDeviceAudio
            )
        }
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

        // 5. 初始化音频源下拉
        var selectedAudioSource = config.audioSource
        val audioLabels = ScrcpyPresets.AUDIO_SOURCE_OPTIONS.map { it.label }
        val audioAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, audioLabels)
        dialogBinding.actvAudioSource.setAdapter(audioAdapter)
        dialogBinding.actvAudioSource.setText(ScrcpyPresets.getAudioSourceLabel(config.audioSource), false)
        dialogBinding.actvAudioSource.setOnItemClickListener { _, _, position, _ ->
            selectedAudioSource = ScrcpyPresets.AUDIO_SOURCE_OPTIONS[position].value
        }

        // 6. 初始化音质品质模式下拉
        var selectedQualityMode = config.audioQualityMode
        val qualityLabels = ScrcpyPresets.AUDIO_QUALITY_OPTIONS.map { it.label }
        val qualityAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, qualityLabels)
        dialogBinding.actvAudioQuality.setAdapter(qualityAdapter)
        dialogBinding.actvAudioQuality.setText(ScrcpyPresets.getAudioQualityLabel(config.audioQualityMode), false)
        dialogBinding.actvAudioQuality.setOnItemClickListener { _, _, position, _ ->
            selectedQualityMode = ScrcpyPresets.AUDIO_QUALITY_OPTIONS[position].value
        }

        // 7. 初始化远端数字音量增益下拉
        var selectedGain = config.audioGain
        val gainLabels = ScrcpyPresets.AUDIO_GAIN_OPTIONS.map { it.label }
        val gainAdapter = ArrayAdapter(this, R.layout.item_dropdown_menu, gainLabels)
        dialogBinding.actvAudioGain.setAdapter(gainAdapter)
        dialogBinding.actvAudioGain.setText(ScrcpyPresets.getAudioGainLabel(config.audioGain), false)
        dialogBinding.actvAudioGain.setOnItemClickListener { _, _, position, _ ->
            selectedGain = ScrcpyPresets.AUDIO_GAIN_OPTIONS[position].value
        }

        // 8. 初始化开关
        dialogBinding.switchFullscreen.isChecked = config.isFullscreen
        dialogBinding.switchScreenOff.isChecked = config.turnScreenOff
        dialogBinding.switchAudioForward.isChecked = config.enableAudio
        dialogBinding.switchMuteDevice.isChecked = config.muteDeviceAudio
        dialogBinding.switchBackgroundAudio.isChecked = config.backgroundAudio

        // 应用按钮
        dialogBinding.btnApplySettings.setOnClickListener {
            val needRestartVideo = (config.maxSize != selectedSize ||
                    config.bitRate != selectedBitrate ||
                    config.maxFps != selectedFps)

            config.maxSize = selectedSize
            config.bitRate = selectedBitrate
            config.maxFps = selectedFps
            config.navPosition = selectedNavPos

            val oldFullscreen = config.isFullscreen
            config.isFullscreen = dialogBinding.switchFullscreen.isChecked
            config.turnScreenOff = dialogBinding.switchScreenOff.isChecked

            val oldAudio = config.enableAudio
            val oldSource = config.audioSource
            val oldQuality = config.audioQualityMode
            val oldGain = config.audioGain

            config.enableAudio = dialogBinding.switchAudioForward.isChecked
            config.audioSource = selectedAudioSource
            config.audioQualityMode = selectedQualityMode
            config.muteDeviceAudio = dialogBinding.switchMuteDevice.isChecked
            config.backgroundAudio = dialogBinding.switchBackgroundAudio.isChecked
            config.audioGain = selectedGain

            saveConfig()
            applyNavPosition(config.navPosition)
            if (oldFullscreen != config.isFullscreen) {
                applyFullscreen(config.isFullscreen)
            }

            // 确保同步被控端外放静音设置（强制下发期望状态，杜绝状态不一致）
            scrcpyManager?.setDeviceMuted(config.muteDeviceAudio)

            // 同步数字增益
            if (oldGain != config.audioGain) {
                scrcpyManager?.setAudioGain(config.audioGain)
            }

            // 若音频开关、输入源或缓冲区品质模式发生变化，重构音频传输
            if (oldAudio != config.enableAudio || oldSource != config.audioSource || oldQuality != config.audioQualityMode) {
                scrcpyManager?.setAudioEnabled(
                    enabled = config.enableAudio,
                    audioSource = config.audioSource,
                    qualityMode = config.audioQualityMode,
                    gain = config.audioGain,
                    muteDevice = config.muteDeviceAudio
                )
            }

            dialog.dismiss()

            if (needRestartVideo) {
                Toast.makeText(this, "投屏参数已更新，正在重载画面...", Toast.LENGTH_SHORT).show()
                startStreaming()
            } else {
                val audioMsg = if (config.enableAudio) "声音传输已开启" else "声音传输已关闭"
                val muteTip = if (config.muteDeviceAudio) "，被控端物理外放已静音" else ""
                Toast.makeText(this, "设置已生效 ($audioMsg$muteTip)", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showQuickMenuDialog() {
        val muteAction = if (config.muteDeviceAudio) "恢复被控端外放声音" else "静音被控端物理外放"
        val items = arrayOf("显示底部控制栏", "切换全屏显示", "被控端熄屏/亮屏", muteAction, "切换投屏模式 (流式/截图)", "投屏设置", "返回主页")
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
                        config.muteDeviceAudio = !config.muteDeviceAudio
                        saveConfig()
                        scrcpyManager?.setDeviceMuted(config.muteDeviceAudio)
                        val tip = if (config.muteDeviceAudio) "已将对端物理外放设为微音量静音 (保持内录信号)" else "已恢复对端物理外放声音"
                        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        if (binding.surfaceView.visibility == View.VISIBLE) {
                            userSelectedFallback = true
                            Toast.makeText(this, "正在切换至兼容截图投屏模式...", Toast.LENGTH_SHORT).show()
                            startFallbackImageStream()
                        } else {
                            userSelectedFallback = false
                            Toast.makeText(this, "正在切换至硬件解码流式投屏...", Toast.LENGTH_SHORT).show()
                            startStreaming()
                        }
                    }
                    5 -> showSettingsDialog()
                    6 -> finish()
                }
            }
            .show()
    }

    private fun showAudioSourceQuickDialog() {
        val options = ScrcpyPresets.AUDIO_SOURCE_OPTIONS
        val labels = options.map { it.label }.toTypedArray()
        val currentIndex = options.indexOfFirst { it.value == config.audioSource }.coerceAtLeast(0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("音频传输设置")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = options[which].value
                config.audioSource = selected
                config.enableAudio = true
                saveConfig()
                scrcpyManager?.setAudioEnabled(
                    enabled = true,
                    audioSource = selected,
                    qualityMode = config.audioQualityMode,
                    gain = config.audioGain,
                    muteDevice = config.muteDeviceAudio
                )
                Toast.makeText(this, "已切换为: ${options[which].label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setPositiveButton(if (config.muteDeviceAudio) "恢复对端声音" else "静音对端外放") { _, _ ->
                config.muteDeviceAudio = !config.muteDeviceAudio
                saveConfig()
                scrcpyManager?.setDeviceMuted(config.muteDeviceAudio)
                val tip = if (config.muteDeviceAudio) "已将对端物理外放设为微音量静音 (保持内录信号)" else "已恢复对端物理外放声音"
                Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (config.enableAudio) "关闭音频" else "开启音频") { _, _ ->
                config.enableAudio = !config.enableAudio
                saveConfig()
                scrcpyManager?.setAudioEnabled(
                    enabled = config.enableAudio,
                    audioSource = config.audioSource,
                    qualityMode = config.audioQualityMode,
                    gain = config.audioGain,
                    muteDevice = config.muteDeviceAudio
                )
                val msg = if (config.enableAudio) "已开启音频传输" else "已关闭音频传输"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundTimeoutJob?.cancel()
        backgroundTimeoutJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        AudioPlaybackService.stop(this)
        AudioPlaybackService.onServiceStopRequested = null
        if (config.muteDeviceAudio) {
            try {
                scrcpyManager?.setDeviceMuted(false)
            } catch (_: Exception) {}
        }
        stopStreaming()
    }
}
