package com.apkatolye.app.ai

import com.apkatolye.app.apk.WorkspacePaths
import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Low-level read/write helpers over the extracted APK workspace.
 */
class CodeOps(private val context: Context) {

    fun extractRoot(): File = WorkspacePaths.extractDir(context)

    fun resolve(path: String): File {
        val root = extractRoot()
        val cleaned = path.trim().removePrefix("/").removePrefix("./")
        val file = File(root, cleaned)
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.path.startsWith(canonicalRoot.path)) {
            "Çıkarılan klasör dışına yazılamaz"
        }
        return canonicalFile
    }

    fun readText(path: String, maxBytes: Long = 2_000_000): String {
        val file = resolve(path)
        require(file.exists()) { "Dosya yok: $path" }
        require(file.isFile) { "Klasör: $path" }
        require(file.length() <= maxBytes) { "Dosya çok büyük (${file.length()} bayt)" }
        return file.readText()
    }

    fun writeText(path: String, content: String): File {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    fun delete(path: String): Boolean {
        val file = resolve(path)
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    fun listEditable(limit: Int = 200): List<String> {
        val root = extractRoot()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && isEditable(it) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .take(limit)
            .toList()
    }

    fun findFiles(query: String, limit: Int = 40): List<String> {
        val root = extractRoot()
        val q = query.lowercase(Locale.ROOT)
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.lowercase(Locale.ROOT).contains(q) }
            .sorted()
            .take(limit)
            .toList()
    }

    fun findSmaliClass(className: String): File? {
        val root = extractRoot()
        val simple = className.substringAfterLast('.').substringAfterLast('/')
        val smaliPath = className
            .replace('.', '/')
            .removePrefix("L")
            .removeSuffix(";")
        val candidates = mutableListOf<File>()
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("smali", true) }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (
                    rel.contains(smaliPath, ignoreCase = true) ||
                    file.nameWithoutExtension.equals(simple, ignoreCase = true)
                ) {
                    candidates += file
                }
            }
        return candidates.minByOrNull { it.path.length }
    }

    fun findSmaliClasses(query: String, limit: Int = 30): List<String> {
        val root = extractRoot()
        val q = query.lowercase(Locale.ROOT)
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("smali", true) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.lowercase(Locale.ROOT).contains(q) }
            .sorted()
            .take(limit)
            .toList()
    }

    fun replaceInFile(path: String, old: String, new: String): Int {
        val text = readText(path)
        if (!text.contains(old)) return 0
        val count = text.split(old).size - 1
        writeText(path, text.replace(old, new))
        return count
    }

    fun replaceMethodBody(path: String, methodName: String, newBodyLines: List<String>): Boolean {
        val text = readText(path)
        val lines = text.lines().toMutableList()
        var start = -1
        var end = -1
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith(".method") && line.contains(methodName)) {
                start = i
                for (j in (i + 1) until lines.size) {
                    if (lines[j].trim() == ".end method") {
                        end = j
                        break
                    }
                }
                break
            }
        }
        if (start < 0 || end < 0) return false
        val header = lines[start]
        val replacement = mutableListOf(header)
        replacement += newBodyLines
        replacement += ".end method"
        val newLines = lines.subList(0, start) + replacement + lines.subList(end + 1, lines.size)
        writeText(path, newLines.joinToString("\n"))
        return true
    }

    fun workspaceSummary(maxFiles: Int = 80): String {
        val root = extractRoot()
        if (!root.exists()) return "Çıkarım yok"
        val files = listEditable(maxFiles)
        return buildString {
            appendLine("Kök: ${root.absolutePath}")
            appendLine("Düzenlenebilir örnek dosyalar (${files.size}):")
            files.take(40).forEach { appendLine(" - $it") }
            if (files.size > 40) appendLine(" …")
        }
    }

    fun isEditable(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in EDITABLE_EXT || file.name.equals("AndroidManifest.xml", true)
    }

    companion object {
        val EDITABLE_EXT = setOf(
            "smali", "xml", "json", "txt", "html", "htm", "css", "js", "kt", "java",
            "properties", "csv", "md", "yml", "yaml", "cfg", "ini", "log", "gradle"
        )
    }
}
