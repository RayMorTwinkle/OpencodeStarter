package com.opencodestarter

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * root 自唤起层（KernelSU / Magisk su 均可）。
 *
 * 解决的问题：Termux 进程没起来时，外部 App 的 RUN_COMMAND 会被系统静默吞掉；
 * 且 Oplus/ColorOS 上跨应用拉 Termux 要弹确认框。走 root 起 Termux 走的是
 * shell 通道，两者都绕过，全自动、无弹窗。
 *
 * 无 root 时全部回退到老链路（「先打开 Termux」按钮手动兜底）。
 */
object RootUtil {
    private const val TAG = "RootUtil"
    const val TERMUX_ACTIVITY = "com.termux/.app.TermuxActivity"

    @Volatile private var cachedRoot: Boolean? = null

    data class ExecResult(val code: Int, val out: String)

    fun exec(cmd: String, timeoutSec: Long = 5): ExecResult? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            null
        } else {
            ExecResult(p.exitValue(), p.inputStream.bufferedReader().readText().trim().take(2000))
        }
    } catch (e: Exception) {
        Log.w(TAG, "exec failed: ${e.message}")
        null
    }

    /** 是否有 root（结果缓存，首次约 100-200ms，建议后台线程预热）。 */
    fun hasRoot(): Boolean {
        cachedRoot?.let { return it }
        val ok = exec("id", 3)?.let { it.code == 0 && it.out.contains("uid=0") } == true
        cachedRoot = ok
        Log.d(TAG, "hasRoot=$ok")
        return ok
    }

    fun termuxPids(): List<String> =
        exec("pidof com.termux", 3)?.out
            ?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()

    fun isTermuxRunning(): Boolean = termuxPids().isNotEmpty()

    /** root 身份冷起 Termux 主界面（附带拉起 TermuxService），成功返回 true。 */
    fun startTermux(): Boolean {
        val r = exec("am start -n $TERMUX_ACTIVITY", 10) ?: return false
        Log.d(TAG, "startTermux code=${r.code} out=${r.out.take(200)}")
        return r.code == 0
    }

    /** root 身份把 Termux 切到前台（前台启动用，无跨应用弹窗）。 */
    fun bringTermuxForeground(): Boolean {
        val r = exec("am start -n $TERMUX_ACTIVITY", 10) ?: return false
        return r.code == 0
    }
}
