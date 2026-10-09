package com.gaze.reader.scan

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 让通过 SAF 选择的文件夹在重启后依然可访问。
 *
 * 不调 `takePersistableUriPermission` 的话，授权只在本次进程内有效 ——
 * 用户下次打开 Gaze，所有文件夹都会变成「访问权限已失效」。
 *
 * 文件夹先试 READ|WRITE，失败再退到只 READ：Gaze 只读不写用户的漫画目录，
 * 但有些 provider 只肯授予读写两种权限，先要全的能少一次失败重试。
 */
fun Context.persistUriPermission(uri: Uri, isTree: Boolean) {
    val treeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val docFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val primary = if (isTree) treeFlags else docFlags
    runCatching { contentResolver.takePersistableUriPermission(uri, primary) }
        .recoverCatching { contentResolver.takePersistableUriPermission(uri, docFlags) }
}

/**
 * 释放持久化授权。
 *
 * 关键：**release 的 flag 必须和 take 时一致**。Gaze 取的是 `READ | WRITE`，
 * 如果这里只传 `READ`，系统会认为「没有这样一条持久授权」而抛 SecurityException ——
 * 外面套着 `runCatching`，异常被吞掉，权限就留在系统里了，没有任何报错。
 *
 * 所以这里按「先试 take 的组合，再逐个降级，最后按系统实际记录的授权释放」三步走，
 * 以后即使改了 take 的 flag 也不会对不上。
 */
fun Context.releaseUriPermission(uri: Uri) {
    val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val readOnly = Intent.FLAG_GRANT_READ_URI_PERMISSION

    runCatching { contentResolver.releasePersistableUriPermission(uri, readWrite) }
        .recoverCatching { contentResolver.releasePersistableUriPermission(uri, readOnly) }
        .recoverCatching {
            // 兜底：按系统实际记录的那条授权来释放
            val flags = contentResolver.persistedUriPermissions
                .firstOrNull { it.uri == uri }
                ?.let { permission ->
                    var f = 0
                    if (permission.isReadPermission) f = f or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (permission.isWritePermission) f = f or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    f
                }
            if (flags != null && flags != 0) {
                contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
}
