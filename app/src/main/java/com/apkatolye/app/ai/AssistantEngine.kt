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
    WAITING_WRITE_CONTENT
}

data class AssistantReply(
    val message: String,
    val brief: String = "",
    val plan: String = "",
    val pending: PendingAction = PendingAction.NONE,
    val openImagePicker: Boolean = false,
    val openFiles: Boolean = false,
    val openRebuild: Boolean = false,
    val openTest: Boolean = false,
    val askApiKey: Boolean = false,
    val keepListening: Boolean = false
)

/**
 * Conversation-first assistant: remembers everything, consolidates in background,
 * routes through Gemini like Cursor when API key exists.
 */
class AssistantEngine(private val context: Context) {

    var pending: PendingAction = PendingAction.NONE
        private set

    private var writePath: String? = null
    private val code = CodeOps(context)
    private val gemini = GeminiClient(context)
    val memory = ConversationMemory(context)

    fun setApiKey(key: String?) = gemini.setApiKey(key)
    fun hasApiKey(): Boolean = gemini.hasKey()

    fun currentBrief(): String = memory.brief
    fun currentPlan(): String = memory.lastPlan

    /**
     * Buffer-friendly entry: remembers the utterance, then thinks.
     * @param finalize if false, only note + light ack (for continuous speech chunks)
     */
    fun handle(userText: String, finalize: Boolean = true): AssistantReply {
        val text = userText.trim()
        if (text.isEmpty()) {
            return AssistantReply("Seni dinliyorum — ne yapmamı istiyorsun?")
        }

        // Always remember — even incomplete speech chunks
        memory.addUser(text)

        if (!finalize) {
            val briefGuess = consolidateLocally()
            memory.updateBrief(briefGuess)
            return AssistantReply(
                message = "Not aldım. Konuşmaya devam et veya «uygula» de.",
                brief = briefGuess,
                keepListening = true
            )
        }

        // Pending local multi-step
        when (pending) {
            PendingAction.WAITING_LOGO_IMAGE -> {
                return AssistantReply(
                    "Logo için görsel bekliyorum.",
                    brief = memory.brief,
                    pending = PendingAction.WAITING_LOGO_IMAGE,
                    openImagePicker = true
                )
            }
            PendingAction.WAITING_WRITE_CONTENT -> {
                val path = writePath.orEmpty()
                pending = PendingAction.NONE
                writePath = null
                return writeFile(path, text)
            }
            else -> Unit
        }

        // Explicit file write block still supported
        parseExplicitWrite(text)?.let { return it }

        val n = normalize(text)

        // Meta commands that don't need LLM
        if (matchesApiKey(n)) {
            return AssistantReply(
                "Gemini API anahtarını kaydedelim — bundan sonra dediklerinin hepsine Cursor gibi odaklanacağım.",
                brief = memory.brief,
                askApiKey = true
            )
        }
        if (n.contains("brief") || n.contains("ozet") || n.contains("ne istiyorum") || n.contains("hedefler")) {
            return AssistantReply(
                "Biriken odak:\n${memory.brief.ifBlank { "(henüz özet yok — konuşmaya devam et)" }}\n\n" +
                    "Plan:\n${memory.lastPlan.ifBlank { "-" }}\n\n" +
                    "Ham notlar:\n${memory.allUserNotes()}",
                brief = memory.brief,
                plan = memory.lastPlan
            )
        }
        if (n.contains("hafizayi sil") || n.contains("sifirla") || n == "reset") {
            memory.resetAll()
            return AssistantReply("Hafızayı temizledim. Yeniden başlayalım.")
        }
        if (n == "uygula" || n == "yap" || n.contains("hepsini uygula") || n.contains("simdi yap")) {
            return thinkAndAct("Kullanıcı biriken tüm istekleri ŞİMDİ uygulamamı istedi. Brief ve notlardaki her hedefi uygula.")
        }

        // Fast local intents (still recorded in memory)
        when {
            matchesLogo(n) -> return startLogoChange()
            matchesExtract(n) -> return extract()
            matchesRebuild(n) -> return rebuild()
            matchesOpenFiles(n) -> return AssistantReply("Dosya gezgini açılıyor.", memory.brief, openFiles = true)
            matchesTest(n) -> return AssistantReply("Test alanı açılıyor.", memory.brief, openTest = true)
        }

        // Primary path: Cursor-like Gemini with full memory
        if (gemini.hasKey()) {
            return thinkAndAct(text)
        }

        // No API key: keep consolidating locally so user feels heard
        val brief = consolidateLocally()
        memory.updateBrief(brief)
        val reply = AssistantReply(
            message = "Seni duydum ve arka planda notuma ekledim.\n\n" +
                "Güncel odak:\n$brief\n\n" +
                "Cursor gibi serbest konuşup kod değiştirmem için Gemini API anahtarı gerekli.\n" +
                "«API» ile kaydet, sonra istediğini doğal cümleyle söyle veya «uygula» de.\n\n" +
                "Anahtarsız şimdilik: logo / çıkar / paketle / dosya gezgini çalışır.",
            brief = brief,
            askApiKey = true
        )
        memory.addAssistant(reply.message)
        return reply
    }

    fun applyLogoFromUri(uri: Uri): AssistantReply {
        memory.addUser("(logo görseli seçildi)")
        return try {
            ensureExtracted()
            val targets = findLauncherIcons(code.extractRoot())
            if (targets.isEmpty()) {
                pending = PendingAction.NONE
                return rememberReply("Launcher ikonu yok. Önce APK çıkar.")
            }
            val source = decodeBitmap(uri) ?: return rememberReply("Görsel okunamadı.")
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
            val brief = consolidateLocally() + "\n- Logo güncellendi ($changed dosya)"
            memory.updateBrief(brief)
            rememberReply("Logoyu değiştirdim ($changed ikon). İstersen «yeniden paketle» veya başka isteklerini söyle.")
        } catch (e: Exception) {
            pending = PendingAction.NONE
            rememberReply("Logo hatası: ${e.message}")
        }
    }

    private fun thinkAndAct(latest: String): AssistantReply {
        return try {
            ensureExtractedSoft()
            val summary = if (WorkspacePaths.extractDir(context).exists()) {
                code.workspaceSummary(120)
            } else {
                "Çıkarım yok. Gerekirse extract local_action kullan."
            }
            val focus = gatherFocus(latest)
            val decision = gemini.think(latest, memory, summary, focus)
            if (decision.brief.isNotBlank()) memory.updateBrief(decision.brief)
            if (decision.plan.isNotBlank()) memory.updatePlan(decision.plan)

            val patchReport = if (decision.patches.isNotEmpty()) {
                "\n\nUygulanan yamalar:\n" + applyPatches(decision.patches)
            } else ""

            var openImage = false
            var openFiles = false
            var openTest = false
            var openRebuild = false
            decision.localActions.forEach { action ->
                when (action.lowercase(Locale.ROOT)) {
                    "extract" -> runCatching { extract() }
                    "rebuild" -> runCatching { rebuild() }.also { openTest = true }
                    "open_files" -> openFiles = true
                    "open_test" -> openTest = true
                    "pick_image", "logo" -> {
                        openImage = true
                        pending = PendingAction.WAITING_LOGO_IMAGE
                    }
                    "open_rebuild" -> openRebuild = true
                }
            }

            val msg = buildString {
                append(decision.message)
                append(patchReport)
                if (decision.brief.isNotBlank()) {
                    append("\n\n— Odak özeti —\n")
                    append(decision.brief)
                }
                if (decision.plan.isNotBlank()) {
                    append("\n\n— Plan —\n")
                    append(decision.plan)
                }
            }
            memory.addAssistant(msg)
            AssistantReply(
                message = msg,
                brief = memory.brief,
                plan = memory.lastPlan,
                pending = pending,
                openImagePicker = openImage,
                openFiles = openFiles,
                openTest = openTest,
                openRebuild = openRebuild,
                keepListening = decision.listenMore
            )
        } catch (e: Exception) {
            rememberReply(
                "Düşünürken hata: ${e.message}\n" +
                    "API anahtarını kontrol et veya «durum» / «özet» ile biriken notlara bak."
            )
        }
    }

    private fun gatherFocus(latest: String): MutableMap<String, String> {
        val focus = mutableMapOf<String, String>()
        if (!WorkspacePaths.extractDir(context).exists()) return focus
        val tokens = (latest + " " + memory.brief)
            .split(Regex("[\\s,.;:]+"))
            .filter { it.length > 3 }
            .distinct()
            .take(12)
        tokens.forEach { token ->
            code.findFiles(token, 2).forEach { path ->
                if (focus.size < 6 && runCatching { code.isEditable(code.resolve(path)) }.getOrDefault(false)) {
                    runCatching { focus[path] = code.readText(path, 25_000) }
                }
            }
            code.findSmaliClasses(token, 2).forEach { path ->
                if (focus.size < 6) runCatching { focus[path] = code.readText(path, 25_000) }
            }
        }
        if (focus.isEmpty()) {
            code.listEditable(4).forEach { path ->
                runCatching { focus[path] = code.readText(path, 15_000) }
            }
        }
        return focus
    }

    private fun consolidateLocally(): String {
        val notes = memory.allUserNotes().lowercase(Locale("tr", "TR"))
        val items = linkedSetOf<String>()
        if (notes.contains("logo") || notes.contains("ikon") || notes.contains("icon")) {
            items += "Logo/ikon değiştirilecek"
        }
        if (notes.contains("adını") || notes.contains("adini") || notes.contains("isim")) {
            items += "Uygulama adı güncellenecek"
        }
        if (notes.contains("kod") || notes.contains("smali") || notes.contains("yeniden yaz")) {
            items += "Kod yeniden yazılacak / düzenlenecek"
        }
        if (notes.contains("paket") || notes.contains("apk yap") || notes.contains("rebuild")) {
            items += "Yeniden paketlenecek"
        }
        if (notes.contains("renk") || notes.contains("tema")) {
            items += "Görünüm/renk değişikliği"
        }
        if (notes.contains("metin") || notes.contains("yazı") || notes.contains("yazi")) {
            items += "Metin değişiklikleri"
        }
        // Always include last few raw notes so nothing is "lost"
        memory.allUserNotes().split("\n").map { it.removePrefix("- ").trim() }
            .filter { it.isNotBlank() }
            .takeLast(6)
            .forEach { items += "Not: $it" }
        return if (items.isEmpty()) "Henüz net hedef yok — konuşmaya devam et."
        else items.joinToString("\n") { "• $it" }
    }

    private fun applyPatches(patches: List<CodePatch>): String = buildString {
        patches.forEachIndexed { index, patch ->
            try {
                when (patch.action.lowercase(Locale.ROOT)) {
                    "write" -> {
                        val content = patch.content ?: error("content yok")
                        code.writeText(patch.path, content)
                        appendLine("${index + 1}. yazıldı: ${patch.path}")
                    }
                    "replace" -> {
                        val count = code.replaceInFile(
                            patch.path,
                            patch.old ?: error("old yok"),
                            patch.new ?: error("new yok")
                        )
                        appendLine("${index + 1}. replace: ${patch.path} ($count)")
                    }
                    "delete" -> {
                        code.delete(patch.path)
                        appendLine("${index + 1}. silindi: ${patch.path}")
                    }
                    else -> appendLine("${index + 1}. bilinmeyen: ${patch.action}")
                }
            } catch (e: Exception) {
                appendLine("${index + 1}. HATA ${patch.path}: ${e.message}")
            }
        }
    }

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
            return rememberReply("Yol alındı: $path — şimdi içeriği gönder.")
        }
        return writeFile(path, content)
    }

    private fun writeFile(path: String, content: String): AssistantReply {
        return try {
            ensureExtracted()
            code.writeText(path, content)
            rememberReply("Yazdım: $path (${content.lines().size} satır)")
        } catch (e: Exception) {
            rememberReply("Yazma hatası: ${e.message}")
        }
    }

    private fun startLogoChange(): AssistantReply {
        if (!WorkspacePaths.selectedApk(context).exists()) {
            return rememberReply("Önce APK seç.")
        }
        pending = PendingAction.WAITING_LOGO_IMAGE
        return AssistantReply(
            "Logoyu değiştireceğim — görseli seç.",
            brief = memory.brief,
            pending = PendingAction.WAITING_LOGO_IMAGE,
            openImagePicker = true
        )
    }

    private fun extract(): AssistantReply {
        return try {
            val apk = WorkspacePaths.selectedApk(context)
            if (!apk.exists()) return rememberReply("Önce APK seç.")
            val result = ApkExtractor.extract(apk, WorkspacePaths.extractDir(context))
            rememberReply("Çıkardım: ${result.fileCount} dosya. Devam et, seni dinliyorum.")
                .copy(openFiles = false)
        } catch (e: Exception) {
            rememberReply("Çıkarma: ${e.message}")
        }
    }

    private fun rebuild(): AssistantReply {
        return try {
            ensureExtracted()
            val out = WorkspacePaths.rebuildApk(context)
            val result = ApkRepackager.rebuild(code.extractRoot(), out)
            rememberReply(
                "Paketledim: ${result.outputApk.name} (${ApkAnalyzer.formatSize(result.sizeBytes)})"
            ).copy(openTest = true)
        } catch (e: Exception) {
            rememberReply("Paketleme: ${e.message}")
        }
    }

    private fun rememberReply(message: String): AssistantReply {
        memory.addAssistant(message)
        return AssistantReply(message = message, brief = memory.brief, plan = memory.lastPlan)
    }

    private fun ensureExtractedSoft() {
        val apk = WorkspacePaths.selectedApk(context)
        if (!apk.exists()) return
        val dir = WorkspacePaths.extractDir(context)
        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) {
            runCatching { ApkExtractor.extract(apk, dir) }
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
        return root.walkTopDown().filter { it.isFile }.filter {
            val n = it.name.lowercase(Locale.ROOT)
            it.path.contains("/res/") && (n.endsWith(".png") || n.endsWith(".webp")) &&
                hints.any { h -> n.contains(h) }
        }.sortedBy { it.path }.toList()
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

    private fun normalize(s: String): String =
        s.lowercase(Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
            .replace("ş", "s").replace("ö", "o").replace("ç", "c")
            .replace("â", "a")

    private fun matchesApiKey(n: String) =
        n.contains("api") && (n.contains("anahtar") || n.contains("key") || n.contains("gemini"))
    private fun matchesLogo(n: String) =
        (n.contains("logo") || n.contains("ikon") || n.contains("icon")) &&
            (n.contains("degistir") || n.contains("yap") || n.contains("guncelle"))
    private fun matchesExtract(n: String) = n.contains("cikar") || n.contains("extract")
    private fun matchesRebuild(n: String) = n.contains("paketle") || n.contains("rebuild")
    private fun matchesOpenFiles(n: String) = n.contains("dosya") && (n.contains("ac") || n.contains("gez"))
    private fun matchesTest(n: String) = n.contains("test") && !n.contains("protest")
}
