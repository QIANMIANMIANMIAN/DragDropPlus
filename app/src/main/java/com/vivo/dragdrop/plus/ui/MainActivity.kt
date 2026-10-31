package com.vivo.dragdrop.plus.ui

import android.content.Context
import android.content.SharedPreferences
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

/** 与模块侧 HookEntry 保持一致的 key */
private const val PREFS_NAME = "dragdrop_plus"
private const val KEY_FORCE_ALL = "force_all"
private const val KEY_RELAX_IMAGE = "relax_image"
private const val KEY_DEBUG_LOG = "debug_log"

private const val LOG_PATH = "/sdcard/Documents/DragDropPlus/module.log"

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
    var content by remember { mutableStateOf("") }
    var exists by remember { mutableStateOf(false) }

    fun refresh() {
        val f = File(LOG_PATH)
        exists = f.exists()
        content = if (exists) {
            runCatching { f.readText().takeLast(8000) }.getOrDefault("<读取失败>")
        } else {
            "日志文件尚未生成：\n$LOG_PATH\n\n（需要模块在拖放进程里被加载后才会写入）"
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
                Text("运行日志", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { refresh() }) { Text("刷新") }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                Text(
                    text = content.ifBlank { "（空）" },
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
