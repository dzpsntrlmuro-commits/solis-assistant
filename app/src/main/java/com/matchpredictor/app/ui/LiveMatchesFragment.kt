package com.matchpredictor.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.matchpredictor.app.R
import com.matchpredictor.app.data.models.FootballMatch
import kotlinx.coroutines.launch

class LiveMatchesFragment : Fragment() {

    private val viewModel: MatchViewModel by activityViewModels()
    private lateinit var adapter: MatchAdapter
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            viewModel.loadLiveMatches()
            refreshHandler.postDelayed(this, 60_000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_matches, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvMatches)
        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val tvError = view.findViewById<TextView>(R.id.tvError)

        adapter = MatchAdapter { openDetail(it) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        swipeRefresh.setOnRefreshListener { viewModel.loadLiveMatches() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveMatches.collect { state ->
                    when (state) {
                        is MatchViewModel.UiState.Loading -> {
                            progressBar.visibility = View.VISIBLE
                            swipeRefresh.isRefreshing = false
                        }
                        is MatchViewModel.UiState.Success -> {
                            progressBar.visibility = View.GONE
                            swipeRefresh.isRefreshing = false
                            tvError.visibility = View.GONE
                            adapter.submitList(state.data)
                            tvEmpty.visibility = if (state.data.isEmpty()) View.VISIBLE else View.GONE
                            tvEmpty.text = getString(R.string.no_live_matches)
                        }
                        is MatchViewModel.UiState.Error -> {
                            progressBar.visibility = View.GONE
                            swipeRefresh.isRefreshing = false
                            tvError.visibility = View.VISIBLE
                            tvError.text = state.message
                        }
                    }
                }
            }
        }

        viewModel.loadLiveMatches()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun openDetail(match: FootballMatch) {
        startActivity(Intent(requireContext(), MatchDetailActivity::class.java).apply {
            putExtra(MatchDetailActivity.EXTRA_MATCH_ID, match.id)
            putExtra(MatchDetailActivity.EXTRA_HOME, match.homeTeam)
            putExtra(MatchDetailActivity.EXTRA_AWAY, match.awayTeam)
        })
    }
}
