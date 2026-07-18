package com.matchpredictor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchpredictor.app.data.MatchRepository
import com.matchpredictor.app.data.models.FootballMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MatchViewModel : ViewModel() {

    private val repository = MatchRepository()

    private val _dailyMatches = MutableStateFlow<UiState<List<FootballMatch>>>(UiState.Loading)
    val dailyMatches: StateFlow<UiState<List<FootballMatch>>> = _dailyMatches

    private val _liveMatches = MutableStateFlow<UiState<List<FootballMatch>>>(UiState.Loading)
    val liveMatches: StateFlow<UiState<List<FootballMatch>>> = _liveMatches

    fun loadDailyMatches() {
        viewModelScope.launch {
            _dailyMatches.value = UiState.Loading
            repository.getDailyMatches().fold(
                onSuccess = { _dailyMatches.value = UiState.Success(it) },
                onFailure = { _dailyMatches.value = UiState.Error(it.message ?: "Veri alınamadı") }
            )
        }
    }

    fun loadLiveMatches() {
        viewModelScope.launch {
            _liveMatches.value = UiState.Loading
            repository.getLiveMatches().fold(
                onSuccess = { _liveMatches.value = UiState.Success(it) },
                onFailure = { _liveMatches.value = UiState.Error(it.message ?: "Veri alınamadı") }
            )
        }
    }

    sealed class UiState<out T> {
        object Loading : UiState<Nothing>()
        data class Success<T>(val data: T) : UiState<T>()
        data class Error(val message: String) : UiState<Nothing>()
    }
}
