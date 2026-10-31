package com.vivo.dragdrop.plus.xposed

import android.util.Log

/**
 * 统一日志出口。
 *
 * 三种输出：
 *  1. Logcat（tag = [TAG]）—— 现场调试用
 *  2. 落盘文件 /sdcard/Documents/DragDropPlus/module.log —— 用户导出用
 *
 * 落盘失败一律静默吞掉：日志本身不能成为崩溃源。
 */
object ModLog {

    private const val TAG = "DragDropPlus"

    /** 由 HookEntry 在进程内首次加载时注入；为 null 时只打 logcat */
    @Volatile
    private var writer: ((String) -> Unit)? = null

    @Volatile
    var debugEnabled: Boolean = false

    fun install(w: (String) -> Unit) {
        writer = w
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        writer?.invoke("I $msg")
    }

    fun d(msg: String) {
        if (!debugEnabled) return
        Log.d(TAG, msg)
        writer?.invoke("D $msg")
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        writer?.invoke("W $msg")
    }

    fun e(msg: String, tr: Throwable? = null) {
        Log.e(TAG, msg, tr)
        writer?.invoke("E $msg" + (tr?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }
}
