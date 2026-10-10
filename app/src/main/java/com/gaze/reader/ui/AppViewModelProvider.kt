package com.gaze.reader.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gaze.reader.GazeApp
import com.gaze.reader.ui.screens.DetailViewModel
import com.gaze.reader.ui.screens.FavoritesViewModel
import com.gaze.reader.ui.screens.HistoryViewModel
import com.gaze.reader.ui.screens.LibraryViewModel
import com.gaze.reader.ui.screens.MissingViewModel
import com.gaze.reader.ui.screens.ReaderViewModel
import com.gaze.reader.ui.screens.SettingsViewModel
import com.gaze.reader.ui.screens.StatsViewModel
import com.gaze.reader.ui.screens.TagDetailViewModel
import com.gaze.reader.ui.screens.TagsViewModel

/**
 * ViewModel 工厂。
 *
 * 注意：**每新增一个 ViewModel 都必须在这里补一行 `initializer`**。
 * 漏注册不会编译报错，而是等用户点到那个页面时抛
 * `IllegalArgumentException: No initializer set for given class`。
 */
object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer { LibraryViewModel(app().container) }
        initializer { TagsViewModel(app().container) }
        initializer { FavoritesViewModel(app().container) }
        initializer { SettingsViewModel(app().container) }
        initializer { StatsViewModel(app().container) }
        initializer { HistoryViewModel(app().container) }
        initializer { DetailViewModel(app().container, createSavedStateHandle()) }
        initializer { TagDetailViewModel(app().container, createSavedStateHandle()) }
        initializer { ReaderViewModel(app().container, createSavedStateHandle()) }
        initializer { MissingViewModel(app().container) }
    }
}

private fun CreationExtras.app(): GazeApp = this[APPLICATION_KEY] as GazeApp
