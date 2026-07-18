package com.temiztube.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.temiztube.app.data.YoutubeRepository
import com.temiztube.app.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Empty : SearchUiState
    data object Loading : SearchUiState
    data class Success(val items: List<VideoItem>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(
    private val repository: YoutubeRepository = YoutubeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Empty)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun search(query: String) {
        viewModelScope.launch {
            _state.value = SearchUiState.Loading
            runCatching { repository.search(query) }
                .onSuccess { items ->
                    _state.value = if (items.isEmpty()) {
                        SearchUiState.Error("Sonuç bulunamadı")
                    } else {
                        SearchUiState.Success(items)
                    }
                }
                .onFailure { error ->
                    _state.value = SearchUiState.Error(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Bağlantı hatası. İnterneti kontrol edip tekrar dene."
                    )
                }
        }
    }

    fun loadTrending() {
        viewModelScope.launch {
            _state.value = SearchUiState.Loading
            runCatching { repository.trending() }
                .onSuccess { items ->
                    _state.value = if (items.isEmpty()) {
                        SearchUiState.Error("Popüler videolar alınamadı")
                    } else {
                        SearchUiState.Success(items)
                    }
                }
                .onFailure { error ->
                    _state.value = SearchUiState.Error(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Bağlantı hatası. İnterneti kontrol edip tekrar dene."
                    )
                }
        }
    }
}
