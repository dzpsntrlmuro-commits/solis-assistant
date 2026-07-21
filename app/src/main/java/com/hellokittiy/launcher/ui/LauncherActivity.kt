package com.hellokittiy.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.AnimationUtils
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hellokittiy.launcher.R
import com.hellokittiy.launcher.databinding.ActivityLauncherBinding
import com.hellokittiy.launcher.databinding.PanelQuickBinding
import com.hellokittiy.launcher.model.AppItem
import com.hellokittiy.launcher.model.NotifItem
import com.hellokittiy.launcher.notify.KittiyNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class LauncherActivity : AppCompatActivity(), KittiyNotificationListener.Listener {

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var panel: PanelQuickBinding
    private lateinit var audioManager: AudioManager
    private lateinit var appAdapter: AppAdapter
    private lateinit var notifAdapter: NotificationAdapter

    private var allApps: List<AppItem> = emptyList()
    private var panelOpen = false
    private var mutedSnapshot: Triple<Int, Int, Int>? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            binding.tvClock.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        panel = binding.quickPanel

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupApps()
        setupPanel()
        setupGestures()
        setupDock()
        updateClock()
        binding.tvClock.post(clockRunnable)

        binding.btnOpenPanel.setOnClickListener { openPanel() }
        binding.scrim.setOnClickListener { closePanel() }

        // Soft entrance for brand
        binding.imgBrand.alpha = 0f
        binding.imgBrand.scaleX = 0.6f
        binding.imgBrand.scaleY = 0.6f
        binding.imgBrand.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .start()

        binding.tvBrand.alpha = 0f
        binding.tvBrand.translationY = -12f
        binding.tvBrand.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(120)
            .setDuration(400)
            .start()

        loadApps()
    }

    private fun setupApps() {
        appAdapter = AppAdapter { launchApp(it) }
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter = appAdapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
                val filtered = if (q.isEmpty()) allApps else allApps.filter {
                    it.label.lowercase(Locale.getDefault()).contains(q)
                }
                appAdapter.submit(filtered)
            }
        })
    }

    private fun setupPanel() {
        notifAdapter = NotificationAdapter()
        panel.rvNotifications.layoutManager = LinearLayoutManager(this)
        panel.rvNotifications.adapter = notifAdapter

        panel.btnClosePanel.setOnClickListener { closePanel() }
        panel.btnClearNotifs.setOnClickListener {
            KittiyNotificationListener.instance?.clearAll()
            notifAdapter.submit(emptyList())
            panel.tvEmptyNotifs.isVisible = true
        }
        panel.btnSetHome.setOnClickListener { promptHomeChooser() }
        panel.tvNotifPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        panel.btnMute.setOnClickListener { toggleMute() }

        bindVolumeSeek(panel.seekMedia, AudioManager.STREAM_MUSIC)
        bindVolumeSeek(panel.seekRing, AudioManager.STREAM_RING)
        bindVolumeSeek(panel.seekAlarm, AudioManager.STREAM_ALARM)
    }

    private fun bindVolumeSeek(seek: SeekBar, stream: Int) {
        seek.max = audioManager.getStreamMaxVolume(stream)
        seek.progress = audioManager.getStreamVolume(stream)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(stream, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun refreshVolumes() {
        panel.seekMedia.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        panel.seekMedia.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        panel.seekRing.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        panel.seekRing.progress = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        panel.seekAlarm.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        panel.seekAlarm.progress = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    }

    private fun toggleMute() {
        if (mutedSnapshot == null) {
            mutedSnapshot = Triple(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                audioManager.getStreamVolume(AudioManager.STREAM_RING),
                audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            )
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            panel.btnMute.text = getString(R.string.unmute)
        } else {
            val (m, r, a) = mutedSnapshot!!
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, m, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, r, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, a, 0)
            mutedSnapshot = null
            panel.btnMute.text = getString(R.string.mute)
        }
        refreshVolumes()
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
                val dx = e2.x - e1.x
                if (abs(dy) < abs(dx)) return false
                if (dy > 120 && velocityY > 400 && e1.y < 220) {
                    openPanel()
                    return true
                }
                if (dy < -120 && velocityY < -400 && panelOpen) {
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
    }

    private fun setupDock() {
        binding.dockPhone.setOnClickListener {
            launchIntent(Intent(Intent.ACTION_DIAL))
        }
        binding.dockSms.setOnClickListener {
            launchIntent(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_MESSAGING)
            })
        }
        binding.dockCamera.setOnClickListener {
            launchIntent(Intent("android.media.action.IMAGE_CAPTURE"))
        }
        binding.dockSettings.setOnClickListener {
            launchIntent(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Uygulama açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) { queryApps() }
            allApps = apps
            appAdapter.submit(apps)
        }
    }

    private fun queryApps(): List<AppItem> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolve = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolve
            .mapNotNull { info ->
                val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
                val pkg = info.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                val icon = info.loadIcon(pm) ?: return@mapNotNull null
                AppItem(label, pkg, info.activityInfo.name, icon)
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun launchApp(item: AppItem) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(item.packageName, item.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Açılamadı: ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPanel() {
        if (panelOpen) return
        panelOpen = true
        refreshVolumes()
        refreshNotificationUi()
        binding.scrim.isVisible = true
        binding.scrim.alpha = 0f
        binding.scrim.animate().alpha(1f).setDuration(200).start()
        binding.quickPanel.root.isVisible = true
        binding.quickPanel.root.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.panel_slide_in)
        )
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        binding.scrim.animate().alpha(0f).setDuration(180).withEndAction {
            binding.scrim.isVisible = false
        }.start()
        val out = AnimationUtils.loadAnimation(this, R.anim.panel_slide_out)
        out.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) = Unit
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) = Unit
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                binding.quickPanel.root.isVisible = false
            }
        })
        binding.quickPanel.root.startAnimation(out)
    }

    private fun refreshNotificationUi() {
        val enabled = isNotificationAccessEnabled()
        panel.tvNotifPermission.isVisible = !enabled
        panel.tvNotifPermission.text = if (enabled) {
            ""
        } else {
            getString(R.string.enable_notifications) + " → " + getString(R.string.open_notif_settings)
        }
        val items = KittiyNotificationListener.instance?.currentNotifications().orEmpty()
        onNotificationsChanged(items)
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val cn = ComponentName(this, KittiyNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun promptHomeChooser() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(Intent.createChooser(intent, getString(R.string.set_as_home)))
    }

    private fun updateClock() {
        val cal = Calendar.getInstance()
        val time = DateFormat.format("HH:mm · EEEE d MMMM", cal)
        binding.tvClock.text = time
    }

    override fun onNotificationsChanged(items: List<NotifItem>) {
        runOnUiThread {
            notifAdapter.submit(items)
            panel.tvEmptyNotifs.isVisible = items.isEmpty()
            panel.rvNotifications.isVisible = items.isNotEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        KittiyNotificationListener.addListener(this)
        refreshNotificationUi()
        loadApps()
    }

    override fun onPause() {
        KittiyNotificationListener.removeListener(this)
        super.onPause()
    }

    override fun onBackPressed() {
        if (panelOpen) {
            closePanel()
        }
        // Launcher: ignore back to stay on home
    }

    override fun onDestroy() {
        binding.tvClock.removeCallbacks(clockRunnable)
        super.onDestroy()
    }
}
