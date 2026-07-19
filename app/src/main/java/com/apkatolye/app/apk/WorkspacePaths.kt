package com.apkatolye.app.apk

import android.content.Context
import java.io.File

object WorkspacePaths {

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "apk_atolye").also { it.mkdirs() }

    fun selectedApk(context: Context): File = File(root(context), "selected.apk")

    fun extractDir(context: Context): File = File(root(context), "extracted")

    fun rebuildApk(context: Context): File = File(root(context), "rebuilt-unsigned.apk")

    fun reportFile(context: Context, name: String): File = File(root(context), name)

    fun metaFile(context: Context): File = File(root(context), "selected.meta")
}
