package com.apklab.app.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.apklab.app.model.ApkInfo
import java.io.File

object InstalledAppsScanner {

    fun listLaunchableApps(context: Context, query: String = ""): List<ApkInfo> {
        val pm = context.packageManager
        val q = query.trim().lowercase()
        val flags = PackageManager.GET_META_DATA
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }

        return apps.mapNotNull { info ->
            val apkPath = info.sourceDir ?: return@mapNotNull null
            val label = pm.getApplicationLabel(info).toString()
            val packageName = info.packageName
            if (q.isNotEmpty() &&
                !label.lowercase().contains(q) &&
                !packageName.lowercase().contains(q)
            ) {
                return@mapNotNull null
            }

            val pkgInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
            } catch (_: Exception) {
                null
            }

            val versionName = pkgInfo?.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                pkgInfo?.versionCode?.toLong() ?: 0L
            }

            ApkInfo(
                label = label,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                sourcePath = apkPath,
                sizeBytes = File(apkPath).length(),
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedBy { it.label.lowercase() }
    }
}
