package com.hellokittiy.launcher.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hellokittiy.launcher.R
import com.hellokittiy.launcher.databinding.ActivityMainBinding
import com.hellokittiy.launcher.model.AppItem
import com.hellokittiy.launcher.model.NotifItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appAdapter: AppAdapter
    private lateinit var notifAdapter: NotifAdapter
    private lateinit var audioManager: AudioManager

    private var allApps: List<AppItem> = emptyList()
    private var panelOpen = false
    private var panelHeight = 0f

    private val prefs by lazy { getSharedPreferences("kittiy", MODE_PRIVATE) }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupInsets()
        setupApps()
        setupDock()
        setupPanel()
        setupVolume()
        setupSearch()
        setupGestures()
        renderNotifications(sampleNotifications())

        // Always behave as home launcher entry
        if (intent?.categories?.contains(Intent.CATEGORY_HOME) == true ||
            intent?.action == Intent.ACTION_MAIN
        ) {
            maybePromptDefaultLauncher()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (panelOpen) closePanel()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTick)
        syncVolumeSliders()
        loadApps()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelOpen) {
            closePanel()
        }
        // Launcher: stay on home
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.homeLayer.setPadding(
                binding.homeLayer.paddingLeft,
                bars.top + 6,
                binding.homeLayer.paddingRight,
                bars.bottom + 6
            )
            binding.notificationPanel.setPadding(
                binding.notificationPanel.paddingLeft,
                bars.top + 10,
                binding.notificationPanel.paddingRight,
                binding.notificationPanel.paddingBottom
            )
            insets
        }
    }

    private fun setupApps() {
        appAdapter = AppAdapter(emptyList()) { launchApp(it) }
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter = appAdapter

        notifAdapter = NotifAdapter(emptyList())
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = notifAdapter
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.toAppItem(pm) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        allApps = resolved
        filterApps(binding.etSearch.text?.toString().orEmpty())
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase(Locale.getDefault())
        val filtered = if (q.isEmpty()) allApps else allApps.filter {
            it.label.lowercase(Locale.getDefault()).contains(q)
        }
        appAdapter.submit(filtered)
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupDock() {
        binding.btnDockPhone.setOnClickListener {
            launchIntent(Intent(Intent.ACTION_DIAL))
        }
        binding.btnDockMessages.setOnClickListener {
            val sms = Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
            if (sms.resolveActivity(packageManager) != null) launchIntent(sms)
            else Toast.makeText(this, "Mesaj uygulaması yok", Toast.LENGTH_SHORT).show()
        }
        binding.btnDockCamera.setOnClickListener {
            val camera = packageManager.getLaunchIntentForPackage("com.android.camera")
                ?: packageManager.getLaunchIntentForPackage("com.google.android.GoogleCamera")
                ?: Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            launchIntent(camera)
        }
        binding.btnDockSettings.setOnClickListener {
            launchIntent(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPanel() {
        binding.notificationPanel.post {
            panelHeight = binding.notificationPanel.height.toFloat()
            if (!panelOpen) {
                binding.notificationPanel.translationY = -panelHeight
            }
        }

        binding.scrim.setOnClickListener { closePanel() }
        binding.btnClearNotifs.setOnClickListener {
            renderNotifications(emptyList())
        }

        binding.btnVolDown.setOnClickListener { adjustVolume(-1) }
        binding.btnVolUp.setOnClickListener { adjustVolume(+1) }

        binding.tileWifi.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        binding.tileSilent.setOnClickListener { toggleSilent() }
        binding.tileLauncher.setOnClickListener { promptDefaultLauncher() }
    }

    private fun setupVolume() {
        fun bind(seek: SeekBar, stream: Int) {
            seek.max = audioManager.getStreamMaxVolume(stream)
            seek.progress = audioManager.getStreamVolume(stream)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) audioManager.setStreamVolume(stream, progress, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bind(binding.seekMedia, AudioManager.STREAM_MUSIC)
        bind(binding.seekRing, AudioManager.STREAM_RING)
        bind(binding.seekAlarm, AudioManager.STREAM_ALARM)
    }

    private fun adjustVolume(delta: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val next = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + delta)
            .coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        binding.seekMedia.progress = next
        Toast.makeText(
            this,
            if (delta < 0) getString(R.string.vol_down) else getString(R.string.vol_up),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun syncVolumeSliders() {
        binding.seekMedia.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.seekMedia.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekRing.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        binding.seekRing.progress = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        binding.seekAlarm.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        binding.seekAlarm.progress = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    private fun toggleSilent() {
        val mode = audioManager.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            Toast.makeText(this, "Sessiz ♡", Toast.LENGTH_SHORT).show()
            binding.tileSilent.alpha = 0.55f
        } else {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            Toast.makeText(this, "Ses açık ♡", Toast.LENGTH_SHORT).show()
            binding.tileSilent.alpha = 1f
        }
        syncVolumeSliders()
    }

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                if (!panelOpen && e1.y < 180 && dy > 120 && velocityY > 400) {
                    openPanel()
                    return true
                }
                if (panelOpen && dy < -120 && velocityY < -400) {
                    closePanel()
                    return true
                }
                return false
            }
        })

        binding.root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }

        binding.pullHintBar.setOnClickListener { openPanel() }
        binding.statusRow.setOnClickListener { openPanel() }
        binding.tvBrand.setOnLongClickListener {
            openPanel()
            true
        }
    }

    private fun openPanel() {
        panelHeight = binding.notificationPanel.height.toFloat().coerceAtLeast(1f)
        panelOpen = true
        binding.scrim.isVisible = true
        binding.scrim.alpha = 0f
        binding.scrim.animate().alpha(1f).setDuration(220).start()
        binding.notificationPanel.animate()
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()
        syncVolumeSliders()
        if (notifAdapter.itemCount == 0) {
            renderNotifications(sampleNotifications())
        }
    }

    private fun closePanel() {
        panelOpen = false
        panelHeight = binding.notificationPanel.height.toFloat().coerceAtLeast(1f)
        binding.scrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.scrim.isVisible = false
        }.start()
        binding.notificationPanel.animate()
            .translationY(-panelHeight)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun updateClock() {
        val now = Date()
        binding.tvClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        binding.tvDate.text = SimpleDateFormat("EEE d MMM", Locale("tr")).format(now)
    }

    private fun launchApp(item: AppItem) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(item.packageName, item.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve: ResolveInfo? = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolve?.activityInfo?.packageName == packageName
    }

    private fun maybePromptDefaultLauncher() {
        if (isDefaultLauncher()) return
        if (prefs.getBoolean("skip_launcher_prompt", false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.launcher_dialog_title)
            .setMessage(R.string.launcher_dialog_msg)
            .setPositiveButton(R.string.launcher_dialog_ok) { _, _ ->
                promptDefaultLauncher()
            }
            .setNegativeButton(R.string.launcher_dialog_later) { _, _ ->
                prefs.edit().putBoolean("skip_launcher_prompt", true).apply()
            }
            .show()
    }

    private fun promptDefaultLauncher() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: Exception) {
                // Fallback: trigger home chooser
                val selector = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(selector)
            }
        }
        Toast.makeText(this, R.string.launcher_hint, Toast.LENGTH_LONG).show()
    }

    private fun renderNotifications(items: List<NotifItem>) {
        notifAdapter.submit(items)
        binding.tvEmptyNotifs.isVisible = items.isEmpty()
        binding.rvNotifications.isVisible = items.isNotEmpty()
    }

    private fun sampleNotifications(): List<NotifItem> = listOf(
        NotifItem("1", "Hello Kittiy", "Pembe bildirimler hazır ♡", packageName),
        NotifItem("2", "Ses", "Ses kıs / Ses aç pembe Kitty tuşlarıyla.", packageName),
        NotifItem("3", "Ana ekran", "Varsayılan başlatıcı yap — telefon açılınca bu arayüz gelir.", packageName)
    )
}
