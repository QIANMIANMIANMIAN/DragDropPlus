package com.vivo.dragdrop.plus.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 核心：让任意 App 都能拖走文字/图片。
 *
 * ================= 1.0.2 关键修正：打点换到真正的位置 =================
 *
 * 1.0.0 / 1.0.1 hook 的是 WhiteListResultBean.getResult()，
 * 日志显示 hook 成功挂上，但一条「放行」都没打印 —— 说明那个方法根本没被调用。
 *
 * 重新静态分析后发现：WhiteListResultBean.getResult() 在整个 dex 里只有 2 处调用，
 * 都在 za.n 里，而 za.n 属于【中转岛 TransIsland】链路（gb.e = IslandStrategy），
 * 跟「长按拖拽」是两条不同的路。所以之前打错了地方。
 *
 * ================== 真正的拦截点 ==================
 *
 * 拖拽起始消息由 DragService 的 Handler（cb.h）处理，其 a(Message) 方法里：
 *
 *     .line 1597
 *     invoke-static {v2, v3}, Lza/o;->g(Ljava/lang/String;Ljava/lang/String;)Z
 *     move-result v2
 *     .line 1601
 *     if-nez v2, :cond_6a9              # ← 返回 false 就进拒绝分支
 *     .line 1613
 *     const-string v5, "activity not in whitelist: "     # ← 拒绝日志
 *     .line 1626
 *     const/16 v5, 0x1b                 # ← 给客户端回 what=27（拒绝）
 *
 * 即：za.o.g(packageName, activityName) 返回 false → 拖拽被直接拒绝。
 * 整个 dex 里这个方法【只有这一个调用点】，hook 它零副作用。
 *
 * za.o.g 原始逻辑（反编译确认）：
 *     pkg 为空 / activity 为空 / activity == "NA"  → false
 *     查 EnhancedDragFeatureListV2[pkg] 的 pages，比对 activity
 *     查 EnhancedDragWhiteListV2[pkg]
 *     两个都查不到 → false
 *
 * 所以恒返回 true 就等于「所有 App 的所有页面都在白名单里」。
 */
internal class WhiteListHooker(
    private val module: XposedModule,
    /** 宿主进程的 ClassLoader —— 必须用它，不能用模块自己的 */
    private val hostClassLoader: ClassLoader,
    /** 总开关提供者：由 HookEntry 持有，实时反映用户设置 */
    private val forceAllProvider: () -> Boolean,
) {

    private companion object {
        const val TAG = "[DragPlus]"

        /**
         * 白名单总闸门（R8 混淆名，但混淆后类名是确定的）：
         *   za.o.g(String packageName, String activityName) : boolean
         */
        const val CLS_GATE = "za.o"

        /** 主判定方法：按包名+Activity 判断能否拖 */
        const val METHOD_CHECK = "g"

        /** 用于确认环境：拖拽起始消息 Handler */
        const val CLS_HANDLER = "cb.h"

        /** 用于确认环境：中转岛白名单 bean（存在说明是拖放进程） */
        const val CLS_WL_BEAN =
            "com.vivo.common.dragdrop.data.bean.WhiteListResultBean"

        /** 打点日志限流：前 N 次全打，之后每 M 次打一条 */
        const val LOG_FIRST = 30
        const val LOG_EVERY = 200
    }

    private val hitCount = AtomicInteger(0)
    private val passCount = AtomicInteger(0)

    fun install() {
        ModLog.i("$TAG ClassLoader=${hostClassLoader.javaClass.name}")

        val gateCls = loadClass(CLS_GATE)
        if (gateCls == null) {
            dumpDiagnostics()
            return
        }

        ModLog.i(
            "$TAG 环境：gate=${gateCls.name} handler=${loadClass(CLS_HANDLER) != null} " +
                "bean=${loadClass(CLS_WL_BEAN) != null}",
        )

        hookGate(gateCls)
    }

    private fun hookGate(gateCls: Class<*>) {
        val method = findGate(gateCls)
        if (method == null) {
            ModLog.w(
                "$TAG 找不到 $CLS_GATE.$METHOD_CHECK(String,String)Z，该类方法：" +
                    gateCls.declaredMethods.joinToString { "${it.name}(${it.parameterCount})" }
                        .take(500),
            )
            return
        }
        ModLog.i("$TAG 目标方法：${method.toGenericString()}")

        runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = chain.args.getOrNull(0) as? String
                    val act = chain.args.getOrNull(1) as? String

                    // 关掉开关 → 完全走原生逻辑
                    if (!forceAllProvider()) {
                        val r = runCatching { chain.proceed() as? Boolean ?: false }
                            .getOrDefault(false)
                        log(pkg, act, r, r)
                        return@intercept r
                    }

                    val original = runCatching { chain.proceed() as? Boolean ?: false }
                        .getOrDefault(false)

                    // ★ 恒返回 true：任何 App 的任何 Activity 都算在白名单里
                    log(pkg, act, original, true)
                    if (!original) passCount.incrementAndGet()
                    true
                }
            ModLog.i("$TAG ✅ 已 hook ${gateCls.name}.$METHOD_CHECK —— 任意 App 可拖")
        }.onFailure {
            ModLog.e("$TAG 挂载 $METHOD_CHECK 失败", it)
        }
    }

    private fun log(pkg: String?, act: String?, original: Boolean, result: Boolean) {
        val n = hitCount.incrementAndGet()
        if (n > LOG_FIRST && n % LOG_EVERY != 0) return
        val mark = if (result && !original) "强行放行" else if (result) "原生通过" else "原生拒绝"
        ModLog.i("$TAG #$n $mark pkg=$pkg activity=$act original=$original → $result")
    }

    /**
     * 精确找 g(String,String)Z。
     * 只按「名字 g + 两个 String 参数 + 返回 boolean」定位，
     * 不做名字模糊匹配，避免挂到别的重载上。
     */
    private fun findGate(cls: Class<*>): Method? = runCatching {
        cls.declaredMethods.firstOrNull {
            it.name == METHOD_CHECK &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                (it.returnType == Boolean::class.javaPrimitiveType ||
                    it.returnType == java.lang.Boolean::class.java)
        }?.also { it.isAccessible = true }
    }.getOrNull()

    /**
     * 类加载失败时输出诊断，区分：
     *  1. 宿主 ClassLoader 里根本没有这个类 → 版本改名
     *  2. loader 用错
     */
    private fun dumpDiagnostics() {
        ModLog.w("$TAG ❌ 加载不到 $CLS_GATE")

        val altLoaders = listOfNotNull(
            "currentThread" to Thread.currentThread().contextClassLoader,
            "thisObj" to javaClass.classLoader,
        )
        for ((tag, cl) in altLoaders) {
            if (cl == null) {
                ModLog.w("$TAG    loader[$tag]=null")
                continue
            }
            val found = runCatching { Class.forName(CLS_GATE, false, cl) }.isSuccess
            ModLog.w("$TAG    loader[$tag]=${cl.javaClass.name} found=$found")
        }

        // 已知同包兄弟类，用于判断「只是改名」还是「整个版本结构变了」
        val siblings = listOf(
            CLS_HANDLER,
            CLS_WL_BEAN,
            "com.vivo.common.dragdrop.data.bean.EnhancedDragWhiteList",
            "com.vivo.common.dragdrop.data.bean.WhiteListResultBean",
            "com.vivo.dragdrop.service.DragService",
        )
        for (s in siblings) {
            ModLog.w("$TAG    兄弟类 $s = ${loadClass(s) != null}")
        }
    }

    /**
     * 用【宿主 ClassLoader】加载类。
     * initialize=false：只做 hook 目标定位，不要触发静态初始化。
     */
    private fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
}
