package com.toolbox.adbhelper.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.databinding.ActivityTerminalBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSend.setOnClickListener {
            val cmd = binding.etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                executeCommand(cmd)
                binding.etCommand.setText("")
            }
        }

        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }

        binding.cmdClear.setOnClickListener {
            binding.tvTerminalOutput.text = "$ "
        }

        binding.cmdGetprop.setOnClickListener { executeCommand("getprop") }
        binding.cmdTop.setOnClickListener { executeCommand("top -n 1") }
        binding.cmdPmList.setOnClickListener { executeCommand("pm list packages -3") }
        binding.cmdIp.setOnClickListener { executeCommand("ip addr") }
    }

    private fun executeCommand(command: String) {
        val client = DeviceManager.adbClient
        if (client == null) {
            Toast.makeText(this, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
            return
        }

        appendOutput("$ $command\n")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val output = client.executeShell(command)
                withContext(Dispatchers.Main) {
                    appendOutput(output + if (output.endsWith("\n")) "$ " else "\n$ ")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("Error: ${e.message}\n$ ")
                }
            }
        }
    }

    private fun appendOutput(text: String) {
        binding.tvTerminalOutput.append(text)
        binding.svTerminal.post {
            binding.svTerminal.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
