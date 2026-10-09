package com.gaze.reader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gaze.reader.core.SortOrder
import com.gaze.reader.data.AppContainer
import com.gaze.reader.data.db.ShelfGroup
import com.gaze.reader.data.prefs.GazeSettings
import com.gaze.reader.scan.persistUriPermission
import com.gaze.reader.ui.components.GazeIcons
import com.gaze.reader.ui.components.GazeTagPicker
import com.gaze.reader.ui.components.Hairline
import com.gaze.reader.ui.components.TagPickMode
import com.gaze.reader.ui.screens.AboutSettingsScreen
import com.gaze.reader.ui.screens.AppearanceSettingsScreen
import com.gaze.reader.ui.screens.BackupSettingsScreen
import com.gaze.reader.ui.screens.DetailScreen
import com.gaze.reader.ui.screens.DetailViewModel
import com.gaze.reader.ui.screens.FavoritesScreen
import com.gaze.reader.ui.screens.FavoritesViewModel
import com.gaze.reader.ui.screens.HistoryScreen
import com.gaze.reader.ui.screens.HistoryViewModel
import com.gaze.reader.ui.screens.LibraryScreen
import com.gaze.reader.ui.screens.LibrarySettingsScreen
import com.gaze.reader.ui.screens.LibraryViewModel
import com.gaze.reader.ui.screens.MediaSettingsScreen
import com.gaze.reader.ui.screens.NamingSettingsScreen
import com.gaze.reader.ui.screens.PageLoader
import com.gaze.reader.ui.screens.ReaderScreen
import com.gaze.reader.ui.screens.ReaderViewModel
import com.gaze.reader.ui.screens.ReadingSettingsScreen
import com.gaze.reader.ui.screens.SettingsScreen
import com.gaze.reader.ui.screens.SettingsViewModel
import com.gaze.reader.ui.screens.StatsScreen
import com.gaze.reader.ui.screens.StatsViewModel
import com.gaze.reader.ui.screens.TagDetailScreen
import com.gaze.reader.ui.screens.TagDetailViewModel
import com.gaze.reader.ui.screens.TagsScreen
import com.gaze.reader.ui.screens.TagsViewModel
import com.gaze.reader.ui.theme.gazeColors
import kotlinx.coroutines.launch

/** 页面转场时长。导航默认 700ms 太慢，统一压到 180ms。 */
private const val NAV_TRANSITION_MS = 180

/**
 * 退出页面的淡出时长，刻意比进入短。
 *
 * 两个页面同时半透明的时间越长，「两层叠在一起」的观感越明显 ——
 * 阅读页是满屏黑底，退出时叠在浅色书架上尤其割裂。
 * 让它先快速退场，再让新页面慢慢淡入，观感就干净得多。
 */
private const val NAV_FADE_OUT_MS = 90

object Routes {
    const val LIBRARY = "library"
    const val TAGS = "tags"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val READER = "reader/{comicId}"
    const val DETAIL = "detail/{comicId}"
    const val TAG_DETAIL = "tag/{tagId}"

    // 设置二级页。它们不在 TOP_LEVEL 里，所以进入后底部导航自动收起 ——
    // 二级页是「推入」的，不该和一级页共用同一层导航。
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_READING = "settings/reading"
    const val SETTINGS_LIBRARY = "settings/library"
    const val SETTINGS_MEDIA = "settings/media"
    const val SETTINGS_NAMING = "settings/naming"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_ABOUT = "settings/about"

    fun reader(id: Long) = "reader/$id"
    fun detail(id: Long) = "detail/$id"
    fun tagDetail(id: Long) = "tag/$id"

    /** 只有这三个进底部导航。 */
    val TOP_LEVEL = setOf(LIBRARY, TAGS, SETTINGS)
}

/**
 * 非沉浸页面的外壳：给状态栏让出内边距。
 *
 * 这个内边距**必须挂在页面自己身上，不能挂在 `NavHost` 上**。
 * 挂在 NavHost 上时，它的值随 `currentRoute` 变化，而返回手势一触发、
 * 或转场一结束，路由就会翻转 —— 于是整个 NavHost（连同正在淡出的阅读页）
 * 会瞬间被挤下一条状态栏的高度：上沿露出一条与正文颜色不同的横带，
 * 就是「退出阅读页时割裂分层」。挂在目的地内部之后，阅读页在整个淡出
 * 过程中都保持满屏，横带不会出现。
 */
@Composable
private fun ScreenInsets(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }
}

@Composable
fun GazeRoot(
    container: AppContainer,
    settings: GazeSettings,
    incomingBackup: Uri? = null,
    onIncomingBackupConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || currentRoute in Routes.TOP_LEVEL

    // 整个设置树共用一个 ViewModel：扫描进度、提示条、待恢复的备份都是跨子页的状态，
    // 每页各建一个实例会让「在媒体库页启动扫描、返回主页面时进度条消失」。
    val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)

    // 外部「打开方式」带进来的备份：**先按内容校验，是备份才跳转**。
    //
    // 「打开方式」是通配注册的（见 AndroidManifest 的说明），任何本地文件都能
    // 把 Gaze 唤起来。不加校验的话，点开一张照片也会被拽进备份页 ——
    // 那比找不到入口更让人困惑。不是备份就用一句 toast 说清楚，页面不动。
    val context = LocalContext.current
    LaunchedEffect(incomingBackup) {
        val uri = incomingBackup ?: return@LaunchedEffect
        onIncomingBackupConsumed()
        val reason = settingsViewModel.peekIncomingBackup(uri)
        if (reason == null) {
            navController.navigate(Routes.SETTINGS_BACKUP) { launchSingleTop = true }
        } else {
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            // 导航默认的淡入淡出是 **700ms**，在这个「点进去看」的节奏里
            // 明显拖沓 —— 每次跳转都要等大半秒才看清新页面。统一压到 180ms：
            // 足够让眼睛感到「这是一次转场」，又不至于挡住操作。
            enterTransition = { fadeIn(tween(NAV_TRANSITION_MS)) },
            exitTransition = { fadeOut(tween(NAV_FADE_OUT_MS)) },
            popEnterTransition = { fadeIn(tween(NAV_TRANSITION_MS)) },
            popExitTransition = { fadeOut(tween(NAV_FADE_OUT_MS)) },
            // 注意这里**没有** statusBarsPadding —— 见 [ScreenInsets]。
            modifier = Modifier.weight(1f)
        ) {
            composable(Routes.LIBRARY) {
                ScreenInsets {
                    LibraryRoute(
                        container = container,
                        navController = navController,
                        autoScan = settings.autoScanOnLaunch
                    )
                }
            }
            composable(Routes.TAGS) {
                ScreenInsets { TagsRoute(container = container, navController = navController) }
            }
            composable(Routes.FAVORITES) {
                ScreenInsets { FavoritesRoute(container = container, navController = navController) }
            }
            composable(Routes.SETTINGS) {
                ScreenInsets {
                    SettingsHubRoute(
                        container = container,
                        navController = navController,
                        viewModel = settingsViewModel
                    )
                }
            }
            composable(Routes.SETTINGS_APPEARANCE) {
                ScreenInsets {
                    SettingsAppearanceRoute(viewModel = settingsViewModel, navController = navController)
                }
            }
            composable(Routes.SETTINGS_READING) {
                ScreenInsets {
                    SettingsReadingRoute(viewModel = settingsViewModel, navController = navController)
                }
            }
            composable(Routes.SETTINGS_LIBRARY) {
                ScreenInsets {
                    SettingsLibraryRoute(viewModel = settingsViewModel, navController = navController)
                }
            }
            composable(Routes.SETTINGS_MEDIA) {
                ScreenInsets {
                    SettingsMediaRoute(viewModel = settingsViewModel, navController = navController)
                }
            }
            composable(Routes.SETTINGS_NAMING) {
                ScreenInsets {
                    SettingsNamingRoute(viewModel = settingsViewModel, navController = navController)
                }
            }
            composable(Routes.SETTINGS_BACKUP) {
                ScreenInsets {
                    SettingsBackupRoute(viewModel = settingsViewModel, navController = navController)
                }
            }
            composable(Routes.SETTINGS_ABOUT) {
                ScreenInsets { AboutSettingsScreen(onBack = { navController.popBackStack() }) }
            }
            composable(Routes.STATS) {
                ScreenInsets { StatsRoute(container = container, navController = navController) }
            }
            composable(Routes.HISTORY) {
                ScreenInsets { HistoryRoute(container = container, navController = navController) }
            }
            composable(Routes.DETAIL) {
                ScreenInsets { DetailRoute(container = container, navController = navController) }
            }
            composable(Routes.TAG_DETAIL) {
                ScreenInsets { TagDetailRoute(container = container, navController = navController) }
            }
            // 阅读页是沉浸式：自己铺满整屏，不给状态栏留位 —— 唯一不加外壳的目的地。
            composable(Routes.READER) {
                ReaderRoute(container = container, navController = navController)
            }
        }

        if (showBottomBar) {
            GazeBottomBar(
                currentRoute = currentRoute ?: Routes.LIBRARY,
                onSelect = { route ->
                    if (route != currentRoute) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}

// ==================================================================== 底部导航

private data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * 底部导航。
 *
 * 加图标不是为了好看 —— 纯文字标签在 11sp 上辨识度很低，用户扫一眼找不到
 * 「设置」在哪。图标给的是**形状记忆**，文字给的是**精确确认**，两者要一起在。
 */
@Composable
private fun GazeBottomBar(currentRoute: String, onSelect: (String) -> Unit) {
    val colors = gazeColors
    val items = listOf(
        NavItem(Routes.LIBRARY, "书架", GazeIcons.grid(2)),
        NavItem(Routes.TAGS, "标签", GazeIcons.Tag),
        NavItem(Routes.SETTINGS, "设置", GazeIcons.Gear)
    )

    Column(Modifier.fillMaxWidth()) {
        Hairline(strong = true)
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                // 顺序要紧：先让开系统导航栏，再给内容一个固定的 54dp ——
                // 反过来算出来的是「内容高度减去导航栏」，手势导航机型上会被压扁。
                .navigationBarsPadding()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = item.route == currentRoute
                val tint = if (selected) colors.ink else colors.inkFaint
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(20.dp)
                            .height(1.5.dp)
                            .background(if (selected) colors.accent else Color.Transparent)
                    )
                }
            }
        }
    }
}

// ==================================================================== 书架

@Composable
private fun LibraryRoute(
    container: AppContainer,
    navController: NavController,
    autoScan: Boolean
) {
    val context = LocalContext.current
    val viewModel: LibraryViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allTags by container.tagRepository.observeAllWithCount()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var tagPicker by remember { mutableStateOf(false) }

    // 点进某个书架文件夹时只记 key，组对象本身从最新状态里取 ——
    // 直接记住整个 ShelfGroup 会在封面或内容变化后变成一份过期快照。
    var openedGroupKey by remember { mutableStateOf<String?>(null) }
    val openedGroup: ShelfGroup? = openedGroupKey?.let { key ->
        state.groups.firstOrNull { it.key == key }
    }

    // 切换视图 / 搜索时自动退出文件夹，否则会停在一个已经不属于当前视图的组里
    LaunchedEffect(state.view, state.query) {
        openedGroupKey = null
    }

    // 启动自动扫描**每个进程只做一次**。
    //
    // LaunchedEffect 在每次回到书架页时都会重新执行（导航返回、底部 tab 切换
    // 都会重建这个 composable）。不设闸门的话，用户从设置页返回书架就会触发一次
    // 完整扫描并弹出「扫描完成」—— 这正是 v1.0.0「我没扫描它却总弹」的根因。
    //
    // 自动扫描走 silent：进度条照常显示，但不弹完成提示 —— 它是后台行为，
    // 用户没主动发起就不该被打断。
    LaunchedEffect(autoScan) {
        if (autoScan && !container.launchScanDone) {
            container.launchScanDone = true
            viewModel.startScan(silent = true)
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.persistUriPermission(uri, isTree = true)
            viewModel.addFolder(uri.toString())
        }
    }

    LibraryScreen(
        state = state,
        openedGroup = openedGroup,
        onViewChange = viewModel::setView,
        onSortChange = viewModel::setSort,
        onQueryChange = viewModel::setQuery,
        onLayoutChange = viewModel::setLayout,
        onOpenComic = { navController.navigate(Routes.reader(it)) },
        onOpenDetail = { navController.navigate(Routes.detail(it)) },
        onOpenHistory = { navController.navigate(Routes.HISTORY) },
        onOpenGroup = { openedGroupKey = it.key },
        onCloseGroup = { openedGroupKey = null },
        onSetGroupCover = viewModel::setShelfCover,
        onToggleSelect = viewModel::toggleSelect,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAll,
        onBatchFavorite = viewModel::toggleFavoriteOnSelection,
        onBatchTags = { tagPicker = true },
        onBatchDelete = viewModel::deleteSelection,
        onUndoRemove = viewModel::undoRemove,
        onScan = { viewModel.startScan() },
        onCancelScan = viewModel::cancelScan,
        onAddFolder = { folderLauncher.launch(null) },
        onDismissContinue = viewModel::dismissContinue,
        onConsumeMessage = viewModel::consumeMessage
    )

    if (tagPicker) {
        GazeTagPicker(
            title = "批量标签",
            allTags = allTags,
            onDismiss = { tagPicker = false },
            onApply = { mode, ids ->
                viewModel.applyTagsToSelection(
                    addIds = if (mode == TagPickMode.ADD) ids else emptySet(),
                    removeIds = if (mode == TagPickMode.REMOVE) ids else emptySet()
                )
                tagPicker = false
            },
            onCreate = { name, mode ->
                // 新建后顺手挂到当前选中的漫画上，用户少点一步。
                // 但只在「添加」模式下这么做 —— 用户在「移除」面板里新建标签，
                // 期待的是「先有这个标签」，而不是被塞进这批漫画。
                scope.launch {
                    val created = container.tagRepository.create(name)
                    if (created != null && mode == TagPickMode.ADD) {
                        viewModel.applyTagsToSelection(listOf(created.id), emptySet())
                    }
                }
            }
        )
    }
}

// ==================================================================== 标签

@Composable
private fun TagsRoute(container: AppContainer, navController: NavController) {
    val viewModel: TagsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = { Text("提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text("好") } }
        )
    }

    TagsScreen(
        tags = tags,
        favoriteCount = favoriteCount,
        selection = selection,
        onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
        onOpenTag = { navController.navigate(Routes.tagDetail(it)) },
        onToggleSelect = viewModel::toggleSelect,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAll,
        onBatchDelete = viewModel::deleteSelection,
        onBatchColor = viewModel::setColorOnSelection,
        onCreate = viewModel::createTag,
        onRename = viewModel::renameTag,
        onSetColor = viewModel::setColor,
        onDelete = viewModel::deleteTag
    )
}

// ==================================================================== 收藏

/**
 * 收藏页。
 *
 * 刻意与书架的「收藏」视图**分开**：书架那个是「换一个视图看同一面墙」，
 * 这里是标签体系里的一个**集合入口**（和点进某个标签是一回事）。
 * 两个入口长得一样、点下去都到同一处，用户会以为自己点错了。
 */
@Composable
private fun FavoritesRoute(container: AppContainer, navController: NavController) {
    val viewModel: FavoritesViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()

    FavoritesScreen(
        comics = comics,
        sort = sort,
        onSortChange = viewModel::setSort,
        onBack = { navController.popBackStack() },
        onOpenComic = { navController.navigate(Routes.reader(it)) },
        onOpenDetail = { navController.navigate(Routes.detail(it)) },
        onRemoveFavorite = viewModel::removeFavorite
    )
}

// ==================================================================== 设置

@Composable
private fun SettingsHubRoute(
    container: AppContainer,
    navController: NavController,
    viewModel: SettingsViewModel
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = settings,
        stats = stats,
        scan = scan,
        message = message,
        onCancelScan = viewModel::cancelScan,
        onConsumeMessage = viewModel::consumeMessage,
        onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
        onOpenReading = { navController.navigate(Routes.SETTINGS_READING) },
        onOpenLibrary = { navController.navigate(Routes.SETTINGS_LIBRARY) },
        onOpenMedia = { navController.navigate(Routes.SETTINGS_MEDIA) },
        onOpenNaming = { navController.navigate(Routes.SETTINGS_NAMING) },
        onOpenStats = { navController.navigate(Routes.STATS) },
        onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
        onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) }
    )
}

@Composable
private fun SettingsAppearanceRoute(viewModel: SettingsViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    AppearanceSettingsScreen(
        settings = settings,
        onSetThemeMode = viewModel::setThemeMode,
        onSetThemeVariant = viewModel::setThemeVariant,
        onSetThemeSeed = viewModel::setThemeSeed,
        onBack = { navController.popBackStack() }
    )
}

@Composable
private fun SettingsReadingRoute(viewModel: SettingsViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    ReadingSettingsScreen(
        settings = settings,
        onSetReadMode = viewModel::setDefaultReadMode,
        onSetFitMode = viewModel::setDefaultFitMode,
        onSetRenderMode = viewModel::setRenderMode,
        onSetScreenOrientation = viewModel::setScreenOrientation,
        onSetBrightnessFollowSystem = viewModel::setBrightnessFollowSystem,
        onSetBrightness = viewModel::setBrightness,
        onSetVolumeKeyPaging = viewModel::setVolumeKeyPaging,
        onSetKeepScreenOn = viewModel::setKeepScreenOn,
        onSetShowPageNumber = viewModel::setShowPageNumber,
        onSetReaderBackground = viewModel::setReaderBackground,
        onBack = { navController.popBackStack() }
    )
}

@Composable
private fun SettingsLibraryRoute(viewModel: SettingsViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    LibrarySettingsScreen(
        settings = settings,
        onSetLibraryView = viewModel::setLibraryView,
        onSetLibraryLayout = viewModel::setLibraryLayout,
        onSetSortOrder = viewModel::setSortOrder,
        onBack = { navController.popBackStack() }
    )
}

@Composable
private fun SettingsMediaRoute(viewModel: SettingsViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    MediaSettingsScreen(
        settings = settings,
        stats = stats,
        folders = folders,
        scan = scan,
        health = health,
        message = message,
        onAddFolder = viewModel::addFolder,
        onRemoveFolder = viewModel::removeFolder,
        onScan = viewModel::startScan,
        onCancelScan = viewModel::cancelScan,
        onClearCoverCache = viewModel::clearCoverCache,
        onHealthCheck = viewModel::startHealthCheck,
        onCancelHealthCheck = viewModel::cancelHealthCheck,
        onDismissHealthReport = viewModel::dismissHealthReport,
        onSetAutoScan = viewModel::setAutoScan,
        onConsumeMessage = viewModel::consumeMessage,
        onBack = { navController.popBackStack() }
    )
}

@Composable
private fun SettingsNamingRoute(viewModel: SettingsViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    NamingSettingsScreen(
        settings = settings,
        onSetParseBySeparator = viewModel::setParseAuthorBySeparator,
        onSetSeparator = viewModel::setAuthorSeparator,
        onSetParseByBracket = viewModel::setParseAuthorByBracket,
        onSetBracket = viewModel::setAuthorBracket,
        onReparseFileNames = viewModel::reparseFileNames,
        onBack = { navController.popBackStack() }
    )
}

@Composable
private fun SettingsBackupRoute(viewModel: SettingsViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val missingReport by viewModel.missingReport.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()

    // 备份默认文件名只在进入本页时算一次，避免每次重组都跟着时钟变
    val suggestedName = remember { viewModel.suggestedBackupName() }

    BackupSettingsScreen(
        settings = settings,
        backups = backups,
        missingReport = missingReport,
        pendingRestore = pendingRestore,
        suggestedName = suggestedName,
        onSetBackupFolder = viewModel::setBackupFolder,
        onClearBackupFolder = viewModel::clearBackupFolder,
        onSetAutoBackupInterval = viewModel::setAutoBackupInterval,
        onSetAutoBackupKeep = viewModel::setAutoBackupKeep,
        onBackupNow = viewModel::backupNow,
        onRefreshBackups = viewModel::refreshBackups,
        onExport = viewModel::exportBackup,
        onPeekBackup = viewModel::peekBackup,
        onRestoreBackup = viewModel::restoreFromBackup,
        onVerifyBackup = viewModel::verifyBackup,
        onDismissMissingReport = viewModel::dismissMissingReport,
        onRestoreLastExport = viewModel::restoreLastExport,
        onForgetLastExport = viewModel::forgetLastExport,
        onConfirmRestore = viewModel::confirmRestore,
        onCancelPendingRestore = viewModel::cancelPendingRestore,
        onBack = { navController.popBackStack() }
    )
}

// ==================================================================== 其它页

@Composable
private fun StatsRoute(container: AppContainer, navController: NavController) {
    val viewModel: StatsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val data by viewModel.data.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    StatsScreen(
        data = data,
        message = message,
        onBack = { navController.popBackStack() },
        onClear = viewModel::clear,
        onConsumeMessage = viewModel::consumeMessage
    )
}

@Composable
private fun HistoryRoute(container: AppContainer, navController: NavController) {
    val viewModel: HistoryViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val comics by viewModel.comics.collectAsStateWithLifecycle()

    HistoryScreen(
        comics = comics,
        onBack = { navController.popBackStack() },
        onOpenComic = { navController.navigate(Routes.reader(it)) },
        onOpenDetail = { navController.navigate(Routes.detail(it)) }
    )
}

@Composable
private fun TagDetailRoute(container: AppContainer, navController: NavController) {
    val viewModel: TagDetailViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val tag by viewModel.tag.collectAsStateWithLifecycle()
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    var sort by remember { mutableStateOf(SortOrder.ADDED_DESC) }

    TagDetailScreen(
        tag = tag,
        comics = comics,
        sort = sort,
        onSortChange = {
            sort = it
            viewModel.setSort(it)
        },
        onBack = { navController.popBackStack() },
        onOpenComic = { navController.navigate(Routes.reader(it)) },
        onRemoveFromTag = { viewModel.removeTagFrom(listOf(it)) }
    )
}

@Composable
private fun DetailRoute(container: AppContainer, navController: NavController) {
    val viewModel: DetailViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val comic by viewModel.comic.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val removed by viewModel.removed.collectAsStateWithLifecycle()

    DetailScreen(
        comic = comic,
        tags = tags,
        allTags = allTags,
        message = message,
        removed = removed,
        onBack = { navController.popBackStack() },
        onRead = { comic?.let { navController.navigate(Routes.reader(it.id)) } },
        onToggleFavorite = viewModel::toggleFavorite,
        onSetTags = viewModel::setTags,
        onCreateTag = viewModel::createAndAttach,
        onRename = viewModel::rename,
        onSetMetadata = viewModel::setMetadata,
        onRegenerateCover = viewModel::regenerateCover,
        // 移除后**不立刻返回**：留 5 秒撤销窗口，倒计时结束由 DetailScreen 自己回退
        onRemove = viewModel::remove,
        onUndoRemove = viewModel::undoRemove,
        onConsumeMessage = viewModel::consumeMessage
    )
}

@Composable
private fun ReaderRoute(container: AppContainer, navController: NavController) {
    val viewModel: ReaderViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    ReaderScreen(
        state = state,
        onBack = {
            // 退出阅读页时先落一次进度，再返回
            scope.launch { viewModel.persist() }
            navController.popBackStack()
        },
        onPageChanged = viewModel::onPageChanged,
        onSetMode = viewModel::setMode,
        onSetFitMode = viewModel::setFitMode,
        onSetRenderMode = viewModel::setRenderMode,
        onSetScreenOrientation = viewModel::setScreenOrientation,
        onSetBrightnessFollowSystem = viewModel::setBrightnessFollowSystem,
        onSetBrightness = viewModel::setBrightness,
        onSetKeepScreenOn = viewModel::setKeepScreenOn,
        onSetShowPageNumber = viewModel::setShowPageNumber,
        onSetVolumeKeyPaging = viewModel::setVolumeKeyPaging,
        onSetReaderBackground = viewModel::setReaderBackground,
        onFollowDefault = viewModel::followGlobalDefaults,
        onOpenDetail = {
            state.comic?.let { navController.navigate(Routes.detail(it.id)) }
        },
        onToggleControls = viewModel::toggleControls,
        loader = remember(viewModel) {
            PageLoader(
                loadFit = { index, w, h -> viewModel.loadPage(index, w, h) },
                loadSize = { index -> viewModel.loadPageSize(index) },
                loadRegion = { index, region, outW, outH ->
                    viewModel.loadRegion(index, region, outW, outH)
                }
            )
        }
    )
}
