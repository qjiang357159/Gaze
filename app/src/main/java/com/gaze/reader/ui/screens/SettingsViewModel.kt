package com.gaze.reader.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gaze.reader.backup.BackupEntry
import com.gaze.reader.backup.BackupInfo
import com.gaze.reader.backup.BackupResult
import com.gaze.reader.backup.MissingReport
import com.gaze.reader.backup.RestoreMode
import com.gaze.reader.backup.autoBackupFileName
import com.gaze.reader.backup.defaultBackupFileName
import com.gaze.reader.core.AutoBackupInterval
import com.gaze.reader.core.FitMode
import com.gaze.reader.core.FileNameParser
import com.gaze.reader.core.LibraryLayout
import com.gaze.reader.core.LibraryView
import com.gaze.reader.core.ReadMode
import com.gaze.reader.core.ReaderBackground
import com.gaze.reader.core.RenderMode
import com.gaze.reader.core.ScreenOrientation
import com.gaze.reader.core.SortOrder
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.core.ThemeVariant
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.FolderEntity
import com.gaze.reader.data.db.LibraryStats
import com.gaze.reader.data.prefs.GazeSettings
import com.gaze.reader.scan.HealthReport
import com.gaze.reader.scan.ScanPhase
import com.gaze.reader.scan.ScanProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页（含全部二级页）共用的 ViewModel。
 *
 * 整个设置树**只有一个实例** —— 由 `GazeRoot` 持有并往下传，而不是每个子路由
 * 各建一个。理由很实际：扫描进度、提示条、待恢复的备份都是跨子页的状态，
 * 每页一个实例会让「在媒体库页启动扫描、返回主页面时进度条消失」。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<GazeSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GazeSettings())

    val stats: StateFlow<LibraryStats?> = container.comicRepository.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val folders: StateFlow<List<FolderEntity>> = container.folderRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 待匹配（找不到文件）的漫画数量 —— 设置页据此决定要不要露出「媒体匹配」入口。 */
    val missingCount: StateFlow<Int> = container.comicRepository.observeMissing()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _scan = MutableStateFlow<ScanProgress?>(null)
    val scan: StateFlow<ScanProgress?> = _scan

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _pendingRestore = MutableStateFlow<Pair<Uri, BackupInfo>?>(null)

    /** 用户选好备份文件后、确认恢复方式之前，先展示它的摘要。 */
    val pendingRestore: StateFlow<Pair<Uri, BackupInfo>?> = _pendingRestore

    private var scanJob: Job? = null

    // ------------------------------------------------------------ 文件健康检查

    private val _health = MutableStateFlow<HealthReport?>(null)
    val health: StateFlow<HealthReport?> = _health

    private var healthJob: Job? = null

    /**
     * 逐个打开归档做体检。
     *
     * 只报告问题，**不自动改动任何数据** —— 「发现文件坏了」和「要不要把它从库里去掉」
     * 是两个决定，后者必须由用户来做。
     */
    fun startHealthCheck() {
        if (healthJob?.isActive == true) return
        healthJob = viewModelScope.launch {
            container.healthChecker.check().collect { _health.value = it }
            _health.value?.takeIf { it.finished }?.let { report ->
                _message.value = if (report.issues.isEmpty()) {
                    "检查完成：${report.total} 本全部可正常读取"
                } else {
                    "检查完成：${report.total} 本中发现 ${report.issues.size} 本有问题"
                }
            }
        }
    }

    fun cancelHealthCheck() {
        healthJob?.cancel()
        healthJob = null
        _health.value = null
    }

    fun dismissHealthReport() {
        if (healthJob?.isActive != true) _health.value = null
    }

    // ------------------------------------------------------------ 外观

    fun setThemeMode(value: ThemeMode) = viewModelScope.launch { container.settingsRepository.setThemeMode(value) }
    fun setThemeVariant(value: ThemeVariant) = viewModelScope.launch { container.settingsRepository.setThemeVariant(value) }
    fun setThemeSeed(value: Int) = viewModelScope.launch { container.settingsRepository.setThemeSeed(value) }
    fun setReaderBackground(value: ReaderBackground) = viewModelScope.launch { container.settingsRepository.setReaderBackground(value) }

    // ------------------------------------------------------------ 阅读

    fun setDefaultReadMode(value: ReadMode) = viewModelScope.launch { container.settingsRepository.setDefaultReadMode(value) }
    fun setDefaultFitMode(value: FitMode) = viewModelScope.launch { container.settingsRepository.setDefaultFitMode(value) }
    fun setRenderMode(value: RenderMode) = viewModelScope.launch { container.settingsRepository.setRenderMode(value) }
    fun setScreenOrientation(value: ScreenOrientation) = viewModelScope.launch { container.settingsRepository.setScreenOrientation(value) }
    fun setBrightnessFollowSystem(value: Boolean) = viewModelScope.launch { container.settingsRepository.setBrightnessFollowSystem(value) }
    fun setBrightness(value: Float) = viewModelScope.launch { container.settingsRepository.setBrightness(value) }
    fun setVolumeKeyPaging(value: Boolean) = viewModelScope.launch { container.settingsRepository.setVolumeKeyPaging(value) }
    fun setTapToPage(value: Boolean) = viewModelScope.launch { container.settingsRepository.setTapToPage(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { container.settingsRepository.setKeepScreenOn(value) }
    fun setShowPageNumber(value: Boolean) = viewModelScope.launch { container.settingsRepository.setShowPageNumber(value) }

    // ------------------------------------------------------------ 书架

    fun setLibraryView(value: LibraryView) = viewModelScope.launch { container.settingsRepository.setLibraryView(value) }
    fun setLibraryLayout(value: LibraryLayout) = viewModelScope.launch { container.settingsRepository.setLibraryLayout(value) }
    fun setSortOrder(value: SortOrder) = viewModelScope.launch { container.settingsRepository.setSortOrder(value) }

    // ------------------------------------------------------------ 文件名解析

    fun setParseAuthorBySeparator(value: Boolean) =
        viewModelScope.launch { container.settingsRepository.setParseAuthorBySeparator(value) }

    fun setAuthorSeparator(value: String) =
        viewModelScope.launch { container.settingsRepository.setAuthorSeparator(value) }

    fun setParseAuthorByBracket(value: Boolean) =
        viewModelScope.launch { container.settingsRepository.setParseAuthorByBracket(value) }

    fun setAuthorBracket(value: String) =
        viewModelScope.launch { container.settingsRepository.setAuthorBracket(value) }

    /**
     * 按当前规则重新解析整个库的文件名。
     *
     * 这是「改了解析规则之后怎么让老数据生效」的出口 —— 没有它，
     * 用户改了分隔符只能删库重扫。手改过的标题 / 作者由 SQL 里的
     * `manualTitle / manualAuthor` 保护，不会被覆盖。
     */
    fun reparseFileNames() {
        viewModelScope.launch {
            val current = container.settingsRepository.current()
            val rules = FileNameParser.Rules(
                bySeparator = current.parseAuthorBySeparator,
                separator = current.authorSeparator,
                byBracket = current.parseAuthorByBracket,
                bracket = current.authorBracket
            )
            val comics = container.comicRepository.getAllPresent()
            var changed = 0
            comics.forEach { comic ->
                val parsed = FileNameParser.parse(comic.fileName, rules)
                if (rules.parseAuthor) {
                    container.comicRepository.refreshParsedNames(comic.id, parsed.title, parsed.author)
                } else {
                    container.comicRepository.refreshParsedTitle(comic.id, parsed.title)
                }
                changed++
            }
            _message.value = if (changed == 0) {
                "库里还没有漫画"
            } else {
                "已按当前规则重新解析 $changed 本的文件名（手动改过的不会被覆盖）"
            }
        }
    }

    // ------------------------------------------------------------ 媒体库

    fun setAutoScan(value: Boolean) = viewModelScope.launch { container.settingsRepository.setAutoScanOnLaunch(value) }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            container.folderRepository.add(uri.toString())
            _message.value = "已授权文件夹，开始扫描…"
            startScan()
        }
    }

    fun removeFolder(uri: String) {
        viewModelScope.launch {
            container.folderRepository.remove(uri)
            // 取消授权后，该目录下的漫画标记为失效（**不删除**任何数据）
            container.comicRepository.setMissingByUriPrefix(uri.substringBeforeLast("/document/"), true)
            _message.value = "已取消授权。已导入的漫画不会删除，只是不再自动扫描。"
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                container.scanner.scanAll().collect { progress ->
                    _scan.value = progress
                    if (progress.phase == ScanPhase.DONE) {
                        _message.value = "扫描完成：新增 ${progress.added}，更新 ${progress.updated}，失效 ${progress.removed}"
                        delay(1_500)
                        _scan.value = null
                    }
                }
                container.coverGenerator.generateMissing().collect { }
                container.coverStore.trimToLimit(container.settingsRepository.current().coverCacheLimitMb)
            } finally {
                if (_scan.value?.phase != ScanPhase.DONE) _scan.value = null
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = null
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            container.coverStore.clear()
            container.comicRepository.getAllPresent().forEach {
                container.comicRepository.setCoverPath(it.id, null)
            }
            _message.value = "封面缓存已清空，下次打开书架会重新生成"
        }
    }

    // ------------------------------------------------------------ 备份

    fun suggestedBackupName(): String = defaultBackupFileName()

    fun exportBackup(target: Uri, displayName: String) {
        viewModelScope.launch {
            _message.value = "正在生成备份…"
            val result = container.backupRepository.export(target, displayName)
            _message.value = result.message
            if (result.success) refreshBackups()
        }
    }

    /** 选好文件后先看一眼是不是 Gaze 备份，再让用户选恢复方式。 */
    fun peekBackup(uri: Uri) {
        viewModelScope.launch { peekIncomingBackup(uri) }
    }

    /**
     * 校验一个外部带入的文件，是备份就挂起待恢复。
     *
     * 「打开方式」是通配注册的（见 AndroidManifest 的说明），任何本地文件都能把
     * Gaze 唤起来 —— 所以**必须先按内容判断再决定跳不跳备份页**：不加这一步，
     * 用户点开一张照片也会被拽进备份页，比找不到入口更让人困惑。
     *
     * @return null 表示是合法备份；否则是给用户看的原因。
     */
    suspend fun peekIncomingBackup(uri: Uri): String? {
        val info = container.backupRepository.peek(uri)
        if (info.valid) {
            _pendingRestore.value = uri to info
            return null
        }
        val reason = info.error ?: "无法读取该备份文件"
        _message.value = reason
        return reason
    }

    fun cancelPendingRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore(mode: RestoreMode) {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        viewModelScope.launch {
            _message.value = "正在恢复…"
            val result = container.backupRepository.restore(pending.first, mode)
            _message.value = result.message
            if (result.success) {
                // 恢复的明细留在页面上，而不是随提示条一起消失 ——
                // 「多少本待重新定位」是用户接下来要不要去媒体匹配页的依据。
                _lastRestore.value = result
                refreshBackups()
            }
        }
    }

    private val _lastRestore = MutableStateFlow<BackupResult?>(null)

    /** 上一次恢复的完整结果（成功才非空）。 */
    val lastRestore: StateFlow<BackupResult?> = _lastRestore

    fun dismissRestoreResult() {
        _lastRestore.value = null
    }

    /** 「恢复上次导出的备份」—— 绕开文件选择器，本机一定能走通。 */
    fun restoreLastExport() {
        val uriString = settings.value.lastExportUri
        if (uriString.isNullOrBlank()) {
            _message.value = "还没有导出过备份"
            return
        }
        peekBackup(Uri.parse(uriString))
    }

    fun forgetLastExport() {
        viewModelScope.launch {
            container.settingsRepository.forgetExport()
            _message.value = "已清除上次导出记录"
        }
    }

    // ------------------------------------------------------------ 备份文件夹

    private val _backups = MutableStateFlow<List<BackupEntry>>(emptyList())

    /** 指定备份文件夹里的历史备份，按时间倒序。 */
    val backups: StateFlow<List<BackupEntry>> = _backups

    private val _missingReport = MutableStateFlow<MissingReport?>(null)

    /** 文件匹配报告（核对完才非空）。 */
    val missingReport: StateFlow<MissingReport?> = _missingReport

    fun setBackupFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            container.settingsRepository.setBackupFolder(uri.toString(), displayName)
            _message.value = "已指定备份文件夹：$displayName"
            refreshBackups()
        }
    }

    fun clearBackupFolder() {
        viewModelScope.launch {
            container.settingsRepository.setBackupFolder(null, null)
            _backups.value = emptyList()
            _message.value = "已取消备份文件夹（已导出的备份文件不会被删除）"
        }
    }

    fun setAutoBackupInterval(value: AutoBackupInterval) =
        viewModelScope.launch { container.settingsRepository.setAutoBackupInterval(value) }

    fun setAutoBackupKeep(value: Int) =
        viewModelScope.launch { container.settingsRepository.setAutoBackupKeep(value) }

    fun refreshBackups() {
        viewModelScope.launch {
            _backups.value = runCatching { container.backupRepository.listBackups() }
                .getOrDefault(emptyList())
        }
    }

    /** 导出到已指定的备份文件夹，不必再过一遍系统文件选择器。 */
    fun exportToBackupFolder() {
        viewModelScope.launch {
            if (settings.value.backupFolderUri.isNullOrBlank()) {
                _message.value = "请先指定备份文件夹"
                return@launch
            }
            _message.value = "正在生成备份…"
            val result = container.backupRepository.exportToFolder(defaultBackupFileName())
            _message.value = result.message
            if (result.success) refreshBackups()
        }
    }

    /** 「立即备份」：走自动备份那条链路（文件名带 gaze-auto- 前缀，受保留份数约束）。 */
    fun backupNow() {
        viewModelScope.launch {
            _message.value = "正在生成备份…"
            val result = container.backupRepository.runAutoBackupIfDue(force = true)
            _message.value = result?.message ?: "请先指定备份文件夹"
            if (result?.success == true) refreshBackups()
        }
    }

    /** 从历史备份直接恢复：先看摘要，再让用户选恢复方式。 */
    fun restoreFromBackup(entry: BackupEntry) {
        peekBackup(Uri.parse(entry.uri))
    }

    /**
     * 文件匹配：核对某个备份里的漫画，本地还剩几本。
     *
     * 口径与恢复时完全一致，所以不会出现「这里说在、恢复时却说不在」。
     */
    fun verifyBackup(entry: BackupEntry) {
        viewModelScope.launch {
            _message.value = "正在核对文件…"
            _missingReport.value = container.backupRepository.verify(Uri.parse(entry.uri))
            _message.value = null
        }
    }

    fun dismissMissingReport() {
        _missingReport.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 自动备份的启动检查。
     *
     * 由 `MainActivity` 在 onCreate 里调一次 —— 判定「是否到点」的逻辑
     * 全在仓库里，这里只负责触发并把结果告知用户。
     */
    fun runAutoBackupIfDue() {
        viewModelScope.launch {
            val result = runCatching { container.backupRepository.runAutoBackupIfDue() }.getOrNull()
            if (result != null && result.success) {
                _message.value = "已自动备份：${result.message.removePrefix("备份已导出：")}"
                refreshBackups()
            }
        }
    }
}
