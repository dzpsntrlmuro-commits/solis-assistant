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
    WAITING_REPLACE_NEW
}

data class AssistantReply(
    val message: String,
    val pending: PendingAction = PendingAction.NONE,
    val openImagePicker: Boolean = false,
    val openFiles: Boolean = false,
    val openRebuild: Boolean = false,
    val openTest: Boolean = false
)

/**
 * On-device Turkish command assistant that edits the extracted APK workspace.
 * Acts as the user's "hands" for common APK resource edits.
 */
class AssistantEngine(private val context: Context) {

    var pending: PendingAction = PendingAction.NONE
        private set
    private var replaceOld: String? = null

    fun handle(userText: String): AssistantReply {
        val text = userText.trim()
        if (text.isEmpty()) {
            return AssistantReply("Ne yapmamı istersin? Örn: «logoyu değiştir», «adını X yap»")
        }

        // Multi-step flows
        when (pending) {
            PendingAction.WAITING_REPLACE_OLD -> {
                replaceOld = text
                pending = PendingAction.WAITING_REPLACE_NEW
                return AssistantReply(
                    "Tamam. «$text» yerine ne yazayım?",
                    pending = PendingAction.WAITING_REPLACE_NEW
                )
            }
            PendingAction.WAITING_REPLACE_NEW -> {
                val old = replaceOld.orEmpty()
                pending = PendingAction.NONE
                replaceOld = null
                return replaceInResources(old, text)
            }
            PendingAction.WAITING_LOGO_IMAGE -> {
                return AssistantReply(
                    "Logo için hâlâ yeni görsel bekliyorum. Aşağıdaki «Görsel seç» ile devam et.",
                    pending = PendingAction.WAITING_LOGO_IMAGE,
                    openImagePicker = true
                )
            }
            else -> Unit
        }

        val n = normalize(text)

        return when {
            matchesHelp(n) -> help()
            matchesLogo(n) -> startLogoChange()
            matchesRename(n) -> renameApp(extractRenameTarget(text))
            matchesReplace(n) -> startReplace()
            matchesFind(n) -> findText(extractFindTarget(text))
            matchesExtract(n) -> extract()
            matchesRebuild(n) -> rebuild()
            matchesAnalyze(n) -> analyze()
            matchesOpenFiles(n) -> AssistantReply(
                "Dosya gezginini açıyorum. Oradan da elle düzenleyebilirsin.",
                openFiles = true
            )
            matchesTest(n) -> AssistantReply(
                "Test alanını açıyorum. Uygulamayı oradan çalıştırabilirsin.",
                openTest = true
            )
            matchesListIcons(n) -> listIcons()
            matchesStatus(n) -> status()
            else -> AssistantReply(
                "Anladım ama bu komutu henüz bilmiyorum.\n\n" +
                    "Şunları deneyebilirsin:\n" +
                    "• logoyu değiştir\n" +
                    "• adını SuperApp yap\n" +
                    "• metin değiştir\n" +
                    "• içini çıkar / yeniden paketle\n" +
                    "• yardım"
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
                return AssistantReply(
                    "Launcher ikonu bulamadım. Önce APK seçip içeriği çıkar. " +
                        "Sonra res/mipmap-* veya res/drawable içinde ic_launcher dosyaları olmalı."
                )
            }

            val source = decodeBitmap(uri)
                ?: return AssistantReply("Görsel okunamadı. PNG veya JPG dene.")

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

            AssistantReply(
                "Logo güncellendi. $changed ikon dosyası değiştirildi.\n\n" +
                    "Sonraki adım: «yeniden paketle» de, sonra Test alanından kurmayı dene.\n" +
                    "(İmzasız APK için kendi keystore’un gerekebilir.)"
            )
        } catch (e: Exception) {
            pending = PendingAction.NONE
            AssistantReply("Logo değiştirirken hata: ${e.message}")
        }
    }

    private fun startLogoChange(): AssistantReply {
        if (!WorkspacePaths.selectedApk(context).exists()) {
            return AssistantReply("Önce bir APK seç. Ana ekrandan APK seç, sonra tekrar söyle.")
        }
        pending = PendingAction.WAITING_LOGO_IMAGE
        return AssistantReply(
            "Tamam, logoyu değiştireceğim. Yeni görseli seç — ben çıkarılan APK içindeki tüm launcher ikonlarını güncelleyeceğim.",
            pending = PendingAction.WAITING_LOGO_IMAGE,
            openImagePicker = true
        )
    }

    private fun renameApp(newName: String?): AssistantReply {
        if (newName.isNullOrBlank()) {
            return AssistantReply("Yeni adı net söyle. Örn: «adını Gece Modu yap»")
        }
        return try {
            ensureExtracted()
            val extractDir = WorkspacePaths.extractDir(context)
            val stringFiles = extractDir.walkTopDown()
                .filter { it.isFile && it.name == "strings.xml" }
                .toList()

            if (stringFiles.isEmpty()) {
                return AssistantReply(
                    "strings.xml bulunamadı. Bazı APK’larda isim binary resources.arsc içinde olur; " +
                        "bu sürümde düz XML ad değiştirmeyi destekliyorum."
                )
            }

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
                // Fallback: inject/replace any app_name-like entry loosely
                val primary = stringFiles.first()
                val original = primary.readText()
                val injected = if (original.contains("name=\"app_name\"")) {
                    replaceAppNameInStrings(original, newName)
                } else {
                    original.replace(
                        "</resources>",
                        "    <string name=\"app_name\">${escapeXml(newName)}</string>\n</resources>"
                    )
                }
                primary.writeText(injected)
                hits = 1
            }

            AssistantReply(
                "Uygulama adını «$newName» yaptım ($hits strings.xml dosyası).\n" +
                    "Değişikliğin görünmesi için «yeniden paketle» demen yeterli."
            )
        } catch (e: Exception) {
            AssistantReply("Ad değiştirirken hata: ${e.message}")
        }
    }

    private fun startReplace(): AssistantReply {
        pending = PendingAction.WAITING_REPLACE_OLD
        return AssistantReply(
            "Hangi metni değiştireyim? Eski metni yaz.",
            pending = PendingAction.WAITING_REPLACE_OLD
        )
    }

    private fun replaceInResources(old: String, new: String): AssistantReply {
        if (old.isBlank()) return AssistantReply("Eski metin boş olamaz.")
        return try {
            ensureExtracted()
            val extractDir = WorkspacePaths.extractDir(context)
            val editable = listOf("xml", "json", "txt", "html", "htm", "css", "js", "properties", "smali", "csv")
            var filesChanged = 0
            var replacements = 0

            extractDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in editable }
                .filter { it.length() < 2_000_000 }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if (!text.contains(old)) return@forEach
                    val count = text.split(old).size - 1
                    file.writeText(text.replace(old, new))
                    filesChanged++
                    replacements += count
                }

            AssistantReply(
                if (filesChanged == 0) "«$old» hiçbir düzenlenebilir dosyada bulunamadı."
                else "«$old» → «$new» yapıldı. $filesChanged dosyada $replacements değişiklik."
            )
        } catch (e: Exception) {
            AssistantReply("Metin değiştirirken hata: ${e.message}")
        }
    }

    private fun findText(query: String?): AssistantReply {
        if (query.isNullOrBlank()) return AssistantReply("Ne arayayım? Örn: «bul app_name»")
        return try {
            ensureExtracted()
            val extractDir = WorkspacePaths.extractDir(context)
            val hits = mutableListOf<String>()
            extractDir.walkTopDown()
                .filter { it.isFile && it.length() < 1_000_000 }
                .filter {
                    it.extension.lowercase(Locale.ROOT) in setOf(
                        "xml", "json", "txt", "html", "properties", "smali", "csv"
                    )
                }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if (text.contains(query, ignoreCase = true)) {
                        hits += file.relativeTo(extractDir).path
                    }
                    if (hits.size >= 25) return@forEach
                }
            if (hits.isEmpty()) AssistantReply("«$query» bulunamadı.")
            else AssistantReply("«$query» geçen dosyalar (${hits.size}):\n" + hits.joinToString("\n") { "• $it" })
        } catch (e: Exception) {
            AssistantReply("Arama hatası: ${e.message}")
        }
    }

    private fun extract(): AssistantReply {
        return try {
            val apk = WorkspacePaths.selectedApk(context)
            if (!apk.exists()) return AssistantReply("Önce APK seç.")
            val result = ApkExtractor.extract(apk, WorkspacePaths.extractDir(context))
            AssistantReply(
                "İçerik çıkarıldı: ${result.fileCount} dosya.\n${result.outputDir.absolutePath}\n\n" +
                    "Şimdi «logoyu değiştir» veya «adını … yap» diyebilirsin.",
                openFiles = true
            )
        } catch (e: Exception) {
            AssistantReply("Çıkarma hatası: ${e.message}")
        }
    }

    private fun rebuild(): AssistantReply {
        return try {
            ensureExtracted()
            val out = WorkspacePaths.rebuildApk(context)
            val result = ApkRepackager.rebuild(WorkspacePaths.extractDir(context), out)
            AssistantReply(
                "Yeniden paketledim.\nDosya: ${result.outputApk.absolutePath}\n" +
                    "Boyut: ${ApkAnalyzer.formatSize(result.sizeBytes)}\n\n" +
                    "Test alanından kurmayı deneyebilirsin.",
                openTest = true
            )
        } catch (e: Exception) {
            AssistantReply("Paketleme hatası: ${e.message}")
        }
    }

    private fun analyze(): AssistantReply {
        return try {
            val apk = WorkspacePaths.selectedApk(context)
            if (!apk.exists()) return AssistantReply("Önce APK seç.")
            val info = ApkAnalyzer.analyze(context, apk)
            AssistantReply(
                "Paket: ${info.packageName}\n" +
                    "Sürüm: ${info.versionName} (${info.versionCode})\n" +
                    "DEX: ${info.dexFiles.size}\n" +
                    "Dosya: ${info.entries.size}\n" +
                    "İzin: ${info.permissions.size}\n" +
                    "Activity: ${info.activities.size}"
            )
        } catch (e: Exception) {
            AssistantReply("Analiz hatası: ${e.message}")
        }
    }

    private fun listIcons(): AssistantReply {
        return try {
            ensureExtracted()
            val icons = findLauncherIcons(WorkspacePaths.extractDir(context))
            if (icons.isEmpty()) AssistantReply("Launcher ikonu yok / içerik çıkarılmamış.")
            else AssistantReply(
                "Bulunan ikonlar (${icons.size}):\n" +
                    icons.joinToString("\n") {
                        "• ${it.relativeTo(WorkspacePaths.extractDir(context)).path} (${ApkAnalyzer.formatSize(it.length())})"
                    }
            )
        } catch (e: Exception) {
            AssistantReply(e.message ?: "Hata")
        }
    }

    private fun status(): AssistantReply {
        val apk = WorkspacePaths.selectedApk(context)
        val extracted = WorkspacePaths.extractDir(context)
        val rebuilt = WorkspacePaths.rebuildApk(context)
        return AssistantReply(
            buildString {
                appendLine("Durum:")
                appendLine("• APK: ${if (apk.exists()) apk.name + " / " + ApkAnalyzer.formatSize(apk.length()) else "yok"}")
                appendLine("• Çıkarım: ${if (extracted.exists()) "var" else "yok"}")
                appendLine("• Yeniden paket: ${if (rebuilt.exists()) ApkAnalyzer.formatSize(rebuilt.length()) else "yok"}")
                appendLine("• Bekleyen iş: $pending")
            }
        )
    }

    private fun help(): AssistantReply = AssistantReply(
        "Ben senin elin-kolunum. Çıkarılan APK üzerinde komutla çalışırım.\n\n" +
            "Örnekler:\n" +
            "• logoyu değiştir / ikonu değiştir\n" +
            "• adını Gece Feneri yap\n" +
            "• metin değiştir\n" +
            "• bul app_name\n" +
            "• içini çıkar\n" +
            "• yeniden paketle\n" +
            "• ikonları listele\n" +
            "• dosyaları aç / test et / durum\n\n" +
            "Yalnızca kendi uygulamalarında kullan."
    )

    private fun ensureExtracted() {
        val apk = WorkspacePaths.selectedApk(context)
        require(apk.exists()) { "Önce APK seç" }
        val dir = WorkspacePaths.extractDir(context)
        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            ApkExtractor.extract(apk, dir)
        }
    }

    private fun findLauncherIcons(root: File): List<File> {
        val nameHints = listOf(
            "ic_launcher", "ic_launcher_round", "ic_launcher_foreground",
            "ic_launcher_background", "icon", "app_icon", "ic_app"
        )
        return root.walkTopDown()
            .filter { it.isFile }
            .filter {
                val n = it.name.lowercase(Locale.ROOT)
                val inRes = it.path.contains("/res/") || it.path.contains("\\res\\")
                val isImg = n.endsWith(".png") || n.endsWith(".webp")
                inRes && isImg && nameHints.any { hint -> n.contains(hint) }
            }
            .sortedBy { it.path }
            .toList()
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    private fun probeIconSize(file: File): Int? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth
        return if (w > 0) w else null
    }

    private fun writeIcon(file: File, bitmap: Bitmap) {
        val format = when {
            file.name.endsWith(".webp", true) -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
        // Draw onto square canvas to avoid stretch artifacts from odd sources
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        FileOutputStream(file).use { fos ->
            out.compress(format, 100, fos)
        }
        out.recycle()
    }

    private fun replaceAppNameInStrings(xml: String, newName: String): String {
        val escaped = escapeXml(newName)
        val patterns = listOf(
            Pattern.compile("""(<string\s+name="app_name"\s*>)(.*?)(</string>)""", Pattern.DOTALL),
            Pattern.compile("""(<string\s+name="app_name"\s+translatable="false"\s*>)(.*?)(</string>)""", Pattern.DOTALL),
            Pattern.compile("""(<string\s+name="application_name"\s*>)(.*?)(</string>)""", Pattern.DOTALL)
        )
        var result = xml
        patterns.forEach { p ->
            val m = p.matcher(result)
            if (m.find()) {
                result = m.replaceAll("$1$escaped$3")
            }
        }
        return result
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun normalize(s: String): String =
        s.lowercase(Locale("tr", "TR"))
            .replace('İ', 'i')
            .replace('I', 'ı')
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")

    private fun matchesHelp(n: String) = n.contains("yardim") || n == "help" || n.contains("ne yapabilir")
    private fun matchesLogo(n: String) =
        (n.contains("logo") || n.contains("ikon") || n.contains("icon") || n.contains("simge")) &&
            (n.contains("degistir") || n.contains("yap") || n.contains("guncelle") || n.contains("ko") || n.contains("ayarla"))
            || n.contains("logoyu degistir") || n.contains("ikonu degistir")

    private fun matchesRename(n: String) =
        (n.contains("adini") || n.contains("ismi") || n.contains("adini") || n.contains("adi ")) &&
            (n.contains("yap") || n.contains("degistir") || n.contains("olsun"))
            || n.startsWith("ad ") || n.contains("uygulama adi")

    private fun matchesReplace(n: String) =
        n.contains("metin degistir") || n.contains("yazi degistir") || n == "degistir" ||
            n.contains("bul ve degistir") || n.contains("replace")

    private fun matchesFind(n: String) = n.startsWith("bul ") || n.startsWith("ara ") || n.contains("nerede")
    private fun matchesExtract(n: String) =
        n.contains("cikar") || n.contains("acik") || n.contains("extract") || n.contains("icini ac")
    private fun matchesRebuild(n: String) =
        n.contains("paketle") || n.contains("yeniden olustur") || n.contains("rebuild") || n.contains("apk yap")
    private fun matchesAnalyze(n: String) = n.contains("analiz") || n.contains("incele") || n.contains("rapor")
    private fun matchesOpenFiles(n: String) = n.contains("dosya") && (n.contains("ac") || n.contains("gez") || n.contains("goster"))
    private fun matchesTest(n: String) = n.contains("test") || n.contains("calistir") || n.contains("oyna")
    private fun matchesListIcons(n: String) = n.contains("ikonlari listele") || n.contains("logolari goster") || n.contains("ikon list")
    private fun matchesStatus(n: String) = n.contains("durum") || n.contains("status") || n == "ne var"

    private fun extractRenameTarget(raw: String): String? {
        val patterns = listOf(
            Regex("""adını\s+(.+?)\s+yap""", RegexOption.IGNORE_CASE),
            Regex("""adini\s+(.+?)\s+yap""", RegexOption.IGNORE_CASE),
            Regex("""ismi\s+(.+?)\s+yap""", RegexOption.IGNORE_CASE),
            Regex("""adı\s+(.+?)\s+olsun""", RegexOption.IGNORE_CASE),
            Regex("""adi\s+(.+?)\s+olsun""", RegexOption.IGNORE_CASE),
            Regex("""adını\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""adini\s+(.+)$""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { p ->
            val m = p.find(raw)
            if (m != null) return m.groupValues[1].trim().trim('"', '\'')
        }
        return null
    }

    private fun extractFindTarget(raw: String): String? {
        val m = Regex("""^(bul|ara)\s+(.+)$""", RegexOption.IGNORE_CASE).find(raw.trim())
        return m?.groupValues?.getOrNull(2)?.trim()
    }
}
