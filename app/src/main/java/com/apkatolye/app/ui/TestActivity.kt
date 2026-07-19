package com.apkatolye.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apkatolye.app.R
import com.apkatolye.app.apk.WorkspacePaths
import com.apkatolye.app.databinding.ActivityTestBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestBinding
    private var allApps: List<AppItem> = emptyList()
    private val adapter = AppAdapter { launchApp(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnInstallSelected.setOnClickListener {
            installApk(WorkspacePaths.selectedApk(this), "Seçili APK")
        }
        binding.btnInstallRebuilt.setOnClickListener {
            installApk(WorkspacePaths.rebuildApk(this), "Yeniden paketlenmiş APK")
        }

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        loadApps()
    }

    private fun loadApps() {
        binding.count.text = "Uygulamalar yükleniyor…"
        lifecycleScope.launch {
            allApps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            applyFilter(binding.search.text?.toString().orEmpty())
        }
    }

    private fun queryLaunchableApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolve = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return resolve.mapNotNull { info ->
            val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString() ?: appInfo.packageName
            val icon = try {
                info.loadIcon(pm)
            } catch (_: Exception) {
                null
            }
            AppItem(
                label = label,
                packageName = appInfo.packageName,
                icon = icon,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) allApps
        else allApps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        adapter.submit(filtered)
        binding.count.text = "${filtered.size} uygulama  ·  dokunarak aç"
    }

    private fun launchApp(item: AppItem) {
        val launch = packageManager.getLaunchIntentForPackage(item.packageName)
        if (launch == null) {
            Toast.makeText(this, "Açılamadı: ${item.packageName}", Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        Toast.makeText(this, "Test: ${item.label}", Toast.LENGTH_SHORT).show()
    }

    private fun installApk(apk: File, label: String) {
        if (!apk.exists()) {
            Toast.makeText(this, "$label bulunamadı. Önce APK seçin / yeniden paketleyin.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Bilinmeyen uygulamalardan kurulum izni gerekli", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "$label kurulum ekranı açıldı. İmzasız APK’lar kurulmayabilir — kendi keystore ile imzalayın.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    data class AppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable?,
        val isSystem: Boolean
    )

    private class AppAdapter(
        private val onLaunch: (AppItem) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {
        private var items: List<AppItem> = emptyList()

        fun submit(data: List<AppItem>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.label
            holder.packageName.text = item.packageName
            if (item.icon != null) holder.icon.setImageDrawable(item.icon)
            else holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.btnLaunch.setOnClickListener { onLaunch(item) }
            holder.itemView.setOnClickListener { onLaunch(item) }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val packageName: TextView = view.findViewById(R.id.packageName)
            val btnLaunch: com.google.android.material.button.MaterialButton =
                view.findViewById(R.id.btnLaunch)
        }
    }
}
