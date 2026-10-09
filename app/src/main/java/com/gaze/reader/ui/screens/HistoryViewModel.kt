package com.gaze.reader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ComicEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(container: AppContainer) : ViewModel() {

    /** 最近阅读，按时间倒序。只读，不需要写操作。 */
    val comics: StateFlow<List<ComicEntity>> = container.comicRepository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
