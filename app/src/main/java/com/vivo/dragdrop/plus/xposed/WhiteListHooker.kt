package com.vivo.dragdrop.plus.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
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
 * 为什么只改 getResult：
 *  - getResult() 是纯 getter（2 条指令，读 result:Z 字段），hook 它代价最低、
 *    不会破坏任何业务逻辑，也不影响 WhiteListResultBean 上其它字段。
 *  - 其它字段（type / imageFormat / strictModel / imageMinWidth ...）全部保留
 *    vivo 自己填的值 —— 这些值决定了后续「抓文字还是抓图 / 用什么格式」，
 *    一旦乱改（比如把 type 改成自定义串）就会走进未覆盖分支导致拖不起来。
 *  - 因此绝不 setResult / 不修改 bean，只覆写返回值。
 *
 * 为什么不 hook executionStrategy / checkWhiteList：
 *  - executionStrategy 有 4 个实现、参数含 WidgetBean 等复杂对象，
 *    hook 后要构造 WhiteListResultBean 返回值，容易 NPE。
 *  - checkWhiteList 内部还会读写 bean 字段做副作用，绕过它反而丢数据。
 *  - getResult 是唯一的「最终裁决读点」，改它等价于「全部通过」且零副作用。
 */
internal class WhiteListHooker(
    private val module: XposedModule,
    /** 总开关提供者：由 HookEntry 持有，实时反映用户设置 */
    private val forceAllProvider: () -> Boolean,
) {

    private companion object {
        const val TAG = "[WhiteList]"

        /** 白名单结果 Bean（R8 混淆后仍在原包名下，未改名） */
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
        // 环境确认：这些类只在拖放进程存在；不存在就直接退出，别瞎挂
        val beanCls = resolveOrNull(CLS_RESULT_BEAN)
        if (beanCls == null) {
            ModLog.w("$TAG 找不到 $CLS_RESULT_BEAN —— 当前进程不是拖放进程，跳过")
            return
        }
        val dispatchPresent = resolveOrNull(CLS_DISPATCH) != null
        val companionPresent = resolveOrNull(CLS_WL_COMPANION) != null
        ModLog.i("$TAG 环境：bean=${beanCls.simpleName} dispatch=$dispatchPresent companion=$companionPresent")

        hookGetResult(beanCls)
    }

    private fun hookGetResult(beanCls: Class<*>) {
        val method = runCatching { beanCls.getDeclaredMethod(METHOD_GET_RESULT) }.getOrNull()
        if (method == null) {
            ModLog.w("$TAG 找不到 $METHOD_GET_RESULT()，跳过")
            return
        }

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

    private fun resolveOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name) }.getOrNull()
}
