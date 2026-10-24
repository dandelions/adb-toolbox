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
import com.toolbox.adbhelper.databinding.ItemFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private var currentPath: String = "/sdcard"
    private val filesList = mutableListOf<FileItem>()
    private lateinit var adapter: FileAdapter

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

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

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

    private fun loadDirectory(path: String) {
        val client = DeviceManager.adbClient ?: return
        currentPath = path
        binding.tvCurrentPath.text = currentPath
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = client.listDirectory(path)
                withContext(Dispatchers.Main) {
                    filesList.clear()
                    filesList.addAll(list)
                    adapter.notifyDataSetChanged()
                    binding.swipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.swipeRefresh.isRefreshing = false
                    Toast.makeText(this@FileManagerActivity, "加载目录失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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

    private fun downloadFile(item: FileItem) {
        val client = DeviceManager.adbClient ?: return
        Toast.makeText(this, "正在下载到本机下载目录...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            var sync: com.toolbox.adbhelper.core.adb.AdbSync? = null
            try {
                sync = client.openSync()
                val savedLocation = com.toolbox.adbhelper.core.utils.DownloadHelper.downloadToPublicDownloads(
                    applicationContext,
                    sync,
                    item.path,
                    item.name
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileManagerActivity, "下载完成，已保存至本机下载目录:\n$savedLocation", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileManagerActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                sync?.close()
            }
        }
    }

    private fun deleteFile(item: FileItem) {
        val client = DeviceManager.adbClient ?: return
        AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要永久删除 ${item.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    client.executeShell("rm -rf \"${item.path}\"")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FileManagerActivity, "已删除", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNewFolderDialog() {
        val client = DeviceManager.adbClient ?: return
        val input = EditText(this).apply { hint = "文件夹名称" }
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        client.executeShell("mkdir -p \"$currentPath/$name\"")
                        withContext(Dispatchers.Main) {
                            loadDirectory(currentPath)
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun uploadFileFromUri(uri: Uri) {
        val client = DeviceManager.adbClient ?: return
        Toast.makeText(this, "正在上传文件...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileManagerActivity, "上传成功！", Toast.LENGTH_SHORT).show()
                    loadDirectory(currentPath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FileManagerActivity, "上传失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
            holder.binding.tvFileInfo.text = "${item.permissions} | ${formatSize(item.size)} | ${item.lastModified}"
            holder.binding.ivFileIcon.setImageResource(
                if (item.isDirectory) android.R.drawable.ic_menu_directions else android.R.drawable.ic_menu_agenda
            )
            holder.binding.root.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = list.size

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024f
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024f
            return "%.1f MB".format(mb)
        }
    }
}
