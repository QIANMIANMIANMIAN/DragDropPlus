package com.vivo.dragdrop.plus.xposed

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块入口。
 *
 * 作用域只有 com.vivo.globaldragdrop（拖放直达）。
 *
 * 设计原则：
 *  - 只 hook 一个方法（WhiteListResultBean.getResult），其余全部走原逻辑。
 *  - 任何异常都不允许逃逸，最坏情况退回系统原生行为。
 */
class HookEntry : XposedModule() {

    companion object {
        const val PREFS_NAME = "dragdrop_plus"

        /** 总开关：任意 App 可拖 */
        const val KEY_FORCE_ALL = "force_all"

        /** 附加能力：放宽图片最小尺寸限制 */
        const val KEY_RELAX_IMAGE = "relax_image"

        /** 调试日志 */
        const val KEY_DEBUG_LOG = "debug_log"

        private const val LOG_DIR = "/sdcard/Documents/DragDropPlus"
        private const val LOG_FILE = "module.log"
        private const val LOG_MAX_BYTES = 512L * 1024
    }

    private val prefs: SharedPreferences by lazy { getRemotePreferences(PREFS_NAME) }

    @Volatile
    private var forceAll: Boolean = true

    @Volatile
    private var relaxImage: Boolean = false

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        installFileLog()
        reloadPrefs()
        installPrefsListener()

        ModLog.i("==== 模块加载 ====")
        ModLog.i("pkg=${param.packageName} process=${param.isFirstPackage}")
        ModLog.i("forceAll=$forceAll relaxImage=$relaxImage debug=${ModLog.debugEnabled}")
        ModLog.i("模块版本=${BuildConfigVersion.VERSION}")

        runCatching {
            WhiteListHooker(this) { forceAll }.install()
        }.onFailure {
            ModLog.e("挂载失败", it)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        ModLog.i("package ready pkg=${param.packageName}")
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        for (h in param.oldHookHandles) {
            runCatching { h.unhook() }
        }
        ModLog.i("hot reload：已卸载旧 hook")
    }

    // ---------------- 配置 ----------------

    private fun reloadPrefs() {
        runCatching {
            forceAll = prefs.getBoolean(KEY_FORCE_ALL, true)
            relaxImage = prefs.getBoolean(KEY_RELAX_IMAGE, false)
            ModLog.debugEnabled = prefs.getBoolean(KEY_DEBUG_LOG, false)
        }.onFailure {
            ModLog.w("读配置失败，使用默认值：${it.javaClass.simpleName}")
        }
    }

    private fun installPrefsListener() {
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    KEY_FORCE_ALL -> {
                        forceAll = prefs.getBoolean(KEY_FORCE_ALL, true)
                        ModLog.i("开关变化 forceAll=$forceAll")
                    }
                    KEY_RELAX_IMAGE -> {
                        relaxImage = prefs.getBoolean(KEY_RELAX_IMAGE, false)
                        ModLog.i("开关变化 relaxImage=$relaxImage")
                    }
                    KEY_DEBUG_LOG -> {
                        ModLog.debugEnabled = prefs.getBoolean(KEY_DEBUG_LOG, false)
                    }
                }
            }
        }
    }

    // ---------------- 文件日志 ----------------

    private fun installFileLog() {
        runCatching {
            val dir = File(LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            if (!dir.canWrite()) {
                ModLog.w("日志目录不可写：$LOG_DIR")
                return
            }
            val file = File(dir, LOG_FILE)
            // 简单滚动：超过上限就清空重来
            if (file.exists() && file.length() > LOG_MAX_BYTES) {
                file.delete()
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            ModLog.install { line ->
                runCatching {
                    file.appendText("${ts.format(Date())} $line\n")
                }
            }
        }.onFailure {
            Log.w("DragDropPlus", "installFileLog failed", it)
        }
    }
}

/** BuildConfig 在 xposed 进程里也可安全读取版本串 */
internal object BuildConfigVersion {
    val VERSION: String = runCatching {
        com.vivo.dragdrop.plus.BuildConfig.MODULE_VERSION
    }.getOrDefault("unknown")
}
