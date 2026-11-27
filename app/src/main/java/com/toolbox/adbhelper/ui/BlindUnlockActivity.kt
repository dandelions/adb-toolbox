package com.toolbox.adbhelper.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.R
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 安全锁屏的密码层禁止屏幕采集，投屏无法显示密码键盘，
 * 因此解锁只能通过 ADB 按键盲输入完成。
 */
class BlindUnlockActivity : AppCompatActivity() {

    private val pinBuilder = StringBuilder()
    private lateinit var tvDots: TextView
    private lateinit var tvStatus: TextView
    private var sending = false

    companion object {
        const val EXTRA_SWIPE_FIRST = "swipe_first"
        private const val MAX_PIN_LENGTH = 16
        private const val KEYCODE_0 = 7
        private const val KEYCODE_DEL = 67
        private const val KEYCODE_ENTER = 66
        private const val KEYCODE_WAKEUP = 224
    }

    private var swipeFirst = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blind_unlock)

        tvDots = findViewById(R.id.tvPinDots)
        tvStatus = findViewById(R.id.tvBlindStatus)
        swipeFirst = intent.getBooleanExtra(EXTRA_SWIPE_FIRST, true)
        if (!swipeFirst) {
            findViewById<android.widget.TextView>(R.id.tvBlindTitle).setText(R.string.verify_password)
            findViewById<android.widget.TextView>(R.id.tvBlindHint).text =
                getString(R.string.blind_verify_description)
        }
        val grid = findViewById<GridLayout>(R.id.gridKeypad)

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.forEach { label ->
            val button = Button(this).apply {
                text = label
                textSize = if (label.length == 1) 22f else 18f
                gravity = Gravity.CENTER
                setOnClickListener { onKeyPressed(label) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6, 6, 6, 6)
            }
            grid.addView(button, params)
        }

        findViewById<Button>(R.id.btnBlindClear).setOnClickListener {
            if (sending) return@setOnClickListener
            pinBuilder.setLength(0)
            renderPin()
        }
        findViewById<Button>(R.id.btnBlindSend).setOnClickListener { sendPin(submit = true) }
        findViewById<Button>(R.id.btnBlindClose).setOnClickListener { finish() }
        findViewById<android.widget.TextView>(R.id.btnBlindBack).setOnClickListener { finish() }

        renderPin()
        updateTargetHint()
        refreshLockState()
    }

    private fun refreshLockState() {
        val client = DeviceManager.adbClient ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val locked = isSecureKeyguardShowing(client)
            withContext(Dispatchers.Main) {
                if (sending) return@withContext
                tvStatus.text = if (locked) getString(R.string.device_locked_enter_pin) else getString(R.string.device_unlocked)
            }
        }
    }

    private fun onKeyPressed(label: String) {
        if (sending) return
        when (label) {
            "⌫" -> if (pinBuilder.isNotEmpty()) pinBuilder.setLength(pinBuilder.length - 1)
            "✓" -> {
                sendPin(submit = true)
                return
            }
            else -> if (pinBuilder.length < MAX_PIN_LENGTH) pinBuilder.append(label)
        }
        renderPin()
    }

    private fun renderPin() {
        tvDots.text = "●".repeat(pinBuilder.length)
        tvStatus.text = if (pinBuilder.isEmpty()) "" else getString(R.string.entered_digits, pinBuilder.length)
    }

    private fun updateTargetHint() {
        val client = DeviceManager.adbClient
        val device = DeviceManager.currentDevice
        when {
            client == null || !client.isConnected ->
                tvStatus.text = getString(R.string.connect_adb_first)
            device != null && device.name.isNotBlank() ->
                tvStatus.text = getString(R.string.target_device, device.name)
        }
    }

    private fun sendPin(submit: Boolean) {
        if (sending) return
        val client = DeviceManager.adbClient
        if (client == null || !client.isConnected) {
            tvStatus.text = getString(R.string.cannot_send_no_device)
            return
        }
        if (pinBuilder.isEmpty()) {
            tvStatus.text = getString(R.string.enter_pin_first)
            return
        }
        val digits = pinBuilder.toString()
        sending = true
        tvStatus.text = getString(R.string.sending)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 点亮屏幕但不解锁，避免密码键落在息屏状态
                client.executeShell("input keyevent $KEYCODE_WAKEUP", 3000)
                delay(250)
                // 锁屏壁纸状态下滑出密码键盘，否则数字会被直接丢弃
                if (swipeFirst && isSecureKeyguardShowing(client)) {
                    val (width, height) = deviceSize(client)
                    client.executeShell(
                        "input swipe ${width / 2} ${(height * 0.90).toInt()} ${width / 2} ${(height * 0.40).toInt()} 300",
                        4000
                    )
                    delay(700)
                }
                for (ch in digits) {
                    client.executeShell("input keyevent ${KEYCODE_0 + (ch - '0')}", 3000)
                    delay(130)
                }
                if (submit) {
                    delay(200)
                    client.executeShell("input keyevent $KEYCODE_ENTER", 3000)
                }
                delay(800)
                val stillLocked = isSecureKeyguardShowing(client)
                withContext(Dispatchers.Main) {
                    sending = false
                    if (submit && !stillLocked) {
                        pinBuilder.setLength(0)
                        renderPin()
                        // 解锁成功后直接回到投屏画面
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        tvStatus.text = if (submit) {
                            getString(R.string.pin_sent_check)
                        } else {
                            getString(R.string.digits_sent)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sending = false
                    tvStatus.text = getString(R.string.send_failed, e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun deviceSize(client: AdbClient): Pair<Int, Int> {
        return try {
            val out = client.executeShell("wm size", 3000)
            val override = Regex("""Override size:\s*(\d+)x(\d+)""").find(out)
            val physical = Regex("""Physical size:\s*(\d+)x(\d+)""").find(out)
            val match = override ?: physical
            if (match != null) {
                match.groupValues[1].toInt() to match.groupValues[2].toInt()
            } else {
                1080 to 2400
            }
        } catch (_: Exception) {
            1080 to 2400
        }
    }

    private fun isSecureKeyguardShowing(client: AdbClient): Boolean {
        return try {
            val output = client.executeShell(
                "dumpsys window policy 2>/dev/null | grep -E 'showing=|secure=|mIsShowing'",
                4000
            )
            val showing = Regex("""(?m)^\s*showing=true\s*$""").containsMatchIn(output) ||
                Regex("""(?m)^\s*mIsShowing=true\s*$""").containsMatchIn(output)
            val secure = Regex("""(?m)^\s*secure=true\s*$""").containsMatchIn(output)
            showing && secure
        } catch (_: Exception) {
            true
        }
    }
}
