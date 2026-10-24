package com.toolbox.adbhelper.core.adb

import com.toolbox.adbhelper.core.model.AppItem
import com.toolbox.adbhelper.core.model.DeviceInfo
import com.toolbox.adbhelper.core.model.FileItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class AdbClient(val connection: AdbConnection) {

    val isConnected: Boolean get() = connection.isConnected()

    fun executeShell(command: String, timeoutMs: Long = 10000): String {
        val stream = connection.openStream("shell:$command", timeoutMs)
        val baos = ByteArrayOutputStream()
        try {
            while (true) {
                val chunk = stream.read() ?: break
                baos.write(chunk)
            }
        } finally {
            stream.close()
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    fun openInteractiveShell(): AdbStream {
        return connection.openStream("shell:")
    }

    fun openSync(): AdbSync {
        val stream = connection.openStream("sync:")
        return AdbSync(stream)
    }

    fun getDeviceInfo(): DeviceInfo {
        val info = DeviceInfo()
        try {
            val props = executeShell("getprop")
            val propMap = mutableMapOf<String, String>()
            for (line in props.lines()) {
                val match = Regex("""\[(.*?)\]: \[(.*?)\]""").find(line)
                if (match != null) {
                    val key = match.groupValues[1]
                    val value = match.groupValues[2]
                    propMap[key] = value
                }
            }
            info.brand = propMap["ro.product.brand"] ?: propMap["ro.product.manufacturer"] ?: "Unknown"
            info.model = propMap["ro.product.model"] ?: "Unknown"
            info.manufacturer = propMap["ro.product.manufacturer"] ?: "Unknown"
            info.deviceCode = propMap["ro.product.device"] ?: propMap["ro.product.name"] ?: "Unknown"
            info.androidVersion = propMap["ro.build.version.release"] ?: "Unknown"
            info.sdkVersion = propMap["ro.build.version.sdk"]?.toIntOrNull() ?: 0
            info.securityPatch = propMap["ro.build.version.security_patch"] ?: "Unknown"
            info.abiList = propMap["ro.product.cpu.abilist"] ?: propMap["ro.product.cpu.abi"] ?: "Unknown"

            // 电池状态
            val batteryOut = executeShell("dumpsys battery")
            for (line in batteryOut.lines()) {
                val trim = line.trim()
                if (trim.startsWith("level:")) {
                    info.batteryLevel = trim.substringAfter("level:").trim().toIntOrNull() ?: -1
                } else if (trim.startsWith("temperature:")) {
                    val t = trim.substringAfter("temperature:").trim().toFloatOrNull() ?: -1f
                    info.batteryTemp = if (t > 100) t / 10f else t
                } else if (trim.startsWith("status:")) {
                    info.batteryStatus = when (trim.substringAfter("status:").trim()) {
                        "2" -> "正在充电"
                        "3" -> "放电中"
                        "4" -> "未充电"
                        "5" -> "充满"
                        else -> "正常"
                    }
                }
            }

            // 屏幕
            val sizeOut = executeShell("wm size")
            info.resolution = sizeOut.lines().firstOrNull { it.contains("size:") }
                ?.substringAfter("size:")?.trim() ?: "Unknown"

            val densityOut = executeShell("wm density")
            info.density = densityOut.lines().firstOrNull { it.contains("density:") }
                ?.substringAfter("density:")?.trim() ?: "Unknown"

            // 存储空间
            val dfOut = executeShell("df /sdcard")
            val dfLines = dfOut.lines().filter { it.isNotBlank() }
            if (dfLines.size >= 2) {
                val parts = dfLines[1].split(Regex("""\s+"""))
                if (parts.size >= 4) {
                    info.storageTotal = parts[1]
                    info.storageFree = parts[3]
                }
            }

            // IP 地址
            val ipOut = executeShell("ip route get 1.1.1.1 2>/dev/null || ip addr show wlan0 2>/dev/null")
            val ipMatch = Regex("""src\s+([0-9.]+)""").find(ipOut)
                ?: Regex("""inet\s+([0-9.]+)""").find(ipOut)
            if (ipMatch != null) {
                info.ipAddress = ipMatch.groupValues[1]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return info
    }

    fun getInstalledApps(filterSystem: Boolean = false): List<AppItem> {
        val out = executeShell("pm list packages -f -u")
        val result = mutableListOf<AppItem>()
        for (line in out.lines()) {
            if (!line.startsWith("package:")) continue
            val raw = line.removePrefix("package:")
            val splitIdx = raw.lastIndexOf('=')
            if (splitIdx > 0) {
                val apkPath = raw.substring(0, splitIdx)
                val pkgName = raw.substring(splitIdx + 1)
                val isSystem = apkPath.startsWith("/system") || apkPath.startsWith("/vendor") || apkPath.startsWith("/product")
                if (filterSystem && isSystem) continue
                result.add(
                    AppItem(
                        packageName = pkgName,
                        appName = pkgName.substringAfterLast('.'),
                        apkPath = apkPath,
                        isSystem = isSystem,
                        isEnabled = true
                    )
                )
            }
        }
        return result.sortedBy { it.packageName }
    }

    fun installApk(apkFile: File): String {
        val remoteTemp = "/data/local/tmp/temp_install.apk"
        val sync = openSync()
        try {
            sync.pushFile(apkFile, remoteTemp)
        } finally {
            sync.close()
        }
        val out = executeShell("pm install -r -d $remoteTemp")
        executeShell("rm -f $remoteTemp")
        return out.trim()
    }

    fun uninstallApp(packageName: String, keepData: Boolean = false): String {
        val flag = if (keepData) "-k" else ""
        return executeShell("pm uninstall $flag $packageName").trim()
    }

    fun disableApp(packageName: String): String {
        return executeShell("pm disable-user --user 0 $packageName").trim()
    }

    fun enableApp(packageName: String): String {
        return executeShell("pm enable $packageName").trim()
    }

    fun clearAppData(packageName: String): String {
        return executeShell("pm clear $packageName").trim()
    }

    fun forceStop(packageName: String): String {
        return executeShell("am force-stop $packageName").trim()
    }

    fun reboot(target: String = ""): String {
        return if (target.isBlank()) {
            executeShell("reboot")
        } else {
            executeShell("reboot $target")
        }
    }

    fun modifyDensity(density: Int): String {
        return if (density <= 0) {
            executeShell("wm density reset")
        } else {
            executeShell("wm density $density")
        }
    }

    fun modifyResolution(width: Int, height: Int): String {
        return if (width <= 0 || height <= 0) {
            executeShell("wm size reset")
        } else {
            executeShell("wm size ${width}x${height}")
        }
    }

    fun listDirectory(path: String): List<FileItem> {
        val targetPath = if (path.endsWith("/")) path else "$path/"
        val out = executeShell("ls -la \"$targetPath\"")
        val items = mutableListOf<FileItem>()
        for (line in out.lines()) {
            val trim = line.trim()
            if (trim.isBlank() || trim.startsWith("total ")) continue
            val parts = trim.split(Regex("""\s+"""), limit = 8)
            if (parts.size >= 8) {
                val perms = parts[0]
                val owner = "${parts[2]}:${parts[3]}"
                val size = parts[4].toLongOrNull() ?: 0L
                val date = "${parts[5]} ${parts[6]}"
                val name = parts[7]
                if (name == "." || name == "..") continue
                val isDir = perms.startsWith("d")
                items.add(
                    FileItem(
                        name = name,
                        path = "$targetPath$name",
                        isDirectory = isDir,
                        size = size,
                        permissions = perms,
                        owner = owner,
                        lastModified = date
                    )
                )
            }
        }
        return items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun activateAppService(appType: String): String {
        return when (appType.lowercase()) {
            "shizuku" -> executeShell("sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
            "blackroom", "黑阈" -> executeShell("sh /data/data/me.piebridge.brevent/brevent.sh")
            "icebox", "冰箱" -> executeShell("dpm set-device-owner com.catchingnow.icebox/.receiver.DPMReceiver")
            "dhizuku" -> executeShell("dpm set-device-owner com.rosan.dhizuku/.receiver.DhizukuDAReceiver")
            else -> "未知应用类型"
        }
    }

    fun close() {
        connection.close()
    }
}
