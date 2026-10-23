package com.toolbox.adbhelper.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.scrcpy.ScrcpyManager
import com.toolbox.adbhelper.databinding.ActivityScrcpyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScrcpyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScrcpyBinding
    private var scrcpyManager: ScrcpyManager? = null
    private var streamJob: Job? = null

    private var deviceWidth = 1080
    private var deviceHeight = 2400

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

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

        scrcpyManager = ScrcpyManager(this, client)
        initViews()
        initTouchControl()
        startScreenStream()
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        val manager = scrcpyManager ?: return
        binding.btnBack.setOnClickListener { lifecycleScope.launch { manager.sendBack() } }
        binding.btnHome.setOnClickListener { lifecycleScope.launch { manager.sendHome() } }
        binding.btnRecent.setOnClickListener { lifecycleScope.launch { manager.sendAppSwitch() } }
        binding.btnPower.setOnClickListener { lifecycleScope.launch { manager.sendPower() } }
        binding.btnVolUp.setOnClickListener { lifecycleScope.launch { manager.sendVolumeUp() } }
        binding.btnVolDown.setOnClickListener { lifecycleScope.launch { manager.sendVolumeDown() } }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchControl() {
        binding.ivScreen.setOnTouchListener { v, event ->
            val manager = scrcpyManager ?: return@setOnTouchListener false
            val viewWidth = v.width
            val viewHeight = v.height
            if (viewWidth <= 0 || viewHeight <= 0) return@setOnTouchListener false

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

                    val targetDownX = (touchDownX / viewWidth * deviceWidth).toInt()
                    val targetDownY = (touchDownY / viewHeight * deviceHeight).toInt()
                    val targetUpX = (upX / viewWidth * deviceWidth).toInt()
                    val targetUpY = (upY / viewHeight * deviceHeight).toInt()

                    lifecycleScope.launch {
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) < 20) {
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
    }

    private fun startScreenStream() {
        binding.pbLoading.visibility = View.VISIBLE
        val manager = scrcpyManager ?: return

        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            // 获取实际分辨率
            val client = DeviceManager.adbClient
            if (client != null) {
                val sizeOut = client.executeShell("wm size")
                val match = Regex("""(\d+)x(\d+)""").find(sizeOut)
                if (match != null) {
                    deviceWidth = match.groupValues[1].toInt()
                    deviceHeight = match.groupValues[2].toInt()
                }
            }

            withContext(Dispatchers.Main) {
                binding.pbLoading.visibility = View.GONE
            }

            // 循环拉取屏幕帧
            while (isActive) {
                val frame = manager.captureFrame()
                if (frame != null) {
                    withContext(Dispatchers.Main) {
                        binding.ivScreen.setImageBitmap(frame)
                    }
                }
                delay(200) // 5fps 稳定刷新
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamJob?.cancel()
    }
}
