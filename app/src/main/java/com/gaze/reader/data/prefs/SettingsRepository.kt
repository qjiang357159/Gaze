package com.gaze.reader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gaze.reader.core.AutoBackupInterval
import com.gaze.reader.core.FitMode
import com.gaze.reader.core.FileNameParser
import com.gaze.reader.core.LibraryFilter
import com.gaze.reader.core.LibraryLayout
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.ReadMode
import com.gaze.reader.core.ReaderBackground
import com.gaze.reader.core.RenderMode
import com.gaze.reader.core.ScreenOrientation
import com.gaze.reader.core.SortOrder
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.core.ThemeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.gazeDataStore: DataStore<Preferences> by preferencesDataStore(name = "gaze_settings")

/** 自定义主题的默认主色：印刷朱红。 */
const val DEFAULT_THEME_SEED: Int = 0xFFB4472F.toInt()

/**
 * 应用设置。
 *
 * 注意「阅读器背景」与「系统主题」是**两个独立设置**（文档第十六章）：
 * 漫画阅读时系统主题和阅读背景并不应该绑定 —— 用户完全可能白天用浅色系统、
 * 但阅读时仍然要黑色背景。所以 [readerBackground] 不跟随 [themeMode]。
 */
data class GazeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeVariant: ThemeVariant = ThemeVariant.PAPER,
    /** 自定义主题的主色（ARGB）。[ThemeVariant.CUSTOM] 时生效。 */
    val themeSeed: Int = DEFAULT_THEME_SEED,
    val readerBackground: ReaderBackground = ReaderBackground.BLACK,
    val defaultReadMode: ReadMode = ReadMode.SINGLE,
    val defaultFitMode: FitMode = FitMode.SCREEN,
    val renderMode: RenderMode = RenderMode.OPTIMIZED,
    val libraryLayout: LibraryLayout = LibraryLayout.GRID_2,
    val libraryView: LibraryView = LibraryView.ALL,
    val sortOrder: SortOrder = SortOrder.ADDED_DESC,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val autoScanOnLaunch: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val coverCacheLimitMb: Int = 256,

    // ---------------------------------------------------------- 阅读页控制
    /** 阅读页屏幕方向。 */
    val screenOrientation: ScreenOrientation = ScreenOrientation.SYSTEM,
    /** 亮度是否跟随系统；关闭时用 [brightness]。 */
    val brightnessFollowSystem: Boolean = true,
    /** 自定义亮度 0f..1f。 */
    val brightness: Float = 0.5f,
    /** 音量键翻页。 */
    val volumeKeyPaging: Boolean = false,

    // ---------------------------------------------------------- 文件名解析
    /**
     * 按**分隔符**识别作者：最后一个分隔符右侧的内容是作者。
     *
     * 沿用 v1.4.0 的 `parse_author_from_filename` 键，老用户的开关状态直接继承。
     */
    val parseAuthorBySeparator: Boolean = true,
    /** 分隔符，默认下划线。 */
    val authorSeparator: String = FileNameParser.DEFAULT_SEPARATOR,
    /** 按**括号**识别作者：第一对括号里的内容是作者。 */
    val parseAuthorByBracket: Boolean = false,
    /** 括号对，存成「开 + 闭」两个字符。 */
    val authorBracket: String = FileNameParser.DEFAULT_BRACKET,

    // ---------------------------------------------------------- 备份
    /** 上次导出的备份 uri —— 「恢复上次导出的备份」快捷入口用，绕开文件选择器。 */
    val lastExportUri: String? = null,
    val lastExportName: String? = null,
    val lastExportTime: Long = 0L,
    /** 指定的备份文件夹（tree uri）。 */
    val backupFolderUri: String? = null,
    val backupFolderName: String? = null,
    /** 自动备份频率。 */
    val autoBackupInterval: AutoBackupInterval = AutoBackupInterval.OFF,
    /** 自动备份保留份数。 */
    val autoBackupKeep: Int = 5,
    /** 上次自动备份时间，用于判断是否到点。 */
    val lastAutoBackupTime: Long = 0L
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_VARIANT = stringPreferencesKey("theme_variant")
        val THEME_SEED = intPreferencesKey("theme_seed")
        val READER_BACKGROUND = stringPreferencesKey("reader_background")
        val READ_MODE = stringPreferencesKey("read_mode")
        val FIT_MODE = stringPreferencesKey("fit_mode")
        val RENDER_MODE = stringPreferencesKey("render_mode")
        val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
        val LIBRARY_VIEW = stringPreferencesKey("library_view")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val LIBRARY_FILTER = stringPreferencesKey("library_filter")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan_on_launch")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SHOW_PAGE_NUMBER = booleanPreferencesKey("show_page_number")
        val COVER_CACHE_MB = intPreferencesKey("cover_cache_limit_mb")
        val SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")
        val BRIGHTNESS_FOLLOW = booleanPreferencesKey("brightness_follow_system")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        val PARSE_AUTHOR = booleanPreferencesKey("parse_author_from_filename")
        val AUTHOR_SEPARATOR = stringPreferencesKey("author_separator")
        val PARSE_AUTHOR_BRACKET = booleanPreferencesKey("parse_author_by_bracket")
        val AUTHOR_BRACKET = stringPreferencesKey("author_bracket")
        val LAST_EXPORT_URI = stringPreferencesKey("last_export_uri")
        val LAST_EXPORT_NAME = stringPreferencesKey("last_export_name")
        val LAST_EXPORT_TIME = longPreferencesKey("last_export_time")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val BACKUP_FOLDER_NAME = stringPreferencesKey("backup_folder_name")
        val AUTO_BACKUP_INTERVAL = stringPreferencesKey("auto_backup_interval")
        val AUTO_BACKUP_KEEP = intPreferencesKey("auto_backup_keep")
        val LAST_AUTO_BACKUP_TIME = longPreferencesKey("last_auto_backup_time")
    }

    val settings: Flow<GazeSettings> = context.gazeDataStore.data.map { p ->
        GazeSettings(
            themeMode = ThemeMode.of(p[Keys.THEME_MODE]),
            themeVariant = ThemeVariant.of(p[Keys.THEME_VARIANT]),
            themeSeed = p[Keys.THEME_SEED] ?: DEFAULT_THEME_SEED,
            readerBackground = ReaderBackground.of(p[Keys.READER_BACKGROUND]),
            defaultReadMode = ReadMode.of(p[Keys.READ_MODE]),
            defaultFitMode = FitMode.of(p[Keys.FIT_MODE]),
            renderMode = RenderMode.of(p[Keys.RENDER_MODE]),
            libraryLayout = LibraryLayout.of(p[Keys.LIBRARY_LAYOUT]),
            libraryView = LibraryView.of(p[Keys.LIBRARY_VIEW]),
            sortOrder = SortOrder.of(p[Keys.SORT_ORDER]),
            libraryFilter = LibraryFilter.of(p[Keys.LIBRARY_FILTER]),
            autoScanOnLaunch = p[Keys.AUTO_SCAN] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            showPageNumber = p[Keys.SHOW_PAGE_NUMBER] ?: true,
            coverCacheLimitMb = p[Keys.COVER_CACHE_MB] ?: 256,
            screenOrientation = ScreenOrientation.of(p[Keys.SCREEN_ORIENTATION]),
            brightnessFollowSystem = p[Keys.BRIGHTNESS_FOLLOW] ?: true,
            brightness = p[Keys.BRIGHTNESS] ?: 0.5f,
            volumeKeyPaging = p[Keys.VOLUME_KEY_PAGING] ?: false,
            parseAuthorBySeparator = p[Keys.PARSE_AUTHOR] ?: true,
            authorSeparator = p[Keys.AUTHOR_SEPARATOR]
                ?: FileNameParser.DEFAULT_SEPARATOR,
            parseAuthorByBracket = p[Keys.PARSE_AUTHOR_BRACKET] ?: false,
            authorBracket = p[Keys.AUTHOR_BRACKET]
                ?: FileNameParser.DEFAULT_BRACKET,
            lastExportUri = p[Keys.LAST_EXPORT_URI],
            lastExportName = p[Keys.LAST_EXPORT_NAME],
            lastExportTime = p[Keys.LAST_EXPORT_TIME] ?: 0L,
            backupFolderUri = p[Keys.BACKUP_FOLDER_URI],
            backupFolderName = p[Keys.BACKUP_FOLDER_NAME],
            autoBackupInterval = AutoBackupInterval.of(p[Keys.AUTO_BACKUP_INTERVAL]),
            autoBackupKeep = p[Keys.AUTO_BACKUP_KEEP] ?: 5,
            lastAutoBackupTime = p[Keys.LAST_AUTO_BACKUP_TIME] ?: 0L
        )
    }

    suspend fun current(): GazeSettings = settings.first()

    suspend fun setThemeMode(value: ThemeMode) = put(Keys.THEME_MODE, value.key)
    suspend fun setThemeVariant(value: ThemeVariant) = put(Keys.THEME_VARIANT, value.key)

    suspend fun setThemeSeed(value: Int) {
        context.gazeDataStore.edit { it[Keys.THEME_SEED] = value }
    }

    suspend fun setReaderBackground(value: ReaderBackground) = put(Keys.READER_BACKGROUND, value.key)
    suspend fun setDefaultReadMode(value: ReadMode) = put(Keys.READ_MODE, value.key)
    suspend fun setDefaultFitMode(value: FitMode) = put(Keys.FIT_MODE, value.key)
    suspend fun setRenderMode(value: RenderMode) = put(Keys.RENDER_MODE, value.key)
    suspend fun setLibraryLayout(value: LibraryLayout) = put(Keys.LIBRARY_LAYOUT, value.key)
    suspend fun setLibraryView(value: LibraryView) = put(Keys.LIBRARY_VIEW, value.key)
    suspend fun setSortOrder(value: SortOrder) = put(Keys.SORT_ORDER, value.key)
    suspend fun setLibraryFilter(value: LibraryFilter) = put(Keys.LIBRARY_FILTER, value.key)
    suspend fun setScreenOrientation(value: ScreenOrientation) = put(Keys.SCREEN_ORIENTATION, value.key)

    suspend fun setAutoScanOnLaunch(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.AUTO_SCAN] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }
    }

    suspend fun setShowPageNumber(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.SHOW_PAGE_NUMBER] = value }
    }

    suspend fun setCoverCacheLimitMb(value: Int) {
        context.gazeDataStore.edit { it[Keys.COVER_CACHE_MB] = value.coerceIn(32, 2048) }
    }

    suspend fun setBrightnessFollowSystem(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.BRIGHTNESS_FOLLOW] = value }
    }

    suspend fun setBrightness(value: Float) {
        context.gazeDataStore.edit { it[Keys.BRIGHTNESS] = value.coerceIn(0.05f, 1f) }
    }

    suspend fun setVolumeKeyPaging(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.VOLUME_KEY_PAGING] = value }
    }

    suspend fun setParseAuthorBySeparator(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.PARSE_AUTHOR] = value }
    }

    suspend fun setAuthorSeparator(value: String) {
        // 空分隔符会让解析退化成「作者 = 整个文件名」，直接拒绝
        if (value.isEmpty()) return
        context.gazeDataStore.edit { it[Keys.AUTHOR_SEPARATOR] = value }
    }

    suspend fun setParseAuthorByBracket(value: Boolean) {
        context.gazeDataStore.edit { it[Keys.PARSE_AUTHOR_BRACKET] = value }
    }

    suspend fun setAuthorBracket(value: String) {
        if (FileNameParser.sanitizeBracket(value) == null) return
        context.gazeDataStore.edit { it[Keys.AUTHOR_BRACKET] = value }
    }

    suspend fun rememberExport(uri: String, name: String) {
        context.gazeDataStore.edit {
            it[Keys.LAST_EXPORT_URI] = uri
            it[Keys.LAST_EXPORT_NAME] = name
            it[Keys.LAST_EXPORT_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun forgetExport() {
        context.gazeDataStore.edit {
            it.remove(Keys.LAST_EXPORT_URI)
            it.remove(Keys.LAST_EXPORT_NAME)
            it.remove(Keys.LAST_EXPORT_TIME)
        }
    }

    // ------------------------------------------------------------ 备份文件夹 / 自动备份

    suspend fun setBackupFolder(uri: String?, name: String?) {
        context.gazeDataStore.edit {
            if (uri.isNullOrBlank()) {
                it.remove(Keys.BACKUP_FOLDER_URI)
                it.remove(Keys.BACKUP_FOLDER_NAME)
            } else {
                it[Keys.BACKUP_FOLDER_URI] = uri
                it[Keys.BACKUP_FOLDER_NAME] = name.orEmpty()
            }
        }
    }

    suspend fun setAutoBackupInterval(value: AutoBackupInterval) =
        put(Keys.AUTO_BACKUP_INTERVAL, value.key)

    suspend fun setAutoBackupKeep(value: Int) {
        context.gazeDataStore.edit { it[Keys.AUTO_BACKUP_KEEP] = value.coerceIn(1, 50) }
    }

    suspend fun rememberAutoBackup(time: Long) {
        context.gazeDataStore.edit { it[Keys.LAST_AUTO_BACKUP_TIME] = time }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        context.gazeDataStore.edit { it[key] = value }
    }
}
