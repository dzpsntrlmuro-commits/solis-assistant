package com.macsans.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.macsans.app.R
import com.macsans.app.adapter.MatchAdapter
import com.macsans.app.data.ApiKeyStore
import com.macsans.app.data.CouponStore
import com.macsans.app.data.MatchOddsCache
import com.macsans.app.data.MatchRepository
import com.macsans.app.model.Match
import com.macsans.app.model.MatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var emptyView: TextView
    private lateinit var headerMeta: TextView
    private lateinit var apiBanner: TextView
    private lateinit var slipInfo: TextView
    private lateinit var adapter: MatchAdapter

    private var allMatches: List<Match> = emptyList()
    private var filter: Filter = Filter.UPCOMING
    private var liveJob: Job? = null

    enum class Filter { ALL, LIVE, UPCOMING, FINISHED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recyclerMatches)
        swipe = findViewById(R.id.swipeRefresh)
        emptyView = findViewById(R.id.txtEmpty)
        headerMeta = findViewById(R.id.txtHeaderMeta)
        apiBanner = findViewById(R.id.txtApiBanner)
        slipInfo = findViewById(R.id.txtSlipInfo)

        adapter = MatchAdapter(
            onOpenDetail = { match ->
                val intent = Intent(this, MatchDetailActivity::class.java)
                intent.putExtra(MatchDetailActivity.EXTRA_MATCH_ID, match.id)
                MatchDetailActivity.cache = MatchOddsCache.get(match.id) ?: match
                startActivity(intent)
            },
            onPickChanged = { refreshSlipBar() }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Chip>(R.id.chipAll).setOnClickListener { setFilter(Filter.ALL) }
        findViewById<Chip>(R.id.chipLive).setOnClickListener { setFilter(Filter.LIVE) }
        findViewById<Chip>(R.id.chipUpcoming).setOnClickListener { setFilter(Filter.UPCOMING) }
        findViewById<Chip>(R.id.chipFinished).setOnClickListener { setFilter(Filter.FINISHED) }

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        apiBanner.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnMyCoupons).setOnClickListener {
            startActivity(Intent(this, CouponsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnSaveSlip).setOnClickListener {
            val coupon = CouponStore.createCouponFromSlip(this)
            if (coupon == null) {
                Toast.makeText(this, "Kupon boş — Olmamış sekmesinde 1/X/2 seç", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Kupon kaydedildi · ${coupon.legs.size} maç · kombine ~%${coupon.combinedChance}",
                    Toast.LENGTH_LONG
                ).show()
                refreshSlipBar()
                startActivity(Intent(this, CouponsActivity::class.java))
            }
        }

        swipe.setOnRefreshListener { loadMatches(showSpinner = true) }
        updateBanner()
        refreshSlipBar()
        loadMatches(showSpinner = true)
        startLiveTicker()
    }

    override fun onResume() {
        super.onResume()
        updateBanner()
        refreshSlipBar()
        // Detaydan dönünce sabitlenmiş oranları listeye yansıt + seçimleri boya
        if (allMatches.isNotEmpty()) {
            allMatches = MatchOddsCache.mergeInto(allMatches)
            renderList()
        } else {
            adapter.refreshSelections()
        }
        if (ApiKeyStore.hasKey(this) && allMatches.isEmpty()) {
            loadMatches(showSpinner = true)
        }
    }

    override fun onDestroy() {
        liveJob?.cancel()
        super.onDestroy()
    }

    private fun refreshSlipBar() {
        val slip = CouponStore.loadSlip(this)
        slipInfo.text = if (slip.isEmpty()) {
            "Kupon boş · Olmamış maçlarda 1/X/2 kutusu seç"
        } else {
            var p = 1.0
            slip.forEach { leg -> p *= leg.predictedPercent.coerceIn(1, 99) / 100.0 }
            val chance = (p * 100).toInt().coerceIn(1, 99)
            "Kupon: ${slip.size} maç · kombine ~%$chance · Kaydet / Kuponlarım"
        }
    }

    private fun updateBanner() {
        if (ApiKeyStore.hasKey(this)) {
            apiBanner.text = "Gerçek veri bağlı · detayda oran bir kez netleşir ve sabitlenir"
            apiBanner.setBackgroundColor(0x332E7D32)
        } else {
            apiBanner.text = "API anahtarı yok — gerçek maçlar için buraya dokun"
            apiBanner.setBackgroundColor(0x33C62828)
        }
    }

    private fun setFilter(value: Filter) {
        filter = value
        renderList()
    }

    private fun loadMatches(showSpinner: Boolean) {
        if (showSpinner) swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MatchRepository.loadDailyMatches(this@MainActivity)
            }
            allMatches = MatchOddsCache.mergeInto(result.matches)
            val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date())
            val liveCount = allMatches.count { it.status == MatchStatus.LIVE }
            headerMeta.text = if (result.error != null && result.matches.isEmpty()) {
                result.error
            } else {
                "$date · ${allMatches.size} maç · $liveCount canlı · ${result.sourceNote}"
            }
            updateBanner()
            renderList()
            swipe.isRefreshing = false
        }
    }

    private fun renderList() {
        val filtered = when (filter) {
            Filter.ALL -> allMatches
            Filter.LIVE -> allMatches.filter { it.status == MatchStatus.LIVE }
            Filter.UPCOMING -> allMatches.filter { it.status == MatchStatus.UPCOMING }
            Filter.FINISHED -> allMatches.filter { it.status == MatchStatus.FINISHED }
        }
        adapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when {
            !ApiKeyStore.hasKey(this) ->
                "Gerçek sonuçlar için Ayarlar’dan API-Football anahtarı gir."
            filter == Filter.LIVE -> "Şu an canlı maç yok."
            filter == Filter.UPCOMING -> "Şu an olmamış (başlamamış) maç yok. Tümü veya Canlı’ya bak."
            filter == Filter.FINISHED -> "Henüz biten maç yok."
            else -> "Bugün için maç gelmedi veya API limiti doldu."
        }
    }

    private fun startLiveTicker() {
        liveJob?.cancel()
        liveJob = lifecycleScope.launch {
            while (isActive) {
                delay(45_000)
                if (!ApiKeyStore.hasKey(this@MainActivity)) continue
                if (allMatches.isNotEmpty()) {
                    allMatches = withContext(Dispatchers.IO) {
                        MatchOddsCache.mergeInto(
                            MatchRepository.refreshLive(this@MainActivity, allMatches)
                        )
                    }
                    val liveCount = allMatches.count { it.status == MatchStatus.LIVE }
                    val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date())
                    headerMeta.text = "$date · ${allMatches.size} maç · $liveCount canlı · canlı güncellendi"
                    renderList()
                }
            }
        }
    }
}
