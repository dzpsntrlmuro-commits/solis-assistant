package com.macsans.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.macsans.app.R
import com.macsans.app.adapter.MatchAdapter
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
    private lateinit var chipGroup: ChipGroup
    private lateinit var adapter: MatchAdapter

    private var allMatches: List<Match> = emptyList()
    private var filter: Filter = Filter.ALL
    private var liveJob: Job? = null

    enum class Filter { ALL, LIVE, UPCOMING, FINISHED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recyclerMatches)
        swipe = findViewById(R.id.swipeRefresh)
        emptyView = findViewById(R.id.txtEmpty)
        headerMeta = findViewById(R.id.txtHeaderMeta)
        chipGroup = findViewById(R.id.chipGroup)

        adapter = MatchAdapter { match ->
            val intent = Intent(this, MatchDetailActivity::class.java)
            intent.putExtra(MatchDetailActivity.EXTRA_MATCH_ID, match.id)
            MatchDetailActivity.cache = match
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Chip>(R.id.chipAll).setOnClickListener { setFilter(Filter.ALL) }
        findViewById<Chip>(R.id.chipLive).setOnClickListener { setFilter(Filter.LIVE) }
        findViewById<Chip>(R.id.chipUpcoming).setOnClickListener { setFilter(Filter.UPCOMING) }
        findViewById<Chip>(R.id.chipFinished).setOnClickListener { setFilter(Filter.FINISHED) }

        swipe.setOnRefreshListener { loadMatches(showSpinner = true) }
        loadMatches(showSpinner = true)
        startLiveTicker()
    }

    override fun onDestroy() {
        liveJob?.cancel()
        super.onDestroy()
    }

    private fun setFilter(value: Filter) {
        filter = value
        renderList()
    }

    private fun loadMatches(showSpinner: Boolean) {
        if (showSpinner) swipe.isRefreshing = true
        lifecycleScope.launch {
            val matches = withContext(Dispatchers.IO) {
                MatchRepository.loadDailyMatches()
            }
            allMatches = matches
            val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date())
            val liveCount = matches.count { it.status == MatchStatus.LIVE }
            headerMeta.text = "$date · ${matches.size} maç · $liveCount canlı"
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
        emptyView.text = when (filter) {
            Filter.LIVE -> "Şu an canlı maç yok. Tüm maçlara geçebilirsin."
            Filter.UPCOMING -> "Bugün için bekleyen maç kalmadı."
            Filter.FINISHED -> "Henüz biten maç yok."
            Filter.ALL -> "Maç listesi yüklenemedi."
        }
    }

    private fun startLiveTicker() {
        liveJob?.cancel()
        liveJob = lifecycleScope.launch {
            while (isActive) {
                delay(20_000)
                if (allMatches.any { it.status == MatchStatus.LIVE }) {
                    allMatches = withContext(Dispatchers.IO) {
                        MatchRepository.refreshLive(allMatches)
                    }
                    val liveCount = allMatches.count { it.status == MatchStatus.LIVE }
                    val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("tr")).format(Date())
                    headerMeta.text = "$date · ${allMatches.size} maç · $liveCount canlı · güncellendi"
                    renderList()
                }
            }
        }
    }
}
