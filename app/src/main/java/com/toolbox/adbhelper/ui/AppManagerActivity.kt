package com.toolbox.adbhelper.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.toolbox.adbhelper.R
import com.toolbox.adbhelper.core.DeviceManager
import com.toolbox.adbhelper.core.app.AppIconManager
import com.toolbox.adbhelper.core.model.AppItem
import com.toolbox.adbhelper.databinding.ActivityAppManagerBinding
import com.toolbox.adbhelper.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AppManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppManagerBinding
    private val allApps = mutableListOf<AppItem>()
    private val displayApps = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    private val selectApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            installApkFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (!DeviceManager.isConnected && !DeviceManager.isExplicitDisconnect) {
            lifecycleScope.launch {
                val client = DeviceManager.getOrReconnectClient(this@AppManagerActivity)
                if (client != null && allApps.isEmpty()) {
                    loadApps()
                }
            }
        }
    }

    private fun initViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppAdapter(displayApps) { appItem ->
            showAppOptions(appItem)
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadApps() }

        binding.etSearch.doAfterTextChanged { text ->
            filterApps(text?.toString() ?: "")
        }

        binding.chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            filterApps(binding.etSearch.text?.toString() ?: "")
        }

        binding.fabInstall.setOnClickListener {
            selectApkLauncher.launch("application/vnd.android.package-archive")
        }
    }

    private fun loadApps() {
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            val client = DeviceManager.getOrReconnectClient(this@AppManagerActivity)
            if (client == null) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@AppManagerActivity, R.string.adb_not_connected, Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val list = withContext(Dispatchers.IO) { client.getInstalledApps() }
                allApps.clear()
                allApps.addAll(list)
                filterApps(binding.etSearch.text?.toString() ?: "")
                binding.swipeRefresh.isRefreshing = false

                // 异步拉取远程设备的应用图标及真实标签并实时局部刷新
                AppIconManager.syncRemoteIcons(applicationContext, client) { pkgName, label, _ ->
                    if (label != null) {
                        allApps.find { it.packageName == pkgName }?.appName = label
                    }
                    val index = displayApps.indexOfFirst { it.packageName == pkgName }
                    if (index >= 0) {
                        if (label != null) {
                            displayApps[index].appName = label
                        }
                        adapter.notifyItemChanged(index)
                    }
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@AppManagerActivity, getString(R.string.app_load_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filterApps(query: String) {
        displayApps.clear()
        val isSystemFilter = binding.chipSystem.isChecked
        val isThirdFilter = binding.chipThirdParty.isChecked

        for (app in allApps) {
            if (isSystemFilter && !app.isSystem) continue
            if (isThirdFilter && app.isSystem) continue

            if (query.isBlank() || app.packageName.contains(query, true) || app.appName.contains(query, true)) {
                displayApps.add(app)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun showAppOptions(app: AppItem) {
        val options = arrayOf(
            getString(R.string.launch_app),
            getString(R.string.export_apk),
            getString(R.string.uninstall_app),
            getString(R.string.uninstall_keep_data),
            getString(R.string.disable_app),
            getString(R.string.enable_app),
            getString(R.string.clear_app_data),
            getString(R.string.force_stop)
        )

        AlertDialog.Builder(this)
            .setTitle(app.packageName)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val client = DeviceManager.getOrReconnectClient(this@AppManagerActivity)
                    if (client == null) {
                        Toast.makeText(this@AppManagerActivity, R.string.adb_not_connected, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        when (which) {
                            0 -> client.executeShell("monkey -p ${app.packageName} -c android.intent.category.LAUNCHER 1")
                            1 -> extractApk(app)
                            2 -> {
                                val r = client.uninstallApp(app.packageName, false)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AppManagerActivity, getString(R.string.app_result_uninstall, r), Toast.LENGTH_SHORT).show()
                                    loadApps()
                                }
                            }
                            3 -> {
                                val r = client.uninstallApp(app.packageName, true)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AppManagerActivity, getString(R.string.app_result_uninstall, r), Toast.LENGTH_SHORT).show()
                                    loadApps()
                                }
                            }
                            4 -> {
                                val r = client.disableApp(app.packageName)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AppManagerActivity, getString(R.string.app_result_disable, r), Toast.LENGTH_SHORT).show()
                                }
                            }
                            5 -> {
                                val r = client.enableApp(app.packageName)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AppManagerActivity, getString(R.string.app_result_enable, r), Toast.LENGTH_SHORT).show()
                                }
                            }
                            6 -> {
                                val r = client.clearAppData(app.packageName)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AppManagerActivity, getString(R.string.app_result_clear_data, r), Toast.LENGTH_SHORT).show()
                                }
                            }
                            7 -> {
                                val r = client.forceStop(app.packageName)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@AppManagerActivity, R.string.app_force_stopped, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun extractApk(app: AppItem) {
        val client = DeviceManager.getOrReconnectClient(this@AppManagerActivity) ?: return
        var sync: com.toolbox.adbhelper.core.adb.AdbSync? = null
        try {
            sync = client.openSync()
            val fileName = "${app.packageName}.apk"
            val savedLocation = com.toolbox.adbhelper.core.utils.DownloadHelper.downloadToPublicDownloads(
                applicationContext,
                sync,
                app.apkPath,
                fileName
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AppManagerActivity, getString(R.string.apk_exported, savedLocation), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AppManagerActivity, getString(R.string.extract_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        } finally {
            sync?.close()
        }
    }

    private fun installApkFromUri(uri: Uri) {
        Toast.makeText(this, R.string.installing_apk, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val client = DeviceManager.getOrReconnectClient(this@AppManagerActivity)
            if (client == null) {
                Toast.makeText(this@AppManagerActivity, R.string.adb_not_connected, Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val res = withContext(Dispatchers.IO) {
                    val tempFile = File(cacheDir, "install_temp.apk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val r = client.installApk(tempFile)
                    tempFile.delete()
                    r
                }
                AlertDialog.Builder(this@AppManagerActivity)
                    .setTitle(R.string.install_result)
                    .setMessage(res)
                    .setPositiveButton(R.string.confirm) { _, _ -> loadApps() }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@AppManagerActivity, getString(R.string.install_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    class AppAdapter(
        private val list: List<AppItem>,
        private val onItemClick: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.binding.tvAppName.text = item.appName
            holder.binding.tvPackageName.text = item.packageName
            holder.binding.tvAppTag.text = holder.binding.root.context.getString(
                if (item.isSystem) R.string.system_apps else R.string.third_party_apps
            )
            holder.binding.root.setOnClickListener { onItemClick(item) }

            val pkg = item.packageName
            holder.binding.ivAppIcon.tag = pkg
            val memIcon = AppIconManager.getFromMemory(pkg)
            if (memIcon != null) {
                holder.binding.ivAppIcon.setImageBitmap(memIcon)
            } else {
                holder.binding.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                val context = holder.binding.root.context
                (context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    val diskIcon = AppIconManager.loadIcon(context, pkg)
                    if (diskIcon != null) {
                        withContext(Dispatchers.Main) {
                            if (holder.binding.ivAppIcon.tag == pkg) {
                                holder.binding.ivAppIcon.setImageBitmap(diskIcon)
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
