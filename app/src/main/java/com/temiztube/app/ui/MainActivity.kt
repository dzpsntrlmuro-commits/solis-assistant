package com.temiztube.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

        binding.searchButton.setOnClickListener { submitSearch() }
        binding.trendingButton.setOnClickListener {
            lastQuery = ""
            viewModel.loadTrending()
        }
        binding.retryButton.setOnClickListener {
            if (lastQuery.isBlank()) viewModel.loadTrending() else viewModel.search(lastQuery)
        }
        binding.swipeRefresh.setOnRefreshListener {
            if (lastQuery.isBlank()) viewModel.loadTrending() else viewModel.search(lastQuery)
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }

        lifecycleScope.launch {
            viewModel.state.collectLatest { state -> render(state) }
        }
    }

    private fun submitSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return
        lastQuery = query
        hideKeyboard()
        viewModel.search(query)
    }

    private fun render(state: SearchUiState) {
        binding.swipeRefresh.isRefreshing = false
        binding.loading.isVisible = state is SearchUiState.Loading
        binding.errorState.isVisible = state is SearchUiState.Error
        binding.emptyState.isVisible = state is SearchUiState.Empty
        binding.resultsList.isVisible = state is SearchUiState.Success

        when (state) {
            is SearchUiState.Success -> adapter.submitList(state.items)
            is SearchUiState.Error -> binding.errorText.text = state.message
            else -> Unit
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            ?: imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
