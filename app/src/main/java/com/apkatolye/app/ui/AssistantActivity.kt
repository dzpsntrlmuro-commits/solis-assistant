package com.apkatolye.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apkatolye.app.R
import com.apkatolye.app.ai.AssistantEngine
import com.apkatolye.app.ai.AssistantReply
import com.apkatolye.app.ai.PendingAction
import com.apkatolye.app.databinding.ActivityAssistantBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistantBinding
    private lateinit var engine: AssistantEngine
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var speechRecognizer: SpeechRecognizer? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            appendBot("Görsel seçilmedi.")
            return@registerForActivityResult
        }
        runLogo(uri)
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else Toast.makeText(this, "Mikrofon izni gerekli", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = AssistantEngine(this)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ChatAdapter(messages)
        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = adapter

        appendBot(
            "Merhaba — artık kodu da doğrudan yeniden yazabilirim.\n\n" +
                "Örnekler:\n" +
                "• kodu yeniden yaz MainActivity\n" +
                "• dosya yaz smali/.../Foo.smali  (sonra kodu yapıştır)\n" +
                "• sınıf bul Login\n" +
                "• yapay zekâ ile: başlık metnini değiştir\n" +
                "• logoyu değiştir / yeniden paketle\n\n" +
                "Serbest cümle için «API» ile Gemini anahtarı kaydet."
        )

        binding.btnSend.setOnClickListener { sendInput() }
        binding.btnMic.setOnClickListener { ensureMicAndListen() }
        binding.chipLogo.setOnClickListener { submit("logoyu değiştir") }
        binding.chipRewrite.setOnClickListener { submit("kodu yeniden yaz") }
        binding.chipAi.setOnClickListener { promptAi() }
        binding.chipRename.setOnClickListener { promptRename() }
        binding.chipRebuild.setOnClickListener { submit("yeniden paketle") }
        binding.chipApi.setOnClickListener { promptApiKey() }
        binding.chipPickImage.setOnClickListener { pickImage.launch("image/*") }

        binding.input.setOnEditorActionListener { _, _, _ ->
            sendInput()
            true
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun sendInput() {
        val text = binding.input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        binding.input.setText("")
        submit(text)
    }

    private fun submit(text: String) {
        appendUser(text)
        binding.btnSend.isEnabled = false
        binding.hint.text = "Çalışıyor…"
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) { engine.handle(text) }
            applyReply(reply)
            binding.btnSend.isEnabled = true
            binding.hint.text = "Söyle veya yaz — kodu da yeniden yazabilirim"
        }
    }

    private fun runLogo(uri: Uri) {
        appendUser("(yeni logo görseli seçildi)")
        binding.btnSend.isEnabled = false
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) { engine.applyLogoFromUri(uri) }
            applyReply(reply)
            binding.btnSend.isEnabled = true
        }
    }

    private fun applyReply(reply: AssistantReply) {
        appendBot(reply.message)
        if (reply.askApiKey) {
            binding.chatList.postDelayed({ promptApiKey() }, 300)
        }
        if (reply.openImagePicker || reply.pending == PendingAction.WAITING_LOGO_IMAGE) {
            binding.chatList.postDelayed({ pickImage.launch("image/*") }, 350)
        }
        if (reply.openFiles) {
            binding.chatList.postDelayed({
                startActivity(Intent(this, FilesActivity::class.java))
            }, 500)
        }
        if (reply.openTest) {
            binding.chatList.postDelayed({
                startActivity(Intent(this, TestActivity::class.java))
            }, 500)
        }
        if (reply.openRebuild) {
            binding.chatList.postDelayed({
                startActivity(Intent(this, RebuildActivity::class.java))
            }, 500)
        }
    }

    private fun promptRename() {
        val input = android.widget.EditText(this).apply {
            hint = "Yeni uygulama adı"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Uygulama adı")
            .setView(input)
            .setPositiveButton("Uygula") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) submit("adını $name yap")
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun promptAi() {
        val input = android.widget.EditText(this).apply {
            hint = "Ne değişsin? (serbest Türkçe)"
            minLines = 3
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("YZ ile düzenle")
            .setMessage(
                if (engine.hasApiKey()) "Talimatını yaz — Gemini dosya yamaları üretecek."
                else "Önce API anahtarı kaydetmen gerekiyor."
            )
            .setView(input)
            .setPositiveButton("Gönder") { _, _ ->
                if (!engine.hasApiKey()) {
                    promptApiKey()
                    return@setPositiveButton
                }
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) submit("yapay zekâ ile: $text")
            }
            .setNeutralButton("API anahtarı") { _, _ -> promptApiKey() }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun promptApiKey() {
        val input = android.widget.EditText(this).apply {
            hint = "Gemini API key"
            setText("")
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API anahtarı")
            .setMessage("https://aistudio.google.com/apikey adresinden ücretsiz alabilirsin. Anahtar cihazda saklanır.")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                engine.setApiKey(key.ifBlank { null })
                appendBot(
                    if (key.isBlank()) "API anahtarı silindi."
                    else "API anahtarı kaydedildi. Artık serbest cümleyle kod değiştirebilirsin."
                )
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun ensureMicAndListen() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startListening()
            else -> requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Bu cihazda ses tanıma yok", Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.btnMic.text = "…"
                    appendBot("Dinliyorum…")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.btnMic.text = "Ses"
                }

                override fun onError(error: Int) {
                    binding.btnMic.text = "Ses"
                    appendBot("Sesi anlayamadım, tekrar dene veya yaz.")
                }

                override fun onResults(results: Bundle?) {
                    binding.btnMic.text = "Ses"
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (spoken.isNullOrEmpty()) appendBot("Bir şey duyulmadı.")
                    else submit(spoken)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun appendUser(text: String) {
        messages += ChatMessage(text, fromUser = true)
        adapter.notifyItemInserted(messages.lastIndex)
        binding.chatList.scrollToPosition(messages.lastIndex)
    }

    private fun appendBot(text: String) {
        messages += ChatMessage(text, fromUser = false)
        adapter.notifyItemInserted(messages.lastIndex)
        binding.chatList.scrollToPosition(messages.lastIndex)
    }

    data class ChatMessage(val text: String, val fromUser: Boolean)

    private class ChatAdapter(
        private val items: List<ChatMessage>
    ) : RecyclerView.Adapter<ChatAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.bubble.text = item.text
            val params = holder.bubble.layoutParams as LinearLayout.LayoutParams
            if (item.fromUser) {
                params.gravity = Gravity.END
                holder.bubble.setBackgroundResource(R.drawable.bg_chat_user)
                holder.bubble.setTextColor(
                    ResourcesCompat.getColor(holder.itemView.resources, R.color.white, null)
                )
            } else {
                params.gravity = Gravity.START
                holder.bubble.setBackgroundResource(R.drawable.bg_panel)
                holder.bubble.setTextColor(
                    ResourcesCompat.getColor(holder.itemView.resources, R.color.ink, null)
                )
            }
            holder.bubble.layoutParams = params
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val bubble: TextView = view.findViewById(R.id.bubble)
        }
    }
}
