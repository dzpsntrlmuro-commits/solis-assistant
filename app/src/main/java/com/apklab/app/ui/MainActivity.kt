package com.apklab.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apklab.app.R
import com.apklab.app.core.ApkExtractor
import com.apklab.app.core.InstalledAppsScanner
import com.apklab.app.core.ManifestDecoder
import com.apklab.app.databinding.ActivityMainBinding
import com.apklab.app.model.ApkInfo
import com.apklab.app.plugin.PluginEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = AppListAdapter { openApk(it) }

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PluginEngine.installSampleExternalPlugin(this)

        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        binding.btnPickApk.setOnClickListener {
            pickApk.launch(arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "*/*"
            ))
        }
        binding.btnInstalled.setOnClickListener { toggleInstalledList() }
        binding.btnPlugins.setOnClickListener {
            startActivity(Intent(this, PluginManagerActivity::class.java))
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                loadInstalled(s?.toString().orEmpty())
            }
        })

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        importUri(uri)
    }

    private fun toggleInstalledList() {
        val show = !binding.etSearch.isVisible
        binding.etSearch.isVisible = show
        binding.rvApps.isVisible = show
        if (show) loadInstalled(binding.etSearch.text?.toString().orEmpty())
    }

    private fun loadInstalled(query: String) {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            binding.tvStatus.text = "Yüklü uygulamalar taranıyor…"
            val apps = withContext(Dispatchers.IO) {
                InstalledAppsScanner.listLaunchableApps(this@MainActivity, query)
            }
            adapter.submit(apps)
            binding.progress.isVisible = false
            binding.tvStatus.text = "${apps.size} uygulama listelendi"
        }
    }

    private fun importUri(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true, getString(R.string.status_extracting))
            try {
                val cached = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        ApkExtractor.copyUriToCache(
                            this@MainActivity,
                            input,
                            "import-${System.currentTimeMillis()}.apk"
                        )
                    } ?: error("APK okunamadı")
                }
                openApkFile(cached, cached.name, "unknown")
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                setBusy(false, "İçe aktarma başarısız")
            }
        }
    }

    private fun openApk(info: ApkInfo) {
        openApkFile(File(info.sourcePath), info.label, info.packageName)
    }

    private fun openApkFile(apk: File, label: String, packageName: String) {
        lifecycleScope.launch {
            setBusy(true, getString(R.string.status_extracting))
            try {
                val workspace = withContext(Dispatchers.IO) {
                    val ws = ApkExtractor.extract(
                        this@MainActivity,
                        apk,
                        label = label,
                        packageName = packageName
                    ) { msg ->
                        runOnUiThread { binding.tvStatus.text = msg }
                    }
                    val detected = ManifestDecoder.extractPackageName(File(ws.extractDir))
                    if (detected != null && (packageName == "unknown" || packageName.isBlank())) {
                        ws.copy(packageName = detected, label = if (label.endsWith(".apk")) detected else label)
                    } else ws
                }
                setBusy(false, getString(R.string.status_done))
                startActivity(
                    Intent(this@MainActivity, WorkspaceActivity::class.java).apply {
                        putExtra(WorkspaceActivity.EXTRA_ID, workspace.id)
                        putExtra(WorkspaceActivity.EXTRA_LABEL, workspace.label)
                        putExtra(WorkspaceActivity.EXTRA_PACKAGE, workspace.packageName)
                        putExtra(WorkspaceActivity.EXTRA_DIR, workspace.extractDir)
                        putExtra(WorkspaceActivity.EXTRA_SOURCE, workspace.sourceApk)
                    }
                )
            } catch (e: Exception) {
                setBusy(false, "Hata: ${e.message}")
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        binding.progress.isVisible = busy
        binding.btnPickApk.isEnabled = !busy
        binding.btnInstalled.isEnabled = !busy
        binding.tvStatus.text = status
    }
}

private class AppListAdapter(
    private val onClick: (ApkInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private val items = mutableListOf<ApkInfo>()

    fun submit(data: List<ApkInfo>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.pkg.text = item.packageName
        val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
        holder.meta.text = "v${item.versionName} (${item.versionCode}) · %.1f MB%s".format(
            sizeMb,
            if (item.isSystemApp) " · sistem" else ""
        )
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvAppName)
        val pkg: TextView = view.findViewById(R.id.tvPackage)
        val meta: TextView = view.findViewById(R.id.tvMeta)
    }
}
