package com.vivo.dragdrop.plus.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivo.dragdrop.plus.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** 与模块侧 HookEntry 保持一致的 key */
private const val PREFS_NAME = "dragdrop_plus"
private const val KEY_FORCE_ALL = "force_all"
private const val KEY_RELAX_IMAGE = "relax_image"
private const val KEY_DEBUG_LOG = "debug_log"


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    // 注意：管理器 UI 自己写的是「普通的 SharedPreferences」，
    // 而模块在目标进程里读的是 libxposed 的 RemotePreferences —— 两者是同一份文件，
    // 由框架做跨进程同步（libxposed 102 支持）。
    // 但必须用 MODE_WORLD_READABLE 的兼容路径，否则目标进程读不到。
    val prefs = remember { prefsOf(ctx) }

    var forceAll by remember { mutableStateOf(prefs.getBoolean(KEY_FORCE_ALL, true)) }
    var relaxImage by remember { mutableStateOf(prefs.getBoolean(KEY_RELAX_IMAGE, false)) }
    var debugLog by remember { mutableStateOf(prefs.getBoolean(KEY_DEBUG_LOG, false)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "拖放增强",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "v${BuildConfig.MODULE_VERSION} · 作用域 com.vivo.globaldragdrop",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        // 开关卡片
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow(
                    icon = { Icon(Icons.Filled.Bolt, null) },
                    title = "任意应用可拖",
                    summary = "关闭后恢复系统原生白名单（只有 vivo 适配过的 App 能拖）",
                    checked = forceAll,
                ) { v ->
                    forceAll = v
                    prefs.edit().putBoolean(KEY_FORCE_ALL, v).apply()
                }

                SwitchRow(
                    icon = { Icon(Icons.Filled.Image, null) },
                    title = "放宽图片尺寸限制",
                    summary = "实验性：让更小的图标/缩略图也能被拖起（默认关闭）",
                    checked = relaxImage,
                ) { v ->
                    relaxImage = v
                    prefs.edit().putBoolean(KEY_RELAX_IMAGE, v).apply()
                }

                SwitchRow(
                    icon = { Icon(Icons.Filled.BugReport, null) },
                    title = "调试日志",
                    summary = "记录每次放行判定到 logcat 与日志文件",
                    checked = debugLog,
                ) { v ->
                    debugLog = v
                    prefs.edit().putBoolean(KEY_DEBUG_LOG, v).apply()
                }
            }
        }

        // 使用说明
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使用步骤", fontWeight = FontWeight.SemiBold)
                Step("1", "在 LSPosed 里启用本模块")
                Step("2", "作用域勾选「拖放直达」（com.vivo.globaldragdrop）")
                Step("3", "强制停止拖放直达，或重启手机让模块注入")
                Step("4", "任意 App 里长按文字/图片即可拖动")
                Spacer(Modifier.height(4.dp))
                Text(
                    "提示：微信、抖音等已适配的 App 原本就能拖；本模块让「没适配的 App」也能拖。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 日志
        LogCard()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LogCard() {
    val ctx = LocalContext.current
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun refresh() {
        loading = true
        scope.launch(Dispatchers.IO) {
            val (src, text) = loadLog()
            withContext(Dispatchers.Main) {
                source = src
                content = text.ifBlank { "（没有日志）" }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("运行日志", fontWeight = FontWeight.SemiBold)
                    if (source.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "($source)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row {
                    if (!hasAllFilesAccess(ctx)) {
                        TextButton(onClick = { requestAllFilesAccess(ctx) }) {
                            Text("授权日志目录", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { refresh() }) { Text("刷新") }
                }
            }
            if (!hasAllFilesAccess(ctx)) {
                Text(
                    "未授予「所有文件访问」，文件日志读不到，已自动切换为 logcat。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 380.dp),
            ) {
                Box {
                    Text(
                        text = if (loading) "读取中…" else content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 日志候选路径，按「管理器能否读到」排序。
 *
 * 模块跑在目标进程 com.vivo.globaldragdrop 里，日志写在目标 App 的外部私有目录：
 *   /storage/emulated/0/Android/data/com.vivo.globaldragdrop/files/DragDropPlus/module.log
 * 管理器要读它需要「所有文件访问」；没授权时用 root su 兜底。
 */
private val LOG_CANDIDATES = listOf(
    "/storage/emulated/0/Android/data/com.vivo.globaldragdrop/files/DragDropPlus/module.log",
    "/sdcard/Android/data/com.vivo.globaldragdrop/files/DragDropPlus/module.log",
    "/sdcard/Documents/DragDropPlus/module.log",
    "/sdcard/Download/DragDropPlus/module.log",
)

/** 依次尝试：直接读 → root 读 → logcat */
private fun loadLog(): Pair<String, String> {
    // 1) 直接读（需要 MANAGE_EXTERNAL_STORAGE）
    for (path in LOG_CANDIDATES) {
        val f = File(path)
        if (f.exists() && f.canRead()) {
            val body = runCatching { f.readText().takeLast(15000) }.getOrDefault("")
            if (body.isNotBlank()) return "文件" to "【$path】\n${"-".repeat(30)}\n$body"
        }
    }
    // 2) root 读（模块里写的位置管理器可能没权限，su 一定行）
    for (path in LOG_CANDIDATES) {
        val body = suCat(path)
        if (!body.isNullOrBlank()) {
            return "root" to "【$path】（root 读取）\n${"-".repeat(30)}\n$body"
        }
    }
    // 3) logcat 兜底（普通 App 只能读自己 uid 的日志，
    //    但 root 下可以读全量，所以这里也先试 root logcat）
    val lc = suLogcat() ?: readLogcat()
    return "logcat" to "【logcat】tag=DragDropPlus\n${"-".repeat(30)}\n$lc"
}

/** su -c cat 读文件；失败返回 null */
private fun suCat(path: String): String? = runCatching {
    val p = ProcessBuilder("su", "-c", "cat '$path'")
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.takeIf { it.isNotBlank() && !it.contains("No such file") && !it.contains("Permission denied") }
        ?.takeLast(15000)
}.getOrNull()

/** su -c logcat 读目标进程日志 */
private fun suLogcat(): String? = runCatching {
    val p = ProcessBuilder("su", "-c", "logcat -d -t 4000 -s DragDropPlus:V")
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.takeIf { it.isNotBlank() && !it.contains("Permission denied") }?.takeLast(15000)
}.getOrNull()

/** 读 logcat 里本模块的输出（只能拿到自己 uid 的） */
private fun readLogcat(): String = runCatching {
    val p = ProcessBuilder("logcat", "-d", "-t", "3000", "-s", "DragDropPlus:V")
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.takeLast(8000).ifBlank { "（logcat 里暂无 DragDropPlus 输出）" }
}.getOrDefault("（读 logcat 失败）")

private fun hasAllFilesAccess(ctx: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        true
    }
}.getOrDefault(false)

private fun requestAllFilesAccess(ctx: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }.onFailure {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                summary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Step(no: String, text: String) {
    Row {
        Text(
            no + ". ",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
        )
        Text(text, fontSize = 13.sp)
    }
}

private fun prefsOf(ctx: Context): SharedPreferences =
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
