package com.vivo.dragdrop.plus.xposed

import android.content.Context
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

        /**
         * 日志文件：
         *  - 首选 App 私有目录（无需任何权限，管理器 UI 自己就能读）
         *  - 尾部追加 /sdcard/Documents/DragDropPlus/ 副本（需要模块被授予「所有文件访问」）
         *
         * 1.0.0 只写 /sdcard/Documents，Android 11+ 下管理器 App 无权限读，
         * 所以 UI 里显示「读取失败」。改成私有目录为主。
         */
        private const val LOG_FILE = "module.log"
        private const val LOG_MAX_BYTES = 512L * 1024
    }

    private val prefs: SharedPreferences by lazy { getRemotePreferences(PREFS_NAME) }

    @Volatile
    private var forceAll: Boolean = true

    @Volatile
    private var relaxImage: Boolean = false

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val hostCl = param.defaultClassLoader

        installFileLog(hostCl)
        reloadPrefs()
        installPrefsListener()

        ModLog.i("==== 模块加载 ====")
        ModLog.i("pkg=${param.packageName} first=${param.isFirstPackage}")
        ModLog.i("forceAll=$forceAll relaxImage=$relaxImage debug=${ModLog.debugEnabled}")
        ModLog.i("模块版本=${BuildConfigVersion.VERSION}")

        runCatching {
            WhiteListHooker(this, hostCl) { forceAll }.install()
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

    /**
     * 日志落盘。
     *
     * 关键：目标进程里没有 Context 可用时，用宿主 ClassLoader 反射拿
     * ActivityThread.currentApplication()；拿到后写它自己的私有目录
     * （=/data/data/com.vivo.globaldragdrop/files/DragDropPlus/module.log）。
     *
     * 但管理器 App 读不到别的 App 的私有目录 —— 所以再尝试写一份到公共目录。
     * 公共目录不可写就算了，UI 会提示去 logcat 看。
     */
    private fun installFileLog(hostCl: ClassLoader) {
        runCatching {
            val app = currentApplication(hostCl)
            val dir = pickLogDir(app)
            if (dir == null) {
                ModLog.w("没有可写日志目录，只输出 logcat")
                return
            }
            if (!dir.exists()) dir.mkdirs()
            if (!dir.canWrite()) {
                ModLog.w("日志目录不可写：${dir.absolutePath}")
                return
            }
            val file = File(dir, LOG_FILE)
            if (file.exists() && file.length() > LOG_MAX_BYTES) file.delete()

            // 公共目录副本（能写就写，写不了静默放弃）
            val mirror = runCatching {
                val d = File("/sdcard/Documents/DragDropPlus")
                if (!d.exists()) d.mkdirs()
                if (d.canWrite()) File(d, LOG_FILE) else null
            }.getOrNull()

            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            ModLog.install { line ->
                val text = "${ts.format(Date())} $line\n"
                runCatching { file.appendText(text) }
                mirror?.let { runCatching { it.appendText(text) } }
            }
            ModLog.i("日志文件：${file.absolutePath}" + (mirror?.let { "（副本：${it.absolutePath}）" } ?: ""))
        }.onFailure {
            Log.w("DragDropPlus", "installFileLog failed", it)
        }
    }

    private fun pickLogDir(app: Context?): File? {
        if (app != null) {
            runCatching {
                return File(app.filesDir, "DragDropPlus")
            }
        }
        // 没有 Context：退到外部公共目录（模块自身无存储权限，可能失败）
        return runCatching {
            val d = File("/sdcard/Documents/DragDropPlus")
            if (!d.exists()) d.mkdirs()
            d.takeIf { it.canWrite() }
        }.getOrNull()
    }

    /** 用宿主 ClassLoader 反射取 Application Context（目标进程里没有别的办法拿到） */
    private fun currentApplication(hostCl: ClassLoader): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread", false, hostCl)
        val cur = at.getMethod("currentApplication").invoke(null)
        cur as? Context
    }.getOrNull()
}

/** BuildConfig 在 xposed 进程里也可安全读取版本串 */
internal object BuildConfigVersion {
    val VERSION: String = runCatching {
        com.vivo.dragdrop.plus.BuildConfig.MODULE_VERSION
    }.getOrDefault("unknown")
}
