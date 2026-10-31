package com.vivo.dragdrop.plus.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 核心：强制「拖放白名单」判定通过，让任意 App 都能拖走文字/图片。
 *
 * ================== 打点依据（实机反编译 拖放直达 4.1.0.0 得出）==================
 *
 * 判定链：
 *
 *   za.n.p(...)                                    ← 拖放调度协程（R8 混淆名）
 *     └── EnhancedDragWhiteList$Companion.checkWhiteList(...) -> WhiteListResultBean
 *           └── IConfigListStrategy.executionStrategy(...)    ← 具体策略
 *                 ├── BlackListStrategy
 *                 ├── WhiteListStrategy  (内部再分 Strict / UnStrict)
 *                 └── NoWidgetLimitWhiteListStrategy
 *
 * 调用点 za.n 的 smali（原样）：
 *
 *     invoke-static/range {v12 .. v22}, ...->checkWhiteList$default(...)Lcom/vivo/common/dragdrop/data/bean/WhiteListResultBean;
 *     move-result-object v0
 *     invoke-virtual {v0}, ...->getResult()Z
 *     move-result v3
 *     if-nez v3, :cond_616          # result==false
 *     invoke-virtual {v0}, ...->getBlackResult()Z
 *     move-result v3
 *     if-eqz v3, :cond_600          # blackResult==false -> 不通过
 *     goto :goto_616                # blackResult==true  -> 通过
 *   :cond_600                        # 继续尝试下一个候选控件
 *
 * 结论：只要 getResult() 恒返回 true，白名单判定必然通过。
 *
 * ================== 1.0.1 修复：类加载器 ==================
 * 1.0.0 用 `Class.forName(name)` 定位类，日志报「找不到 WhiteListResultBean」。
 * 原因：Class.forName 单参重载用的是【本模块自己的 ClassLoader】，
 *       而 WhiteListResultBean 在【宿主 App 的 ClassLoader】里，
 *       两者是互相隔离的 PathClassLoader，所以永远找不到。
 * 修复：改用 `PackageLoadedParam.getDefaultClassLoader()`（libxposed 102 API）
 *       作为宿主 ClassLoader，并通过 `Class.forName(name, false, cl)` 加载。
 *
 * 同时保留反射兜底：若宿主 ClassLoader 也拿不到，再从
 * ActivityThread.currentApplication().getClassLoader() 取。
 */
internal class WhiteListHooker(
    private val module: XposedModule,
    /** 宿主进程的 ClassLoader —— 必须用它，不能用模块自己的 */
    private val hostClassLoader: ClassLoader,
    /** 总开关提供者：由 HookEntry 持有，实时反映用户设置 */
    private val forceAllProvider: () -> Boolean,
) {

    private companion object {
        const val TAG = "[WhiteList]"

        /** 白名单结果 Bean（R8 混淆后仍在原包名下，未改名 —— 已用 dex_names 核实） */
        const val CLS_RESULT_BEAN =
            "com.vivo.common.dragdrop.data.bean.WhiteListResultBean"

        /** 白名单总入口（Companion 单例） */
        const val CLS_WL_COMPANION =
            "com.vivo.common.dragdrop.data.bean.EnhancedDragWhiteList\$Companion"

        /** 调度类，用于确认进程环境（不存在说明不是拖放进程） */
        const val CLS_DISPATCH = "za.n"

        const val METHOD_GET_RESULT = "getResult"

        /** 打点日志限流：前 N 次全打，之后每 M 次打一条 */
        const val LOG_FIRST = 20
        const val LOG_EVERY = 200
    }

    private val hitCount = AtomicInteger(0)
    private val appliedCount = AtomicInteger(0)

    fun install() {
        ModLog.i("$TAG ClassLoader=${hostClassLoader.javaClass.name}")

        val beanCls = loadClass(CLS_RESULT_BEAN)
        if (beanCls == null) {
            // 类加载失败时，把诊断信息打全，方便判断是「进程不对」还是「版本改名」
            dumpDiagnostics()
            return
        }

        val dispatchPresent = loadClass(CLS_DISPATCH) != null
        val companionPresent = loadClass(CLS_WL_COMPANION) != null
        ModLog.i(
            "$TAG 环境：bean=${beanCls.name} dispatch=$dispatchPresent companion=$companionPresent",
        )

        hookGetResult(beanCls)
    }

    private fun hookGetResult(beanCls: Class<*>) {
        val method = findGetResult(beanCls)
        if (method == null) {
            ModLog.w("$TAG 找不到 $METHOD_GET_RESULT()，该类方法：" +
                beanCls.declaredMethods.joinToString { it.name }.take(400))
            return
        }
        ModLog.i("$TAG 目标方法：${method.toGenericString()}")

        runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!forceAllProvider()) {
                        return@intercept chain.proceed()
                    }
                    val n = hitCount.incrementAndGet()

                    // 走到这里说明 vivo 已经完成了一轮白名单判定，
                    // 并把结论写进了这个 bean。我们只把结论翻成 true。
                    val original = runCatching { chain.proceed() as? Boolean ?: false }
                        .getOrDefault(false)

                    if (n <= LOG_FIRST || n % LOG_EVERY == 0) {
                        val info = describe(chain.thisObject)
                        ModLog.i("$TAG 放行 #$n 原结论=$original $info")
                    }

                    if (!original) appliedCount.incrementAndGet()

                    // ★ 无论原值如何，一律返回 true
                    true
                }
            ModLog.i("$TAG ✅ 已 hook ${beanCls.simpleName}.$METHOD_GET_RESULT()")
        }.onFailure {
            ModLog.e("$TAG 挂载 $METHOD_GET_RESULT 失败", it)
        }
    }

    /** 找 getResult()Z：先按名字精确找无参 boolean，再退化到按名字找 */
    private fun findGetResult(cls: Class<*>): Method? {
        runCatching {
            cls.getDeclaredMethod(METHOD_GET_RESULT).let {
                if (it.returnType == Boolean::class.javaPrimitiveType ||
                    it.returnType == java.lang.Boolean::class.java
                ) {
                    return it
                }
            }
        }
        return runCatching {
            cls.declaredMethods.firstOrNull {
                it.name == METHOD_GET_RESULT && it.parameterCount == 0
            }
        }.getOrNull()
    }

    /**
     * 类加载失败时输出诊断，区分三种情况：
     *  1. 宿主 ClassLoader 里根本没有这个包 → 进程/包名不对
     *  2. 有包但没有这个类 → 版本改名（R8 混淆策略变化）
     *  3. ClassLoader 本身拿错 → 打印 loader 层级
     */
    private fun dumpDiagnostics() {
        ModLog.w("$TAG ❌ 加载不到 $CLS_RESULT_BEAN")

        // 试同名类在别的 loader 里能不能找到（判断是不是 loader 用错）
        val altLoaders = listOfNotNull(
            "currentThread" to Thread.currentThread().contextClassLoader,
            "thisObj" to javaClass.classLoader,
        )
        for ((tag, cl) in altLoaders) {
            if (cl == null) {
                ModLog.w("$TAG    loader[$tag]=null")
                continue
            }
            val found = runCatching { Class.forName(CLS_RESULT_BEAN, false, cl) }.isSuccess
            ModLog.w("$TAG    loader[$tag]=${cl.javaClass.name} found=$found")
        }

        // 宿主 loader 里能不能找到同包下的其它类（说明包加载了但类改名）
        val siblings = listOf(
            "com.vivo.common.dragdrop.data.bean.EnhancedDragWhiteList",
            "com.vivo.common.dragdrop.data.bean.WhiteListStrategy",
            "com.vivo.common.dragdrop.data.bean.StrictModeWhiteListStrategy",
        )
        for (s in siblings) {
            ModLog.w("$TAG    兄弟类 $s = ${loadClass(s) != null}")
        }

        // 兜底：扫一遍 dex 里 data.bean 包下所有类名，直接看真实类名
        runCatching {
            val dexCls = loadClass("dalvik.system.BaseDexClassLoader")
            if (dexCls != null && dexCls.isInstance(hostClassLoader)) {
                ModLog.w("$TAG    hostClassLoader 是 BaseDexClassLoader，可枚举 dex")
            }
        }
    }

    /**
     * 用【宿主 ClassLoader】加载类。
     * 注意 initialize=false：此时只做 hook 目标定位，不要触发静态初始化。
     */
    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()

    /**
     * 把 WhiteListResultBean 的关键字段读出来，方便日后定位哪些 App 是被我们强行放行的。
     * 全部走反射且容错，读不到就跳过。
     */
    private fun describe(bean: Any?): String {
        if (bean == null) return "bean=null"
        return runCatching {
            val c = bean.javaClass
            val sb = StringBuilder()
            sb.append("type=").append(readStr(bean, c, "type"))
            sb.append(", result=").append(readStr(bean, c, "result"))
            sb.append(", black=").append(readStr(bean, c, "blackResult"))
            sb.append(", strict=").append(readStr(bean, c, "strictModel"))
            sb.append(", imgFmt=").append(readStr(bean, c, "imageFormat"))
            sb.toString()
        }.getOrDefault("bean=<读字段失败>")
    }

    /** 读一个字段并转字符串；字段不存在或读失败返回 "?" */
    private fun readStr(obj: Any, cls: Class<*>, name: String): String =
        runCatching {
            val f = cls.getDeclaredField(name).apply { isAccessible = true }
            f.get(obj)?.toString() ?: "null"
        }.getOrDefault("?")
}
