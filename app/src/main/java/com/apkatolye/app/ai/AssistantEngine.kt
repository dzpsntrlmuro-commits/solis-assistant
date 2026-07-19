package com.apkatolye.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import com.apkatolye.app.apk.ApkAnalyzer
import com.apkatolye.app.apk.ApkExtractor
import com.apkatolye.app.apk.ApkRepackager
import com.apkatolye.app.apk.WorkspacePaths
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.regex.Pattern

enum class PendingAction {
    NONE,
    WAITING_LOGO_IMAGE,
    WAITING_REPLACE_OLD,
    WAITING_REPLACE_NEW,
    WAITING_WRITE_PATH,
    WAITING_WRITE_CONTENT,
    WAITING_READ_PATH,
    WAITING_CLASS_QUERY,
    WAITING_REWRITE_TARGET,
    WAITING_REWRITE_CONTENT,
    WAITING_DELETE_PATH,
    WAITING_AI_INSTRUCTION
}

data class AssistantReply(
    val message: String,
    val pending: PendingAction = PendingAction.NONE,
    val openImagePicker: Boolean = false,
    val openFiles: Boolean = false,
    val openRebuild: Boolean = false,
    val openTest: Boolean = false,
    val askApiKey: Boolean = false
)

/**
 * Full-control assistant over extracted APK contents:
 * resources, text, smali code rewrite, create/delete files, optional Gemini AI.
 */
class AssistantEngine(private val context: Context) {

    var pending: PendingAction = PendingAction.NONE
        private set

    private var replaceOld: String? = null
    private var writePath: String? = null
    private var rewritePath: String? = null
    private val code = CodeOps(context)
    private val gemini = GeminiClient(context)

    fun setApiKey(key: String?) {
        gemini.setApiKey(key)
    }

    fun hasApiKey(): Boolean = gemini.hasKey()

    fun handle(userText: String): AssistantReply {
        val text = userText.trim()
        if (text.isEmpty()) {
            return AssistantReply(
                "Ne yapayım? Logo, ad, metin, smali kod yeniden yazma, dosya yaz/oku, yapay zekâ ile düzenleme…"
            )
        }

        // Multi-step flows first
        when (pending) {
            PendingAction.WAITING_REPLACE_OLD -> {
                replaceOld = text
                pending = PendingAction.WAITING_REPLACE_NEW
                return AssistantReply("«$text» yerine ne yazayım?", pending = PendingAction.WAITING_REPLACE_NEW)
            }
            PendingAction.WAITING_REPLACE_NEW -> {
                val old = replaceOld.orEmpty()
                pending = PendingAction.NONE
                replaceOld = null
                return replaceEverywhere(old, text)
            }
            PendingAction.WAITING_LOGO_IMAGE -> {
                return AssistantReply(
                    "Logo görseli bekleniyor — «Görsel seç»e bas.",
                    pending = PendingAction.WAITING_LOGO_IMAGE,
                    openImagePicker = true
                )
            }
            PendingAction.WAITING_WRITE_PATH -> {
                writePath = text.trim()
                pending = PendingAction.WAITING_WRITE_CONTENT
                return AssistantReply(
                    "Tamam, «${writePath}» dosyasına yazacağım.\nYeni kodun / metnin tamamını gönder.",
                    pending = PendingAction.WAITING_WRITE_CONTENT
                )
            }
            PendingAction.WAITING_WRITE_CONTENT -> {
                val path = writePath.orEmpty()
                pending = PendingAction.NONE
                writePath = null
                return writeFile(path, text)
            }
            PendingAction.WAITING_READ_PATH -> {
                pending = PendingAction.NONE
                return readFile(text.trim())
            }
            PendingAction.WAITING_CLASS_QUERY -> {
                pending = PendingAction.NONE
                return findClasses(text.trim())
            }
            PendingAction.WAITING_REWRITE_TARGET -> {
                return beginRewriteTarget(text.trim())
            }
            PendingAction.WAITING_REWRITE_CONTENT -> {
                val path = rewritePath.orEmpty()
                pending = PendingAction.NONE
                rewritePath = null
                return writeFile(path, text)
            }
            PendingAction.WAITING_DELETE_PATH -> {
                pending = PendingAction.NONE
                return deleteFile(text.trim())
            }
            PendingAction.WAITING_AI_INSTRUCTION -> {
                pending = PendingAction.NONE
                return runAiRewrite(text)
            }
            else -> Unit
        }

        // Block content format: DOSYA: path \n ... content
        parseExplicitWrite(text)?.let { return it }
        parseExplicitReplace(text)?.let { return it }

        val n = normalize(text)
        return when {
            matchesHelp(n) -> help()
            matchesApiKey(n) -> AssistantReply(
                "Gemini API anahtarını kaydetmek için ayar penceresini açıyorum. " +
                    "Ücretsiz anahtar: https://aistudio.google.com/apikey",
                askApiKey = true
            )
            matchesAi(n) -> startAi(text)
            matchesLogo(n) -> startLogoChange()
            matchesRename(n) -> renameApp(extractRenameTarget(text))
            matchesReplace(n) -> startReplace()
            matchesFind(n) -> findText(extractFindTarget(text))
            matchesWrite(n) -> startWrite(extractPathArg(text, listOf("yaz", "dosya yaz", "kod yaz")))
            matchesRead(n) -> startRead(extractPathArg(text, listOf("oku", "dosya oku", "kod oku", "göster")))
            matchesRewrite(n) -> startRewrite(extractRewriteTarget(text))
            matchesFindClass(n) -> startFindClass(extractClassQuery(text))
            matchesDelete(n) -> startDelete(extractPathArg(text, listOf("sil", "dosya sil")))
            matchesListCode(n) -> listCodeFiles()
            matchesExtract(n) -> extract()
            matchesRebuild(n) -> rebuild()
            matchesAnalyze(n) -> analyze()
            matchesOpenFiles(n) -> AssistantReply("Dosya gezgini açılıyor.", openFiles = true)
            matchesTest(n) -> AssistantReply("Test alanı açılıyor.", openTest = true)
            matchesListIcons(n) -> listIcons()
            matchesStatus(n) -> status()
            // Free-form: if API key exists, treat unknown as AI instruction
            gemini.hasKey() && text.length > 8 -> runAiRewrite(text)
            else -> AssistantReply(
                "Komutu tam anlayamadım.\n\n" +
                    "Kod için:\n" +
                    "• kodu yeniden yaz\n" +
                    "• dosya yaz smali/.../Foo.smali\n" +
                    "• sınıf bul MainActivity\n" +
                    "• yapay zekâ ile: giriş ekranını değiştir\n" +
                    "• api anahtarı (Gemini)\n\n" +
                    "«yardım» yazarak tüm komutları gör."
            )
        }
    }

    fun applyLogoFromUri(uri: Uri): AssistantReply {
        return try {
            ensureExtracted()
            val extractDir = WorkspacePaths.extractDir(context)
            val targets = findLauncherIcons(extractDir)
            if (targets.isEmpty()) {
                pending = PendingAction.NONE
                return AssistantReply("Launcher ikonu bulunamadı. Önce APK çıkar.")
            }
            val source = decodeBitmap(uri)
                ?: return AssistantReply("Görsel okunamadı.")
            var changed = 0
            targets.forEach { file ->
                val size = probeIconSize(file) ?: 192
                val scaled = Bitmap.createScaledBitmap(source, size, size, true)
                writeIcon(file, scaled)
                if (scaled != source) scaled.recycle()
                changed++
            }
            source.recycle()
            pending = PendingAction.NONE
            AssistantReply("Logo güncellendi ($changed dosya). «yeniden paketle» diyebilirsin.")
        } catch (e: Exception) {
            pending = PendingAction.NONE
            AssistantReply("Logo hatası: ${e.message}")
        }
    }

    // --- Explicit block parsers ---

    private fun parseExplicitWrite(text: String): AssistantReply? {
        val header = Regex(
            """^(?:DOSYA|FILE|YAZ|WRITE)\s*[:：]\s*(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        ).find(text) ?: return null
        val path = header.groupValues[1].trim()
        val content = text.substring(header.range.last + 1).trim()
            .removePrefix("```smali").removePrefix("```xml").removePrefix("```")
            .removeSuffix("```").trim()
        if (content.isEmpty()) {
            writePath = path
            pending = PendingAction.WAITING_WRITE_CONTENT
            return AssistantReply(
                "Yol alındı: $path\nŞimdi dosya içeriğini gönder.",
                pending = PendingAction.WAITING_WRITE_CONTENT
            )
        }
        return writeFile(path, content)
    }

    private fun parseExplicitReplace(text: String): AssistantReply? {
        val m = Regex(
            """^(?:DEGISTIR|DEĞİŞTİR|REPLACE)\s*[:：]\s*(.+?)\s*=>\s*(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text.trim()) ?: return null
        return replaceEverywhere(m.groupValues[1].trim(), m.groupValues[2].trim())
    }

    // --- Code ops ---

    private fun startWrite(path: String?): AssistantReply {
        ensureExtractedOrReply()?.let { return it }
        if (path.isNullOrBlank()) {
            pending = PendingAction.WAITING_WRITE_PATH
            return AssistantReply(
                "Hangi dosyaya yazayım? Örn: smali/com/example/MainActivity.smali",
                pending = PendingAction.WAITING_WRITE_PATH
            )
        }
        writePath = path
        pending = PendingAction.WAITING_WRITE_CONTENT
        return AssistantReply(
            "«$path» için yeni kodu / metni gönder (tüm dosya).",
            pending = PendingAction.WAITING_WRITE_CONTENT
        )
    }

    private fun writeFile(path: String, content: String): AssistantReply {
        return try {
            ensureExtracted()
            val file = code.writeText(path, content)
            AssistantReply(
                "Dosya yazıldı: ${file.relativeTo(code.extractRoot()).invariantSeparatorsPath}\n" +
                    "${content.lines().size} satır · ${content.toByteArray().size} bayt\n\n" +
                    "İstersen «yeniden paketle»."
            )
        } catch (e: Exception) {
            AssistantReply("Yazma hatası: ${e.message}")
        }
    }

    private fun startRead(path: String?): AssistantReply {
        ensureExtractedOrReply()?.let { return it }
        if (path.isNullOrBlank()) {
            pending = PendingAction.WAITING_READ_PATH
            return AssistantReply("Hangi dosyayı okuyayım?", pending = PendingAction.WAITING_READ_PATH)
        }
        return readFile(path)
    }

    private fun readFile(path: String): AssistantReply {
        return try {
            ensureExtracted()
            val content = code.readText(path, maxBytes = 80_000)
            val shown = if (content.length > 6_000) content.take(6_000) + "\n\n…(kısaltıldı, dosya gezgininden tamını düzenleyebilirsin)" else content
            AssistantReply("📄 $path\n\n$shown")
        } catch (e: Exception) {
            AssistantReply("Okuma hatası: ${e.message}")
        }
    }

    private fun startRewrite(target: String?): AssistantReply {
        ensureExtractedOrReply()?.let { return it }
        if (target.isNullOrBlank()) {
            pending = PendingAction.WAITING_REWRITE_TARGET
            return AssistantReply(
                "Hangi sınıfı / dosyayı baştan yazayım?\nÖrn: MainActivity veya smali/.../Foo.smali",
                pending = PendingAction.WAITING_REWRITE_TARGET
            )
        }
        return beginRewriteTarget(target)
    }

    private fun beginRewriteTarget(target: String): AssistantReply {
        return try {
            ensureExtracted()
            val file = if (target.contains("/") || target.endsWith(".smali", true) || target.endsWith(".xml", true)) {
                code.resolve(target).takeIf { it.exists() }
            } else {
                code.findSmaliClass(target)
            }
            if (file == null || !file.exists()) {
                val suggestions = code.findSmaliClasses(target.substringAfterLast('.'), 8)
                pending = PendingAction.WAITING_REWRITE_TARGET
                return AssistantReply(
                    "Bulamadım: $target\n" +
                        if (suggestions.isEmpty()) "Önce «sınıf bul …» dene."
                        else "Benzerler:\n" + suggestions.joinToString("\n") { "• $it" },
                    pending = PendingAction.WAITING_REWRITE_TARGET
                )
            }
            val rel = file.relativeTo(code.extractRoot()).invariantSeparatorsPath
            rewritePath = rel
            pending = PendingAction.WAITING_REWRITE_CONTENT
            val preview = code.readText(rel, maxBytes = 40_000).lines().take(25).joinToString("\n")
            AssistantReply(
                "Hedef: $rel\nMevcut ilk satırlar:\n$preview\n\n" +
                    "Şimdi dosyanın YENİ tam içeriğini gönder.\n" +
                    "Ya da «yapay zekâ ile: bu sınıfı … yap» de (API anahtarı gerekir).",
                pending = PendingAction.WAITING_REWRITE_CONTENT
            )
        } catch (e: Exception) {
            AssistantReply("Hata: ${e.message}")
        }
    }

    private fun startFindClass(query: String?): AssistantReply {
        ensureExtractedOrReply()?.let { return it }
        if (query.isNullOrBlank()) {
            pending = PendingAction.WAITING_CLASS_QUERY
            return AssistantReply("Hangi sınıfı arayayım?", pending = PendingAction.WAITING_CLASS_QUERY)
        }
        return findClasses(query)
    }

    private fun findClasses(query: String): AssistantReply {
        return try {
            ensureExtracted()
            val hits = code.findSmaliClasses(query, 40)
            if (hits.isEmpty()) AssistantReply("Sınıf bulunamadı: $query")
            else AssistantReply(
                "Bulunan smali (${hits.size}):\n" +
                    hits.joinToString("\n") { "• $it" } +
                    "\n\nYeniden yazmak için: «kodu yeniden yaz ${hits.first()}»"
            )
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Hata")
        }
    }

    private fun startDelete(path: String?): AssistantReply {
        ensureExtractedOrReply()?.let { return it }
        if (path.isNullOrBlank()) {
            pending = PendingAction.WAITING_DELETE_PATH
            return AssistantReply("Silinecek dosya yolu?", pending = PendingAction.WAITING_DELETE_PATH)
        }
        return deleteFile(path)
    }

    private fun deleteFile(path: String): AssistantReply {
        return try {
            ensureExtracted()
            val ok = code.delete(path)
            if (ok) AssistantReply("Silindi: $path")
            else AssistantReply("Silinemedi / yok: $path")
        } catch (e: Exception) {
            AssistantReply("Silme hatası: ${e.message}")
        }
    }

    private fun listCodeFiles(): AssistantReply {
        return try {
            ensureExtracted()
            val files = code.listEditable(120)
            AssistantReply(
                "Düzenlenebilir dosyalar (${files.size}):\n" +
                    files.take(60).joinToString("\n") { "• $it" } +
                    if (files.size > 60) "\n…" else ""
            )
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Hata")
        }
    }

    private fun startAi(raw: String): AssistantReply {
        if (!gemini.hasKey()) {
            return AssistantReply(
                "Yapay zekâ ile serbest kod yazımı için Gemini API anahtarı gerekli.\n" +
                    "«api anahtarı» de ve anahtarını kaydet.\n" +
                    "Anahtarsız da «dosya yaz» / «kodu yeniden yaz» ile doğrudan kod yapıştırabilirsin.",
                askApiKey = true
            )
        }
        val instruction = raw
            .replace(Regex("""^(yapay\s*zeka|yapay\s*zekâ|ai|gemini)\s*(ile|:)?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { raw }
        return runAiRewrite(instruction)
    }

    private fun runAiRewrite(instruction: String): AssistantReply {
        if (!gemini.hasKey()) {
            pending = PendingAction.WAITING_AI_INSTRUCTION
            return AssistantReply(
                "API anahtarı yok. Kaydetmemi ister misin? Sonra talimatı tekrar gönder.",
                pending = PendingAction.WAITING_AI_INSTRUCTION,
                askApiKey = true
            )
        }
        return try {
            ensureExtracted()
            val summary = code.workspaceSummary(100)
            // Heuristic: gather focus files from keywords in instruction
            val focus = mutableMapOf<String, String>()
            val tokens = instruction.split(Regex("\\s+")).filter { it.length > 3 }.take(8)
            tokens.forEach { token ->
                code.findFiles(token, 3).forEach { path ->
                    if (focus.size < 5 && code.isEditable(code.resolve(path))) {
                        runCatching { focus[path] = code.readText(path, 30_000) }
                    }
                }
                code.findSmaliClasses(token, 2).forEach { path ->
                    if (focus.size < 5) {
                        runCatching { focus[path] = code.readText(path, 30_000) }
                    }
                }
            }
            if (focus.isEmpty()) {
                code.listEditable(5).forEach { path ->
                    runCatching { focus[path] = code.readText(path, 20_000) }
                }
            }

            val (message, patches) = gemini.planPatches(instruction, summary, focus)
            if (patches.isEmpty()) {
                return AssistantReply("YZ yanıt verdi ama yama yok:\n$message")
            }
            val report = applyPatches(patches)
            AssistantReply("$message\n\nUygulanan değişiklikler:\n$report\n\n«yeniden paketle» diyerek APK üret.")
        } catch (e: Exception) {
            AssistantReply("YZ hatası: ${e.message}")
        }
    }

    private fun applyPatches(patches: List<CodePatch>): String = buildString {
        patches.forEachIndexed { index, patch ->
            try {
                when (patch.action.lowercase(Locale.ROOT)) {
                    "write" -> {
                        val content = patch.content ?: error("content yok")
                        code.writeText(patch.path, content)
                        appendLine("${index + 1}. yazıldı: ${patch.path} (${content.lines().size} satır)")
                    }
                    "replace" -> {
                        val old = patch.old ?: error("old yok")
                        val new = patch.new ?: error("new yok")
                        val count = code.replaceInFile(patch.path, old, new)
                        appendLine("${index + 1}. replace: ${patch.path} ($count)")
                    }
                    "delete" -> {
                        code.delete(patch.path)
                        appendLine("${index + 1}. silindi: ${patch.path}")
                    }
                    else -> appendLine("${index + 1}. bilinmeyen action: ${patch.action}")
                }
            } catch (e: Exception) {
                appendLine("${index + 1}. HATA ${patch.path}: ${e.message}")
            }
        }
    }

    private fun replaceEverywhere(old: String, new: String): AssistantReply {
        if (old.isBlank()) return AssistantReply("Eski metin boş olamaz.")
        return try {
            ensureExtracted()
            val root = code.extractRoot()
            var filesChanged = 0
            var replacements = 0
            root.walkTopDown()
                .filter { it.isFile && code.isEditable(it) && it.length() < 2_000_000 }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if (!text.contains(old)) return@forEach
                    val count = text.split(old).size - 1
                    file.writeText(text.replace(old, new))
                    filesChanged++
                    replacements += count
                }
            AssistantReply(
                if (filesChanged == 0) "«$old» bulunamadı."
                else "«$old» → «$new» · $filesChanged dosya · $replacements değişiklik"
            )
        } catch (e: Exception) {
            AssistantReply("Değiştirme hatası: ${e.message}")
        }
    }

    private fun startLogoChange(): AssistantReply {
        if (!WorkspacePaths.selectedApk(context).exists()) {
            return AssistantReply("Önce APK seç.")
        }
        pending = PendingAction.WAITING_LOGO_IMAGE
        return AssistantReply(
            "Yeni logoyu seç.",
            pending = PendingAction.WAITING_LOGO_IMAGE,
            openImagePicker = true
        )
    }

    private fun renameApp(newName: String?): AssistantReply {
        if (newName.isNullOrBlank()) return AssistantReply("Örn: «adını SuperApp yap»")
        return try {
            ensureExtracted()
            val stringFiles = code.extractRoot().walkTopDown()
                .filter { it.isFile && it.name == "strings.xml" }
                .toList()
            if (stringFiles.isEmpty()) return AssistantReply("strings.xml yok (binary resources olabilir).")
            var hits = 0
            stringFiles.forEach { file ->
                val original = file.readText()
                val updated = replaceAppNameInStrings(original, newName)
                if (updated != original) {
                    file.writeText(updated)
                    hits++
                }
            }
            if (hits == 0) {
                val primary = stringFiles.first()
                val original = primary.readText()
                primary.writeText(
                    if (original.contains("name=\"app_name\"")) replaceAppNameInStrings(original, newName)
                    else original.replace(
                        "</resources>",
                        "    <string name=\"app_name\">${escapeXml(newName)}</string>\n</resources>"
                    )
                )
                hits = 1
            }
            AssistantReply("Ad «$newName» yapıldı ($hits dosya).")
        } catch (e: Exception) {
            AssistantReply("Ad hatası: ${e.message}")
        }
    }

    private fun startReplace(): AssistantReply {
        pending = PendingAction.WAITING_REPLACE_OLD
        return AssistantReply("Eski metni yaz.", pending = PendingAction.WAITING_REPLACE_OLD)
    }

    private fun findText(query: String?): AssistantReply {
        if (query.isNullOrBlank()) return AssistantReply("Örn: «bul app_name»")
        return try {
            ensureExtracted()
            val root = code.extractRoot()
            val hits = mutableListOf<String>()
            root.walkTopDown()
                .filter { it.isFile && code.isEditable(it) && it.length() < 1_000_000 }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if (text.contains(query, ignoreCase = true)) {
                        hits += file.relativeTo(root).invariantSeparatorsPath
                    }
                    if (hits.size >= 30) return@forEach
                }
            if (hits.isEmpty()) AssistantReply("«$query» yok.")
            else AssistantReply("Eşleşmeler:\n" + hits.joinToString("\n") { "• $it" })
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Hata")
        }
    }

    private fun extract(): AssistantReply {
        return try {
            val apk = WorkspacePaths.selectedApk(context)
            if (!apk.exists()) return AssistantReply("Önce APK seç.")
            val result = ApkExtractor.extract(apk, WorkspacePaths.extractDir(context))
            AssistantReply("Çıkarıldı: ${result.fileCount} dosya.", openFiles = true)
        } catch (e: Exception) {
            AssistantReply("Çıkarma: ${e.message}")
        }
    }

    private fun rebuild(): AssistantReply {
        return try {
            ensureExtracted()
            val out = WorkspacePaths.rebuildApk(context)
            val result = ApkRepackager.rebuild(code.extractRoot(), out)
            AssistantReply(
                "Paketlendi: ${result.outputApk.absolutePath}\n${ApkAnalyzer.formatSize(result.sizeBytes)}",
                openTest = true
            )
        } catch (e: Exception) {
            AssistantReply("Paketleme: ${e.message}")
        }
    }

    private fun analyze(): AssistantReply {
        return try {
            val apk = WorkspacePaths.selectedApk(context)
            if (!apk.exists()) return AssistantReply("Önce APK seç.")
            val info = ApkAnalyzer.analyze(context, apk)
            AssistantReply(
                "Paket: ${info.packageName}\nSürüm: ${info.versionName}\n" +
                    "DEX: ${info.dexFiles.size} · Dosya: ${info.entries.size} · İzin: ${info.permissions.size}"
            )
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Hata")
        }
    }

    private fun listIcons(): AssistantReply {
        return try {
            ensureExtracted()
            val icons = findLauncherIcons(code.extractRoot())
            if (icons.isEmpty()) AssistantReply("İkon yok.")
            else AssistantReply(icons.joinToString("\n") {
                "• ${it.relativeTo(code.extractRoot()).path}"
            })
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Hata")
        }
    }

    private fun status(): AssistantReply {
        val apk = WorkspacePaths.selectedApk(context)
        val extracted = WorkspacePaths.extractDir(context)
        val rebuilt = WorkspacePaths.rebuildApk(context)
        return AssistantReply(
            "APK: ${if (apk.exists()) ApkAnalyzer.formatSize(apk.length()) else "yok"}\n" +
                "Çıkarım: ${if (extracted.exists()) "var" else "yok"}\n" +
                "Paket: ${if (rebuilt.exists()) ApkAnalyzer.formatSize(rebuilt.length()) else "yok"}\n" +
                "YZ anahtarı: ${if (gemini.hasKey()) "kayıtlı" else "yok"}\n" +
                "Bekleyen: $pending"
        )
    }

    private fun help(): AssistantReply = AssistantReply(
        "Tam kontrol komutları:\n\n" +
            "KOD\n" +
            "• kodu yeniden yaz MainActivity\n" +
            "• dosya yaz smali/.../Foo.smali\n" +
            "• dosya oku …\n" +
            "• sınıf bul Login\n" +
            "• dosya sil …\n" +
            "• kod listesi\n" +
            "• DOSYA: path  + alt satırda içerik\n\n" +
            "YAPAY ZEKÂ\n" +
            "• api anahtarı\n" +
            "• yapay zekâ ile: splash ekranını kaldır\n" +
            "(Anahtar varsa serbest cümle de çalışır)\n\n" +
            "KAYNAK\n" +
            "• logoyu değiştir · adını X yap · metin değiştir\n" +
            "• içini çıkar · yeniden paketle · test et\n\n" +
            "Yalnızca kendi APK’larında kullan."
    )

    private fun ensureExtractedOrReply(): AssistantReply? {
        return try {
            ensureExtracted()
            null
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Önce APK seç / çıkar")
        }
    }

    private fun ensureExtracted() {
        val apk = WorkspacePaths.selectedApk(context)
        require(apk.exists()) { "Önce APK seç" }
        val dir = WorkspacePaths.extractDir(context)
        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            ApkExtractor.extract(apk, dir)
        }
    }

    private fun findLauncherIcons(root: File): List<File> {
        val hints = listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground", "app_icon", "ic_app")
        return root.walkTopDown()
            .filter { it.isFile }
            .filter {
                val n = it.name.lowercase(Locale.ROOT)
                val inRes = it.path.contains("/res/")
                val isImg = n.endsWith(".png") || n.endsWith(".webp")
                inRes && isImg && hints.any { h -> n.contains(h) }
            }
            .sortedBy { it.path }
            .toList()
    }

    private fun decodeBitmap(uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    private fun probeIconSize(file: File): Int? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.takeIf { it > 0 }
    }

    private fun writeIcon(file: File, bitmap: Bitmap) {
        val format = if (file.name.endsWith(".webp", true)) Bitmap.CompressFormat.WEBP
        else Bitmap.CompressFormat.PNG
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        FileOutputStream(file).use { out.compress(format, 100, it) }
        out.recycle()
    }

    private fun replaceAppNameInStrings(xml: String, newName: String): String {
        val escaped = escapeXml(newName)
        var result = xml
        listOf(
            Pattern.compile("""(<string\s+name="app_name"\s*>)(.*?)(</string>)""", Pattern.DOTALL),
            Pattern.compile("""(<string\s+name="application_name"\s*>)(.*?)(</string>)""", Pattern.DOTALL)
        ).forEach { p ->
            val m = p.matcher(result)
            if (m.find()) result = m.replaceAll("$1$escaped$3")
        }
        return result
    }

    private fun escapeXml(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun normalize(s: String): String =
        s.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .replace("â", "a").replace("î", "i").replace("û", "u")

    private fun matchesHelp(n: String) = n.contains("yardim") || n == "help"
    private fun matchesApiKey(n: String) =
        n.contains("api") && (n.contains("anahtar") || n.contains("key") || n.contains("gemini"))
    private fun matchesAi(n: String) =
        n.contains("yapay zeka") || n.startsWith("ai ") || n.startsWith("gemini") ||
            n.contains("yz ile") || n.contains("yapayzeka") || n.contains("zeka ile")
    private fun matchesLogo(n: String) =
        (n.contains("logo") || n.contains("ikon") || n.contains("icon") || n.contains("simge")) &&
            (n.contains("degistir") || n.contains("yap") || n.contains("guncelle") || n.contains("ayarla"))
    private fun matchesRename(n: String) =
        (n.contains("adini") || n.contains("ismi") || n.contains("adi ")) &&
            (n.contains("yap") || n.contains("degistir") || n.contains("olsun"))
    private fun matchesReplace(n: String) =
        n.contains("metin degistir") || n.contains("yazi degistir") || n.contains("bul ve degistir")
    private fun matchesFind(n: String) = n.startsWith("bul ") || n.startsWith("ara ")
    private fun matchesWrite(n: String) =
        n.startsWith("dosya yaz") || n.startsWith("kod yaz") || n == "yaz" || n.startsWith("yaz ")
    private fun matchesRead(n: String) =
        n.startsWith("dosya oku") || n.startsWith("kod oku") || n.startsWith("oku ") || n.startsWith("goster ")
    private fun matchesRewrite(n: String) =
        n.contains("yeniden yaz") || n.contains("kodu degistir") || n.contains("kodu yaz") ||
            n.contains("sinifi yaz") || n.contains("basdan yaz") || n.contains("bastan yaz") ||
            n.contains("kodu tekrar") || n.contains("direkt yaz") || n.contains("direk yaz")
    private fun matchesFindClass(n: String) =
        n.contains("sinif bul") || n.contains("class bul") || n.startsWith("sinif ")
    private fun matchesDelete(n: String) = n.startsWith("dosya sil") || n.startsWith("sil ")
    private fun matchesListCode(n: String) =
        n.contains("kod list") || n.contains("dosya list") || n == "liste" || n.contains("dosyalari goster")
    private fun matchesExtract(n: String) = n.contains("cikar") || n.contains("extract")
    private fun matchesRebuild(n: String) =
        n.contains("paketle") || n.contains("rebuild") || n.contains("apk yap")
    private fun matchesAnalyze(n: String) = n.contains("analiz") || n.contains("incele")
    private fun matchesOpenFiles(n: String) = n.contains("dosya") && (n.contains("ac") || n.contains("gez"))
    private fun matchesTest(n: String) = n.contains("test") || n.contains("calistir")
    private fun matchesListIcons(n: String) = n.contains("ikonlari listele") || n.contains("ikon list")
    private fun matchesStatus(n: String) = n.contains("durum") || n == "status"

    private fun extractRenameTarget(raw: String): String? {
        listOf(
            Regex("""adını\s+(.+?)\s+yap""", RegexOption.IGNORE_CASE),
            Regex("""adini\s+(.+?)\s+yap""", RegexOption.IGNORE_CASE),
            Regex("""ismi\s+(.+?)\s+yap""", RegexOption.IGNORE_CASE),
            Regex("""adını\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""adini\s+(.+)$""", RegexOption.IGNORE_CASE)
        ).forEach { p -> p.find(raw)?.let { return it.groupValues[1].trim().trim('"', '\'') } }
        return null
    }

    private fun extractFindTarget(raw: String): String? =
        Regex("""^(bul|ara)\s+(.+)$""", RegexOption.IGNORE_CASE).find(raw.trim())
            ?.groupValues?.getOrNull(2)?.trim()

    private fun extractPathArg(raw: String, prefixes: List<String>): String? {
        val t = raw.trim()
        prefixes.forEach { p ->
            if (t.startsWith(p, ignoreCase = true)) {
                return t.substring(p.length).trim().ifBlank { null }
            }
        }
        return null
    }

    private fun extractRewriteTarget(raw: String): String? {
        listOf(
            Regex("""yeniden yaz\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""kodu yeniden yaz\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""kodu degistir\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""bastan yaz\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""baştan yaz\s+(.+)$""", RegexOption.IGNORE_CASE)
        ).forEach { p -> p.find(raw)?.let { return it.groupValues[1].trim() } }
        return null
    }

    private fun extractClassQuery(raw: String): String? {
        listOf(
            Regex("""sınıf bul\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""sinif bul\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""class bul\s+(.+)$""", RegexOption.IGNORE_CASE)
        ).forEach { p -> p.find(raw)?.let { return it.groupValues[1].trim() } }
        return null
    }
}
