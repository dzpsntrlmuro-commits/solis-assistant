package com.temiztube.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.temiztube.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: VideoAdapter
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter { video ->
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, video.url)
                    putExtra(PlayerActivity.EXTRA_TITLE, video.title)
                    putExtra(PlayerActivity.EXTRA_UPLOADER, video.uploader)
                }
            )
        }

        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        // Upper search → Murotube / YouTube (unchanged)
        binding.searchButton.setOnClickListener { submitTubeSearch(binding.searchInput) }
        binding.landingSearchButton.setOnClickListener { submitTubeSearch(binding.landingSearchInput) }
        bindSearchAction(binding.searchInput) { submitTubeSearch(it) }
        bindSearchAction(binding.landingSearchInput) { submitTubeSearch(it) }

        // Lower search → film / video results
        binding.filmSearchButton.setOnClickListener { submitFilmSearch(binding.filmSearchInput) }
        binding.landingFilmSearchButton.setOnClickListener { submitFilmSearch(binding.landingFilmSearchInput) }
        bindSearchAction(binding.filmSearchInput) { submitFilmSearch(it) }
        bindSearchAction(binding.landingFilmSearchInput) { submitFilmSearch(it) }

        binding.trendingButton.setOnClickListener {
            lastQuery = ""
            showBrowsingChrome()
            viewModel.loadTrending()
        }
        binding.downloadsButton.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        binding.landingDownloadsButton.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        binding.retryButton.setOnClickListener {
            if (lastQuery.isBlank()) viewModel.loadTrending() else viewModel.search(lastQuery)
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (lastQuery.isBlank()) viewModel.loadTrending() else viewModel.search(lastQuery)
        }

        animateLanding()

        lifecycleScope.launch {
            viewModel.state.collectLatest { state -> render(state) }
        }
    }

    private fun bindSearchAction(field: EditText, onSearch: (EditText) -> Unit) {
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearch(field)
                true
            } else {
                false
            }
        }
    }

    private fun submitTubeSearch(source: EditText) {
        val query = source.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return
        lastQuery = query
        binding.searchInput.setText(query)
        binding.landingSearchInput.setText(query)
        hideKeyboard()
        showBrowsingChrome()
        viewModel.search(query)
    }

    private fun submitFilmSearch(source: EditText) {
        val query = source.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return
        binding.filmSearchInput.setText(query)
        binding.landingFilmSearchInput.setText(query)
        hideKeyboard()
        startActivity(
            Intent(this, FilmSearchActivity::class.java).apply {
                putExtra(FilmSearchActivity.EXTRA_QUERY, query)
            }
        )
    }

    private fun showBrowsingChrome() {
        binding.landingHero.isVisible = false
        binding.compactHeader.isVisible = true
        binding.swipeRefresh.isVisible = true
    }

    private fun showLanding() {
        binding.landingHero.isVisible = true
        binding.compactHeader.isVisible = false
        binding.swipeRefresh.isVisible = false
    }

    private fun render(state: SearchUiState) {
        binding.swipeRefresh.isRefreshing = false

        when (state) {
            is SearchUiState.Empty -> {
                showLanding()
                binding.loading.isVisible = false
                binding.errorState.isVisible = false
                binding.resultsList.isVisible = false
            }

            is SearchUiState.Loading -> {
                showBrowsingChrome()
                binding.loading.isVisible = true
                binding.errorState.isVisible = false
                binding.resultsList.isVisible = false
            }

            is SearchUiState.Success -> {
                showBrowsingChrome()
                binding.loading.isVisible = false
                binding.errorState.isVisible = false
                binding.resultsList.isVisible = true
                adapter.submitList(state.items)
            }

            is SearchUiState.Error -> {
                showBrowsingChrome()
                binding.loading.isVisible = false
                binding.errorState.isVisible = true
                binding.resultsList.isVisible = false
                binding.errorText.text = state.message
            }
        }
    }

    private fun animateLanding() {
        val logo = binding.heroLogo
        val brand = binding.brandTitle
        logo.alpha = 0f
        logo.scaleX = 0.86f
        logo.scaleY = 0.86f
        brand.alpha = 0f
        brand.translationY = 18f

        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(520)
            .setInterpolator(DecelerateInterpolator())
            .start()
        brand.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(120)
            .setDuration(480)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            ?: imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
