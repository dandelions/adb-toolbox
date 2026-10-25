package com.toolbox.adbhelper.core.scrcpy

import android.util.Log
import com.toolbox.adbhelper.core.adb.AdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 虚拟耳机管理器：通过 Linux 内核输入事件将虚拟耳机插入/拔出被控端
 * 原理：向被控端的 /dev/input/event* (支持 SW 0005) 发送 SW_HEADPHONE_INSERT (0002) 等事件
 * 效果：被控端系统音频路由立即切换为耳机通道，硬件扬声器完全物理静音，同时投屏音频流 (Sndcpy) 不受影响
 */
object VirtualHeadsetManager {
    private const val TAG = "VirtualHeadsetManager"
    private var cachedEventNode: String? = null
    private val mutex = Mutex()

    var isPluggedIn: Boolean = false
        private set

    /**
     * 探测被控机支持有线耳机的事件节点 (/dev/input/eventX)
     */
    suspend fun findHeadsetNode(client: AdbClient): String? = withContext(Dispatchers.IO) {
        cachedEventNode?.let { return@withContext it }
        try {
            // 自动检索所有输入设备中支持 SW (0005) 且含耳机检测的节点
            val probeCmd = "for dev in /dev/input/event*; do if getevent -p \$dev 2>/dev/null | grep -q '0005'; then echo \$dev; break; fi; done"
            val output = client.executeShell(probeCmd, 3000).trim()
            val node = output.lines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("/dev/input/event") }

            if (!node.isNullOrBlank()) {
                cachedEventNode = node
                Log.i(TAG, "成功锁定被控端耳机节点: $node")
                return@withContext node
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测耳机节点异常: ${e.message}")
        }
        return@withContext null
    }

    /**
     * 插入虚拟耳机（使被控端外放扬声器静音）
     */
    suspend fun plugIn(client: AdbClient): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            var node = findHeadsetNode(client)
            if (node == null) {
                // 重试一次
                cachedEventNode = null
                node = findHeadsetNode(client)
            }
            if (node == null) {
                Log.w(TAG, "未能在被控端找到支持耳机的输入节点")
                return@withContext false
            }

            try {
                // 5: EV_SW, 2: SW_HEADPHONE_INSERT, 4: SW_MICROPHONE_INSERT, 7: SW_JACK_PHYSICAL_INSERT, 0: SYN_REPORT
                val cmd = "sendevent $node 5 2 1; sendevent $node 5 4 1; sendevent $node 5 7 1; sendevent $node 0 0 0"
                client.executeShell(cmd, 2500)
                isPluggedIn = true
                Log.i(TAG, "已成功向 $node 发送虚拟耳机插入事件")
                true
            } catch (e: Exception) {
                Log.e(TAG, "插入虚拟耳机失败: ${e.message}")
                false
            }
        }
    }

    /**
     * 拔出虚拟耳机（恢复被控端外放扬声器）
     */
    suspend fun plugOut(client: AdbClient): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val node = cachedEventNode ?: findHeadsetNode(client)
            if (node == null) {
                Log.w(TAG, "未能在被控端找到支持耳机的输入节点")
                return@withContext false
            }

            try {
                val cmd = "sendevent $node 5 2 0; sendevent $node 5 4 0; sendevent $node 5 7 0; sendevent $node 0 0 0"
                client.executeShell(cmd, 2500)
                isPluggedIn = false
                Log.i(TAG, "已成功向 $node 发送虚拟耳机拔出事件")
                true
            } catch (e: Exception) {
                Log.e(TAG, "拔出虚拟耳机失败: ${e.message}")
                false
            }
        }
    }

    /**
     * 切换虚拟耳机状态
     */
    suspend fun toggle(client: AdbClient): Boolean {
        return if (isPluggedIn) {
            plugOut(client)
            false
        } else {
            plugIn(client)
            true
        }
    }

    /**
     * 重置状态（连接断开或投屏彻底销毁时）
     */
    fun reset() {
        isPluggedIn = false
        cachedEventNode = null
    }
}
