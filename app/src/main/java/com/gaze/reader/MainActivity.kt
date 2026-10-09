package com.gaze.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gaze.reader.core.ThemeMode
import com.gaze.reader.data.prefs.GazeSettings
import com.gaze.reader.ui.GazeRoot
import com.gaze.reader.ui.theme.GazeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * 从外部带进来的备份文件（文件管理器的「打开方式」或分享）。
     *
     * 用 Compose 的 State 而不是直接读 `intent`：`launchMode="singleTask"` 下
     * 应用已经在后台时再次打开走的是 [onNewIntent]，只在 onCreate 里读一次
     * 会漏掉后面每一次。
     */
    private val incomingBackup = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as GazeApp).container

        // 自动备份的检查点：只在**打开应用**时看一眼是否到点。
        //
        // 刻意不做后台常驻 / WorkManager —— 那需要额外的后台权限与电量预算，
        // 而「备份元数据」这件事对时效性没有任何要求，晚几天也完全无害。
        // 判定逻辑（是否关闭、是否到点、保留几份）全在仓库里，这里只负责触发。
        lifecycleScope.launch {
            runCatching { container.backupRepository.runAutoBackupIfDue() }
        }

        incomingBackup.value = backupUriFrom(intent)

        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = GazeSettings())

            // 状态栏 / 导航栏图标的明暗必须跟着**应用**主题走，而不是系统主题。
            // enableEdgeToEdge() 默认按系统深色模式决定，于是「系统深色 + 应用浅色」
            // 会得到一组白色图标压在米白背景上，几乎看不见。
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            GazeTheme(
                themeMode = settings.themeMode,
                themeVariant = settings.themeVariant,
                themeSeed = Color(settings.themeSeed)
            ) {
                GazeRoot(
                    container = container,
                    settings = settings,
                    incomingBackup = incomingBackup.value,
                    onIncomingBackupConsumed = { incomingBackup.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        backupUriFrom(intent)?.let { incomingBackup.value = it }
    }

    /**
     * 取 intent 里指向备份文件的 uri。
     *
     * 不做额外的格式校验 —— 能走到这里说明命中的是 manifest 里声明的
     * mime / 扩展名过滤器；真选错了文件，读取时会给出「这不是 Gaze 备份文件」。
     */
    private fun backupUriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
