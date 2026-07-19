package com.apklab.app.core

import android.content.Context
import com.android.apksig.ApkSigner
import com.apklab.app.model.RebuildResult
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ApkRepacker {

    private const val ASSET_KEYSTORE = "apklab-debug.p12"
    private const val KEY_ALIAS = "apklab"
    private const val KEY_PASS = "apklab123"
    private const val STORE_PASS = "apklab123"

    fun rebuild(context: Context, extractDir: File, outName: String): RebuildResult {
        require(extractDir.exists()) { "Çalışma alanı yok" }

        val outputRoot = ApkExtractor.outputRoot(context)
        val safeName = outName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "rebuilt" }
        val unsigned = File(outputRoot, "$safeName-unsigned.apk")
        val signed = File(outputRoot, "$safeName-signed.apk")
        if (unsigned.exists()) unsigned.delete()
        if (signed.exists()) signed.delete()

        zipDirectory(extractDir, unsigned)
        signApk(context, unsigned, signed)

        return RebuildResult(
            unsignedApk = unsigned.absolutePath,
            signedApk = signed.absolutePath,
            message = "İmzalı APK hazır: ${signed.name}"
        )
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .filter { it.name != ".apklab.json" }
                .filter { !(it.parentFile?.name == "META-INF") }
                .forEach { file ->
                    val relative = file.absolutePath
                        .removePrefix(sourceDir.absolutePath)
                        .trimStart('/')
                    val entry = ZipEntry(relative)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
    }

    private fun signApk(context: Context, input: File, output: File) {
        val ks = loadKeystore(context)
        val privateKey = ks.getKey(KEY_ALIAS, KEY_PASS.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate

        val config = ApkSigner.SignerConfig.Builder("APKLAB", privateKey, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun loadKeystore(context: Context): KeyStore {
        val cached = File(context.filesDir, ASSET_KEYSTORE)
        if (!cached.exists()) {
            context.assets.open(ASSET_KEYSTORE).use { input ->
                FileOutputStream(cached).use { output -> input.copyTo(output) }
            }
        }
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(cached).use { ks.load(it, STORE_PASS.toCharArray()) }
        return ks
    }
}
