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

        /**
         * 目标 App 的外部私有目录。硬编码而不走 API，
         * 是为了在没有 Context 时也能尝试。
         */
        private const val EXTERNAL_APP_DIR =
            "/storage/emulated/0/Android/data/com.vivo.globaldragdrop/files/DragDropPlus"
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
     * ============ 1.0.3 修正：日志必须写到「两边都可达」的位置 ============
     *
     * 模块代码跑在【目标进程 com.vivo.globaldragdrop】里，用的是目标 App 的权限。
     * 1.0.1/1.0.2 首选写 app.filesDir，那是
     *   /data/data/com.vivo.globaldragdrop/files/DragDropPlus
     * —— 目标 App 私有目录，管理器 App（不同 uid）永远读不到，所以 UI 一片空白。
     *
     * 现在改为：目标 App 的【外部】私有目录
     *   /storage/emulated/0/Android/data/com.vivo.globaldragdrop/files/DragDropPlus
     *  - 目标进程：这是它自己的外部目录，无需任何权限，必定可写
     *  - 管理器：授予 MANAGE_EXTERNAL_STORAGE 后可读；有 root 时用 su 直接 cat
     *
     * 同时保留 /sdcard/Documents/DragDropPlus 作为副本（能写就写）。
     */
    private fun installFileLog(hostCl: ClassLoader) {
        runCatching {
            val app = currentApplication(hostCl)

            val file = chooseLogFile(app)
            if (file == null) {
                ModLog.w("没有可写日志目录，只输出 logcat")
                return
            }

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

    /** 依次尝试几个候选位置，返回第一个真正可写的文件 */
    private fun chooseLogFile(app: Context?): File? {
        val candidates = mutableListOf<File>()

        // 1) 目标 App 的外部私有目录（管理器授权后可读，目标进程必定可写）
        runCatching { app?.getExternalFilesDir(null) }.getOrNull()?.let {
            candidates += File(it, "DragDropPlus/$LOG_FILE")
        }
        // 2) 硬编码路径兜底（拿不到 Context 时也能试）
        candidates += File("$EXTERNAL_APP_DIR/$LOG_FILE")
        // 3) 目标 App 内部私有目录（管理器读不到，但日志不丢，可用 root 取）
        runCatching { app?.filesDir }.getOrNull()?.let {
            candidates += File(it, "DragDropPlus/$LOG_FILE")
        }
        // 4) 公共目录
        candidates += File("/sdcard/Documents/DragDropPlus/$LOG_FILE")
        candidates += File("/sdcard/Download/DragDropPlus/$LOG_FILE")

        for (f in candidates) {
            val ok = runCatching {
                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                if (f.parentFile?.canWrite() != true) return@runCatching false
                if (f.exists() && f.length() > LOG_MAX_BYTES) f.delete()
                f.appendText("")
                f.canWrite()
            }.getOrDefault(false)
            if (ok) return f
        }
        return null
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
