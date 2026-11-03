package com.toolbox.adbhelper.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.model.FileItem
import com.toolbox.adbhelper.databinding.ActivityFileManagerBinding
import com.toolbox.adbhelper.databinding.DialogDownloadProgressBinding
import com.toolbox.adbhelper.databinding.ItemFileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private var currentPath: String = "/sdcard"
    private val rawFilesList = mutableListOf<FileItem>()
    private val filesList = mutableListOf<FileItem>()
    private lateinit var adapter: FileAdapter
    private var currentSortOrder: FileSortOrder = FileSortOrder.NAME_ASC

    enum class FileSortOrder(val title: String, val shortLabel: String) {
        NAME_ASC("按名称升序 (A → Z)", "名称 ↑"),
        NAME_DESC("按名称降序 (Z → A)", "名称 ↓"),
        SIZE_DESC("按大小降序 (由大到小)", "大小 ↓"),
        SIZE_ASC("按大小升序 (由小到大)", "大小 ↑"),
        TIME_DESC("按修改时间降序 (最新优先)", "时间 ↓"),
        TIME_ASC("按修改时间升序 (最旧优先)", "时间 ↑");

        companion object {
            fun fromOrdinal(ordinal: Int): FileSortOrder {
                return values().getOrElse(ordinal) { NAME_ASC }
            }
        }
    }

    private val selectUploadLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadFileFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        loadDirectory(currentPath)
    }

    override fun onResume() {
        super.onResume()
        if (!DeviceManager.isConnected && !DeviceManager.isExplicitDisconnect) {
            lifecycleScope.launch {
                val client = DeviceManager.getOrReconnectClient(this@FileManagerActivity)
                if (client != null && filesList.isEmpty()) {
                    loadDirectory(currentPath)
                }
            }
        }
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        currentSortOrder = loadSortPreference()
        updateSortLabel()

        binding.btnSortToolbar.setOnClickListener { showSortDialog() }
        binding.btnSortPath.setOnClickListener { showSortDialog() }

        adapter = FileAdapter(filesList) { item ->
            if (item.isDirectory) {
                loadDirectory(item.path)
            } else {
                showFileOptions(item)
            }
        }
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter

        binding.btnGoUp.setOnClickListener {
            val parent = File(currentPath).parent
            if (parent != null && parent != currentPath) {
                loadDirectory(parent)
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadDirectory(currentPath)
        }

        binding.btnQuickSdcard.setOnClickListener { loadDirectory("/sdcard") }
        binding.btnQuickDownload.setOnClickListener { loadDirectory("/sdcard/Download") }
        binding.btnQuickTmp.setOnClickListener { loadDirectory("/data/local/tmp") }

        binding.btnUploadFile.setOnClickListener {
            selectUploadLauncher.launch("*/*")
        }

        binding.btnNewFolder.setOnClickListener {
            showNewFolderDialog()
        }
    }

    private fun loadSortPreference(): FileSortOrder {
        val prefs = getSharedPreferences("file_manager_prefs", MODE_PRIVATE)
        val ordinal = prefs.getInt("file_sort_order", FileSortOrder.NAME_ASC.ordinal)
        return FileSortOrder.fromOrdinal(ordinal)
    }

    private fun saveSortPreference(order: FileSortOrder) {
        getSharedPreferences("file_manager_prefs", MODE_PRIVATE)
            .edit()
            .putInt("file_sort_order", order.ordinal)
            .apply()
    }

    private fun updateSortLabel() {
        binding.tvSortLabel.text = currentSortOrder.shortLabel
    }

    private fun parseFileDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "MMM dd HH:mm:ss yyyy",
            "MMM dd HH:mm yyyy",
            "MMM dd HH:mm:ss",
            "MMM dd HH:mm",
            "MMM dd yyyy"
        )
        for (pattern in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun sortFileList(list: List<FileItem>, sortOrder: FileSortOrder): List<FileItem> {
        val comparator = when (sortOrder) {
            FileSortOrder.NAME_ASC -> compareBy<FileItem>({ !it.isDirectory }, { it.name.lowercase() })
            FileSortOrder.NAME_DESC -> compareBy<FileItem>({ !it.isDirectory }).thenByDescending { it.name.lowercase() }
            FileSortOrder.SIZE_DESC -> compareBy<FileItem>({ !it.isDirectory }).thenByDescending { it.size }.thenBy { it.name.lowercase() }
            FileSortOrder.SIZE_ASC -> compareBy<FileItem>({ !it.isDirectory }).thenBy { it.size }.thenBy { it.name.lowercase() }
            FileSortOrder.TIME_DESC -> compareBy<FileItem>({ !it.isDirectory })
                .thenByDescending { parseFileDate(it.lastModified) }
                .thenByDescending { it.lastModified }
                .thenBy { it.name.lowercase() }
            FileSortOrder.TIME_ASC -> compareBy<FileItem>({ !it.isDirectory })
                .thenBy { parseFileDate(it.lastModified) }
                .thenBy { it.lastModified }
                .thenBy { it.name.lowercase() }
        }
        return list.sortedWith(comparator)
    }

    private fun applySort() {
        val sorted = sortFileList(rawFilesList, currentSortOrder)
        filesList.clear()
        filesList.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    private fun showSortDialog() {
        val options = FileSortOrder.values().map { it.title }.toTypedArray()
        val currentIndex = currentSortOrder.ordinal
        MaterialAlertDialogBuilder(this)
            .setTitle("文件排序方式")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = FileSortOrder.fromOrdinal(which)
                if (selected != currentSortOrder) {
                    currentSortOrder = selected
                    saveSortPreference(selected)
                    updateSortLabel()
                    applySort()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadDirectory(path: String) {
        currentPath = path
        binding.tvCurrentPath.text = currentPath
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            val client = DeviceManager.getOrReconnectClient(this@FileManagerActivity)
            if (client == null) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@FileManagerActivity, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val list = withContext(Dispatchers.IO) { client.listDirectory(path) }
                rawFilesList.clear()
                rawFilesList.addAll(list)
                applySort()
                binding.swipeRefresh.isRefreshing = false
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@FileManagerActivity, "加载目录失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFileOptions(item: FileItem) {
        val options = arrayOf("下载到本地 (Pull)", "删除该文件 (Delete)", "查看详细路径")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadFile(item)
                    1 -> deleteFile(item)
                    2 -> {
                        Toast.makeText(this, item.path, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024f
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024f
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024f
            return "%.2f GB".format(gb)
        }
    }

    private fun downloadFile(item: FileItem) {
        val dialogBinding = DialogDownloadProgressBinding.inflate(layoutInflater)
        dialogBinding.tvDownloadTitle.text = "正在下载文件"
        dialogBinding.tvDownloadFileName.text = item.name

        val totalBytes = item.size
        if (totalBytes > 0) {
            dialogBinding.progressBar.isIndeterminate = false
            dialogBinding.progressBar.max = 100
            dialogBinding.progressBar.progress = 0
            dialogBinding.tvDownloadBytes.text = "0 B / ${formatFileSize(totalBytes)}"
            dialogBinding.tvDownloadSpeed.text = ""
            dialogBinding.tvDownloadPercent.text = "0%"
        } else {
            dialogBinding.progressBar.isIndeterminate = true
            dialogBinding.tvDownloadBytes.text = "正在连接并准备下载..."
            dialogBinding.tvDownloadSpeed.text = ""
            dialogBinding.tvDownloadPercent.text = ""
        }

        var isCancelled = false
        var isFinished = false

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setNegativeButton("取消下载") { d, _ ->
                isCancelled = true
                d.dismiss()
            }
            .create()

        dialog.show()

        val downloadJob = lifecycleScope.launch {
            val client = DeviceManager.getOrReconnectClient(this@FileManagerActivity)
            if (client == null) {
                isFinished = true
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    try { dialog.dismiss() } catch (_: Exception) {}
                }
                Toast.makeText(this@FileManagerActivity, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
                return@launch
            }

            var lastUpdateTime = 0L
            var lastSpeedTime = System.currentTimeMillis()
            var lastSpeedBytes = 0L
            var currentSpeedText = ""

            try {
                val savedLocation = withContext(Dispatchers.IO) {
                    val sync = client.openSync()
                    try {
                        com.toolbox.adbhelper.core.utils.DownloadHelper.downloadToPublicDownloads(
                            context = applicationContext,
                            sync = sync,
                            remotePath = item.path,
                            fileName = item.name,
                            totalBytes = totalBytes,
                            onProgress = { downloaded, total ->
                                if (isCancelled) {
                                    throw java.io.InterruptedIOException("用户已取消下载")
                                }
                                val now = System.currentTimeMillis()
                                val timeSinceSpeed = now - lastSpeedTime
                                if (timeSinceSpeed >= 500) {
                                    val bytesDelta = downloaded - lastSpeedBytes
                                    if (bytesDelta >= 0 && timeSinceSpeed > 0) {
                                        val speedBytesPerSec = (bytesDelta * 1000L) / timeSinceSpeed
                                        currentSpeedText = "${formatFileSize(speedBytesPerSec)}/s"
                                    }
                                    lastSpeedTime = now
                                    lastSpeedBytes = downloaded
                                }

                                if (now - lastUpdateTime > 80 || (total > 0 && downloaded >= total)) {
                                    lastUpdateTime = now
                                    val speedStr = currentSpeedText
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed && dialog.isShowing) {
                                            if (total > 0) {
                                                val progress = ((downloaded.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                                                dialogBinding.progressBar.isIndeterminate = false
                                                dialogBinding.progressBar.progress = progress
                                                dialogBinding.tvDownloadPercent.text = "$progress%"
                                                dialogBinding.tvDownloadBytes.text = "${formatFileSize(downloaded)} / ${formatFileSize(total)}"
                                                dialogBinding.tvDownloadSpeed.text = speedStr
                                            } else {
                                                dialogBinding.progressBar.isIndeterminate = true
                                                dialogBinding.tvDownloadBytes.text = "已下载: ${formatFileSize(downloaded)}"
                                                dialogBinding.tvDownloadSpeed.text = speedStr
                                                dialogBinding.tvDownloadPercent.text = ""
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    } finally {
                        sync.close()
                    }
                }

                isFinished = true
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    try { dialog.dismiss() } catch (_: Exception) {}
                }
                Toast.makeText(this@FileManagerActivity, "下载完成，已保存至本机下载目录:\n$savedLocation", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                isFinished = true
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    try { dialog.dismiss() } catch (_: Exception) {}
                }
                if (isCancelled || e is java.io.InterruptedIOException || e is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(this@FileManagerActivity, "下载已取消", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FileManagerActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.setOnDismissListener {
            if (!isFinished) {
                isCancelled = true
                downloadJob.cancel()
            }
        }
    }

    private fun deleteFile(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要永久删除 ${item.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val client = DeviceManager.getOrReconnectClient(this@FileManagerActivity)
                    if (client == null) {
                        Toast.makeText(this@FileManagerActivity, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        client.executeShell("rm -rf \"${item.path}\"")
                    }
                    Toast.makeText(this@FileManagerActivity, "已删除", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNewFolderDialog() {
        val input = EditText(this).apply { hint = "文件夹名称" }
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val client = DeviceManager.getOrReconnectClient(this@FileManagerActivity)
                        if (client == null) {
                            Toast.makeText(this@FileManagerActivity, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        withContext(Dispatchers.IO) {
                            client.executeShell("mkdir -p \"$currentPath/$name\"")
                        }
                        loadDirectory(currentPath)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun uploadFileFromUri(uri: Uri) {
        Toast.makeText(this, "正在上传文件...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val client = DeviceManager.getOrReconnectClient(this@FileManagerActivity)
            if (client == null) {
                Toast.makeText(this@FileManagerActivity, "ADB 设备未连接", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    val temp = File(cacheDir, "upload_temp")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val remoteName = "upload_${System.currentTimeMillis()}"
                    val sync = client.openSync()
                    sync.pushFile(temp, "$currentPath/$remoteName")
                    sync.close()
                    temp.delete()
                }
                Toast.makeText(this@FileManagerActivity, "上传成功！", Toast.LENGTH_SHORT).show()
                loadDirectory(currentPath)
            } catch (e: Exception) {
                Toast.makeText(this@FileManagerActivity, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    class FileAdapter(
        private val list: List<FileItem>,
        private val onItemClick: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.binding.tvFileName.text = item.name
            holder.binding.tvFileInfo.text = "${item.permissions} | ${formatFileSize(item.size)} | ${item.lastModified}"
            holder.binding.ivFileIcon.setImageResource(
                if (item.isDirectory) android.R.drawable.ic_menu_directions else android.R.drawable.ic_menu_agenda
            )
            holder.binding.root.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = list.size
    }
}
