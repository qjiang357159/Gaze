package com.gaze.reader.data

import android.content.Context
import com.gaze.reader.backup.BackupRepository
import com.gaze.reader.data.db.GazeDatabase
import com.gaze.reader.data.prefs.SettingsRepository
import com.gaze.reader.data.repository.ComicRepository
import com.gaze.reader.data.repository.FolderRepository
import com.gaze.reader.data.repository.StatsRepository
import com.gaze.reader.data.repository.TagRepository
import com.gaze.reader.image.CoverStore
import com.gaze.reader.scan.CoverEditor
import com.gaze.reader.scan.CoverGenerator
import com.gaze.reader.scan.LibraryHealthChecker
import com.gaze.reader.scan.LibraryScanner
import com.gaze.reader.scan.MediaRelocator

/**
 * 极简依赖容器。
 *
 * 刻意不引 Hilt / Koin —— Gaze 的依赖图很浅（一个数据库 + 几个仓库 + 两个后台任务），
 * 手写 `by lazy` 已经足够，还省掉一整套注解处理器与构建开销。
 * 「不要为了功能丰富牺牲轻量性」这条原则同样适用于工程结构。
 */
class AppContainer(context: Context) {

    /** 阅读器需要在 ViewModel 里直接打开归档，所以这里对外暴露应用上下文。 */
    val appContext: Context = context.applicationContext

    /**
     * 「启动时自动扫描」是否已经执行过。
     *
     * 放在容器里（进程级单例）而不是 ViewModel 里 —— ViewModel 会随导航
     * 被重建或恢复，而「启动扫描」的语义边界是**一次进程生命周期**。
     */
    @Volatile
    var launchScanDone: Boolean = false

    val database: GazeDatabase by lazy { GazeDatabase.get(appContext) }

    val comicRepository: ComicRepository by lazy {
        ComicRepository(
            comicDao = database.comicDao(),
            tagDao = database.tagDao(),
            statsDao = database.statsDao(),
            shelfCoverDao = database.shelfCoverDao()
        )
    }

    val tagRepository: TagRepository by lazy { TagRepository(database.tagDao()) }

    val statsRepository: StatsRepository by lazy {
        StatsRepository(database.statsDao(), database.comicDao())
    }

    val folderRepository: FolderRepository by lazy { FolderRepository(database.folderDao()) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val coverStore: CoverStore by lazy { CoverStore(appContext) }

    val scanner: LibraryScanner by lazy {
        LibraryScanner(appContext, comicRepository, folderRepository, settingsRepository)
    }

    val coverGenerator: CoverGenerator by lazy {
        CoverGenerator(appContext, comicRepository, coverStore)
    }

    /** 换封面：从漫画里选一页，或从本地挑一张图。 */
    val coverEditor: CoverEditor by lazy {
        CoverEditor(appContext, comicRepository, coverStore)
    }

    /** 文件健康检查：逐个打开归档，找出打不开或页数异常的漫画。 */
    val healthChecker: LibraryHealthChecker by lazy {
        LibraryHealthChecker(appContext, comicRepository)
    }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            context = appContext,
            comics = comicRepository,
            tags = tagRepository,
            folders = folderRepository,
            stats = statsRepository,
            settings = settingsRepository
        )
    }

    /** 媒体匹配：把「待重新定位」的记录重新绑回本机文件。 */
    val mediaRelocator: MediaRelocator by lazy {
        MediaRelocator(appContext, comicRepository, coverGenerator)
    }
}
