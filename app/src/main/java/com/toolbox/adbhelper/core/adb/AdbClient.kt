package com.toolbox.adbhelper.core.adb

import com.toolbox.adbhelper.core.model.AppItem
import com.toolbox.adbhelper.core.model.DeviceInfo
import com.toolbox.adbhelper.core.model.FileItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

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

    fun executeShellLines(command: String, timeoutMs: Long = 60000, onLine: (String) -> Unit) {
        val stream = connection.openStream("shell:$command", timeoutMs)
        try {
            val reader = stream.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        } finally {
            stream.close()
        }
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

            // 存储空间 (转换为 GB 单位)
            val dfOut = executeShell("df -k /sdcard 2>/dev/null || df /sdcard 2>/dev/null || df /data")
            val dfLines = dfOut.lines().filter { it.isNotBlank() }
            val dataLine = dfLines.firstOrNull { it.contains("%") } ?: dfLines.getOrNull(1)
            if (dataLine != null) {
                val parts = dataLine.trim().split(Regex("""\s+"""))
                val percentIndex = parts.indexOfFirst { it.endsWith("%") || it.contains("%") }
                if (percentIndex == 4 && parts.size >= 5) {
                    info.storageTotal = formatStorageToGb(parts[1])
                    info.storageFree = formatStorageToGb(parts[3])
                } else if (percentIndex == 3 && parts.size >= 4) {
                    info.storageTotal = formatStorageToGb(parts[0])
                    info.storageFree = formatStorageToGb(parts[2])
                } else if (parts.size >= 4) {
                    info.storageTotal = formatStorageToGb(parts[1])
                    info.storageFree = formatStorageToGb(parts[3])
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

    companion object {
        fun formatStorageToGb(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.equals("unknown", ignoreCase = true)) {
                return "Unknown"
            }

            val upper = trimmed.uppercase(Locale.US)
            if (upper.endsWith("GB") || upper.endsWith("G")) {
                val numStr = upper.removeSuffix("GB").removeSuffix("G").trim()
                val num = numStr.toDoubleOrNull()
                return if (num != null) String.format(Locale.US, "%.1f GB", num) else trimmed
            }
            if (upper.endsWith("TB") || upper.endsWith("T")) {
                val numStr = upper.removeSuffix("TB").removeSuffix("T").trim()
                val num = numStr.toDoubleOrNull()
                return if (num != null) String.format(Locale.US, "%.1f GB", num * 1024.0) else trimmed
            }
            if (upper.endsWith("MB") || upper.endsWith("M")) {
                val numStr = upper.removeSuffix("MB").removeSuffix("M").trim()
                val num = numStr.toDoubleOrNull()
                return if (num != null) String.format(Locale.US, "%.1f GB", num / 1024.0) else trimmed
            }
            if (upper.endsWith("KB") || upper.endsWith("K")) {
                val numStr = upper.removeSuffix("KB").removeSuffix("K").trim()
                val num = numStr.toDoubleOrNull()
                return if (num != null) String.format(Locale.US, "%.1f GB", num / (1024.0 * 1024.0)) else trimmed
            }

            val num = trimmed.toDoubleOrNull() ?: return trimmed
            val gb = when {
                num > 10_000_000_000.0 -> num / (1024.0 * 1024.0 * 1024.0)
                num > 10_000.0 -> num / (1024.0 * 1024.0)
                num > 1000.0 -> num / 1024.0
                else -> num
            }
            return String.format(Locale.US, "%.1f GB", gb)
        }
    }
}
