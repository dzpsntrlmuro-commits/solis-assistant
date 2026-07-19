package com.apkatolye.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var continuousListen = false
    private val speechBuffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var flushRunnable: Runnable? = null

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
        refreshBrief()

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ChatAdapter(messages)
        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = adapter

        appendBot(
            "Seni gerçekten dinliyorum.\n\n" +
                "• Serbest konuş veya yaz — her cümlen arka planda toplanır\n" +
                "• «Sürekli dinle» açıkken ara vermeden konuş\n" +
                "• «Uygula» deyince biriken tüm isteklerini işlerim\n\n" +
                if (engine.hasApiKey()) "API anahtarı hazır — Cursor gibi odaklanabilirim."
                else "Önce «API» ile Gemini anahtarı kaydet; sonra doğal konuş."
        )

        if (!engine.hasApiKey()) {
            binding.chatList.postDelayed({ promptApiKey(force = false) }, 600)
        }

        binding.btnSend.setOnClickListener { sendInput(finalize = true) }
        binding.btnMic.setOnClickListener { ensureMicAndListen() }
        binding.chipListen.setOnClickListener { toggleContinuous() }
        binding.chipApply.setOnClickListener { submit("uygula", finalize = true) }
        binding.chipBrief.setOnClickListener { submit("özet", finalize = true) }
        binding.chipApi.setOnClickListener { promptApiKey(force = true) }
        binding.chipPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.chipRebuild.setOnClickListener { submit("yeniden paketle", finalize = true) }

        binding.input.setOnEditorActionListener { _, _, _ ->
            sendInput(finalize = true)
            true
        }
    }

    override fun onDestroy() {
        continuousListen = false
        flushRunnable?.let { mainHandler.removeCallbacks(it) }
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun toggleContinuous() {
        continuousListen = !continuousListen
        binding.chipListen.text = if (continuousListen) "Dinleme AÇIK" else "Sürekli dinle"
        binding.chipListen.setTextColor(
            ContextCompat.getColor(this, if (continuousListen) R.color.amber else R.color.teal)
        )
        if (continuousListen) {
            appendBot("Sürekli dinleme açık. Konuş — ara verdiğinde toparlayıp not alacağım.")
            ensureMicAndListen()
        } else {
            appendBot("Sürekli dinleme kapandı.")
            speechRecognizer?.stopListening()
        }
    }

    private fun sendInput(finalize: Boolean) {
        val text = binding.input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        binding.input.setText("")
        submit(text, finalize)
    }

    private fun submit(text: String, finalize: Boolean) {
        appendUser(text)
        binding.btnSend.isEnabled = false
        binding.hint.text = if (finalize) "Odaklanıp işliyorum…" else "Not alıyorum…"
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) {
                engine.handle(text, finalize = finalize)
            }
            applyReply(reply)
            binding.btnSend.isEnabled = true
            binding.hint.text = "Serbest konuş. Her cümlen not alınır; «Uygula» ile hepsini işlerim."
        }
    }

    private fun runLogo(uri: Uri) {
        appendUser("(logo görseli)")
        binding.btnSend.isEnabled = false
        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) { engine.applyLogoFromUri(uri) }
            applyReply(reply)
            binding.btnSend.isEnabled = true
        }
    }

    private fun applyReply(reply: AssistantReply) {
        appendBot(reply.message)
        refreshBrief(reply.brief.ifBlank { engine.currentBrief() }, reply.plan)
        if (reply.askApiKey) {
            binding.chatList.postDelayed({ promptApiKey(force = false) }, 400)
        }
        if (reply.openImagePicker || reply.pending == PendingAction.WAITING_LOGO_IMAGE) {
            binding.chatList.postDelayed({ pickImage.launch("image/*") }, 350)
        }
        if (reply.openFiles) {
            binding.chatList.postDelayed({
                startActivity(Intent(this, FilesActivity::class.java))
            }, 450)
        }
        if (reply.openTest) {
            binding.chatList.postDelayed({
                startActivity(Intent(this, TestActivity::class.java))
            }, 450)
        }
        if (reply.openRebuild) {
            binding.chatList.postDelayed({
                startActivity(Intent(this, RebuildActivity::class.java))
            }, 450)
        }
        if (reply.keepListening || continuousListen) {
            binding.chatList.postDelayed({
                if (continuousListen) startListening()
            }, 500)
        }
    }

    private fun refreshBrief(brief: String = engine.currentBrief(), plan: String = engine.currentPlan()) {
        binding.briefText.text = buildString {
            append(brief.ifBlank { "Seni dinliyorum — söylediklerin burada toplanacak." })
            if (plan.isNotBlank()) {
                append("\n\nPlan: ")
                append(plan)
            }
        }
    }

    private fun promptApiKey(force: Boolean) {
        if (!force && engine.hasApiKey()) return
        val input = android.widget.EditText(this).apply {
            hint = "Gemini API key"
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Cursor gibi dinlemem için")
            .setMessage(
                "Gemini API anahtarı kaydet.\nhttps://aistudio.google.com/apikey\n\n" +
                    "Sonra serbest konuş; her cümleni biriktirip uygulayacağım."
            )
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                engine.setApiKey(key.ifBlank { null })
                appendBot(
                    if (key.isBlank()) "API anahtarı silindi."
                    else "Tamam. Artık dediklerinin hepsine odaklanıp arka planda toparlayacağım."
                )
            }
            .setNegativeButton(if (force) "İptal" else "Sonra", null)
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
                    binding.hint.text = "Dinliyorum — konuş"
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.btnMic.text = "Ses"
                }

                override fun onError(error: Int) {
                    binding.btnMic.text = "Ses"
                    if (continuousListen) {
                        // Soft retry without spamming chat
                        mainHandler.postDelayed({
                            if (continuousListen) startListening()
                        }, 800)
                    } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                        appendBot("Sesi kaçırdım — tekrar söyle veya yaz.")
                    }
                }

                override fun onResults(results: Bundle?) {
                    binding.btnMic.text = "Ses"
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (spoken.isEmpty()) {
                        if (continuousListen) {
                            mainHandler.postDelayed({ startListening() }, 400)
                        }
                        return
                    }

                    if (continuousListen) {
                        // Buffer speech chunks; flush after short pause as finalize=false notes,
                        // user taps Uygula for full action — OR auto-note each chunk
                        if (speechBuffer.isNotEmpty()) speechBuffer.append(' ')
                        speechBuffer.append(spoken)
                        appendUser("🎤 $spoken")
                        // Immediately note without full AI act
                        lifecycleScope.launch {
                            val reply = withContext(Dispatchers.IO) {
                                engine.handle(spoken, finalize = false)
                            }
                            refreshBrief(reply.brief)
                            scheduleSpeechFlush()
                        }
                        mainHandler.postDelayed({
                            if (continuousListen) startListening()
                        }, 450)
                    } else {
                        submit(spoken, finalize = true)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun scheduleSpeechFlush() {
        flushRunnable?.let { mainHandler.removeCallbacks(it) }
        flushRunnable = Runnable {
            val chunk = speechBuffer.toString().trim()
            speechBuffer.clear()
            if (chunk.isNotEmpty() && engine.hasApiKey()) {
                // After a pause in continuous mode, do a light think to update brief/plan
                lifecycleScope.launch {
                    binding.hint.text = "Arka planda toparlıyorum…"
                    val reply = withContext(Dispatchers.IO) {
                        engine.handle(
                            "Kullanıcı konuşuyor. Şu ana kadar söylenenleri brief'te birleştir, " +
                                "henüz büyük değişiklik yapma unless açıkça istendi. Dinlemeye devam. Son parça: $chunk",
                            finalize = true
                        )
                    }
                    // Don't spam full message if it's just consolidation — update brief always
                    refreshBrief(reply.brief, reply.plan)
                    if (isActionHeavy(reply)) {
                        appendBot(reply.message)
                    } else if (reply.brief.isNotBlank()) {
                        appendBot("Odak güncellendi. Konuşmaya devam veya «Uygula».")
                    }
                    binding.hint.text = "Serbest konuş. Her cümlen not alınır; «Uygula» ile hepsini işlerim."
                }
            }
        }
        mainHandler.postDelayed(flushRunnable!!, 4500)
    }

    private fun isActionHeavy(r: AssistantReply): Boolean =
        r.message.contains("yamalar", true) ||
            r.message.contains("yazıldı", true) ||
            r.message.contains("Paketledim", true) ||
            r.message.contains("Logoyu", true) ||
            r.message.contains("Uygulanan", true) ||
            r.openImagePicker || r.openTest || r.openFiles

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
