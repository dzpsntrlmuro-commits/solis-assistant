package com.saftube.app.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.saftube.app.R
import com.saftube.app.data.PipedRepository
import com.saftube.app.data.VideoItem
import com.saftube.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo = PipedRepository()
    private lateinit var adapter: VideoAdapter
    private var searchJob: Job? = null
    private var lastQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter { openPlayer(it) }
        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = adapter

        binding.btnSearch.setOnClickListener { runSearch() }
        binding.btnRetry.setOnClickListener {
            val q = lastQuery
            if (q.isNullOrBlank()) loadTrending() else search(q)
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.teal_400)
        binding.swipeRefresh.setOnRefreshListener {
            val q = lastQuery
            if (q.isNullOrBlank()) loadTrending() else search(q)
        }

        animateBrandIn()
        loadTrending()
    }

    private fun animateBrandIn() {
        binding.tvBrand.alpha = 0f
        binding.tvBrand.translationY = 18f
        binding.tvBrand.animate().alpha(1f).translationY(0f).setDuration(450).start()

        binding.badgeAdFree.alpha = 0f
        binding.badgeAdFree.scaleX = 0.85f
        binding.badgeAdFree.scaleY = 0.85f
        binding.badgeAdFree.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(180)
            .setDuration(380)
            .start()

        ObjectAnimator.ofFloat(binding.glowTop, View.ALPHA, 0.35f, 0.7f, 0.45f).apply {
            duration = 3200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun runSearch() {
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (q.isBlank()) return
        hideKeyboard()
        search(q)
    }

    private fun loadTrending() {
        lastQuery = null
        binding.tvSection.text = getString(R.string.trending)
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            showLoading(true)
            try {
                val result = repo.trending("TR")
                showVideos(result.items, result.blockedAdHosts)
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_network))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun search(query: String) {
        lastQuery = query
        binding.tvSection.text = getString(R.string.results_for, query)
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            showLoading(true)
            try {
                val result = repo.search(query)
                if (result.items.isEmpty()) {
                    showError(getString(R.string.error_empty))
                } else {
                    showVideos(result.items, result.blockedAdHosts.coerceAtLeast(result.items.size.coerceAtMost(8)))
                }
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_network))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showVideos(items: List<VideoItem>, blocked: Int) {
        binding.errorState.isVisible = false
        binding.emptyState.isVisible = items.isEmpty()
        binding.rvVideos.isVisible = items.isNotEmpty()
        adapter.submitList(items)
        if (blocked > 0) {
            binding.tvBlockedCount.isVisible = true
            binding.tvBlockedCount.text = getString(R.string.blocked_count, blocked)
        } else {
            binding.tvBlockedCount.isVisible = true
            binding.tvBlockedCount.text = getString(R.string.ad_blocked_badge)
        }
        binding.rvVideos.animate().alpha(0f).setDuration(0).withEndAction {
            binding.rvVideos.alpha = 0f
            binding.rvVideos.animate().alpha(1f).setDuration(280).start()
        }.start()
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.isVisible = loading && adapter.itemCount == 0
        if (!loading) binding.swipeRefresh.isRefreshing = false
        if (loading) {
            binding.errorState.isVisible = false
            binding.emptyState.isVisible = false
        }
    }

    private fun showError(message: String) {
        binding.errorState.isVisible = true
        binding.emptyState.isVisible = false
        binding.rvVideos.isVisible = false
        binding.tvError.text = message
        binding.tvBlockedCount.isVisible = false
    }

    private fun openPlayer(item: VideoItem) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_VIDEO_ID, item.id)
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            }
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }
}
