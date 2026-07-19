package com.apklab.app.plugin

import com.apklab.app.model.PluginResult
import java.io.File

/**
 * Analysis plugin contract. Built-in and external plugins implement this
 * to inspect an extracted APK workspace and emit reports.
 */
interface ApkPlugin {
    val id: String
    val name: String
    val description: String
    val enabledByDefault: Boolean get() = true

    fun analyze(context: PluginContext): PluginResult
}

data class PluginContext(
    val extractDir: File,
    val packageName: String,
    val label: String,
    val reportDir: File
)
