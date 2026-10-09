package com.gaze.reader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.repository.StatsData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 阅读统计（方案第二十五章 P2）。
 *
 * 所有聚合都在 [com.gaze.reader.data.repository.StatsRepository] 里做，
 * 这里只负责把它变成 UI 状态，以及处理「清空」这个唯一的写操作。
 */
class StatsViewModel(private val container: AppContainer) : ViewModel() {

    val data: StateFlow<StatsData> = container.statsRepository.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatsData()
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clear() {
        viewModelScope.launch {
            container.statsRepository.clear()
            _message.value = "统计数据已清空"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
