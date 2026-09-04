package com.opencodestarter

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Termux RUN_COMMAND Intent 组装（以 Termux 0.118.3 / 官方 wiki RUN_COMMAND-Intent 为准）。
 *
 * - action 固定 "com.termux.RUN_COMMAND"（注意：不是 com.termux.app.action.RUN_COMMAND）
 * - component 固定 com.termux / com.termux.app.RunCommandService
 * - 结果回传只支持 PendingIntent（extra key = com.termux.RUN_COMMAND_PENDING_INTENT），
 *   不支持 ResultReceiver；am startservice 发出的命令拿不到回执，必须走 Java + PendingIntent。
 */
object TermuxCommand {

    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** 结果 Bundle key（TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE，字面值 "result"） */
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"
    const val RESULT_STDOUT_LEN = "stdout_original_length"
    const val RESULT_STDERR_LEN = "stderr_original_length"

    const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
    const val OPENCODE_BIN = "$TERMUX_BIN/opencode"
    const val BASH_BIN = "$TERMUX_BIN/bash"

    /** session action "0" = 切到新 session 并打开 Termux Activity（前台启动用） */
    const val SESSION_SWITCH_TO_NEW = "0"

    data class OpencodeTarget(
        val workDir: String, // 空 = 不传 WORKDIR（Termux 默认 ~/）
        val port: Int?, // 空 = 随机端口（不拼 --port）
        val listenAll: Boolean, // true = --hostname 0.0.0.0，否则 127.0.0.1
    )

    /** opencode web 参数：web + --hostname X + 可选 --port N */
    fun opencodeArgs(t: OpencodeTarget): Array<String> {
        val args = mutableListOf("web", "--hostname", if (t.listenAll) "0.0.0.0" else "127.0.0.1")
        if (t.port != null) {
            args += "--port"
            args += t.port.toString()
        }
        return args.toTypedArray()
    }

    /** 探活脚本（只读）。端口为空时只跑 pgrep，不 curl。 */
    fun probeScript(port: Int?): String = if (port != null) {
        "pgrep -af 'opencode (web|serve)'; echo ---; " +
            "curl -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:$port/"
    } else {
        "pgrep -af 'opencode (web|serve)'; echo ---; echo no-port-specified"
    }

    fun stopScript(): String = "pkill -f 'opencode (web|serve)'; echo exit=$?"

    private fun baseIntent(): Intent = Intent().apply {
        setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        action = ACTION_RUN_COMMAND
    }

    /** 前台启动：Termux 切到前台可见终端。无结果回执（transcript 需去 Termux 窗口看）。 */
    fun foregroundStart(t: OpencodeTarget): Intent = baseIntent().apply {
        putExtra(EXTRA_PATH, OPENCODE_BIN)
        putExtra(EXTRA_ARGUMENTS, opencodeArgs(t))
        if (t.workDir.isNotBlank()) putExtra(EXTRA_WORKDIR, t.workDir.trim())
        putExtra(EXTRA_BACKGROUND, false)
        putExtra(EXTRA_SESSION_ACTION, SESSION_SWITCH_TO_NEW)
        putExtra(EXTRA_LABEL, "OpencodeStarter 前台启动")
    }

    /** 后台启动：Termux 不抢前台。resultKind 用于回执广播分类展示。 */
    fun backgroundStart(context: Context, t: OpencodeTarget, executionId: Int): Intent =
        baseIntent().apply {
            putExtra(EXTRA_PATH, OPENCODE_BIN)
            putExtra(EXTRA_ARGUMENTS, opencodeArgs(t))
            if (t.workDir.isNotBlank()) putExtra(EXTRA_WORKDIR, t.workDir.trim())
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_LABEL, "OpencodeStarter 后台启动")
            putExtra(EXTRA_PENDING_INTENT, resultPendingIntent(context, executionId, "start"))
        }

    /** 状态探活：后台跑 bash -c 探活脚本，结果经 PendingIntent 回传。 */
    fun probe(context: Context, port: Int?, executionId: Int): Intent = baseIntent().apply {
        putExtra(EXTRA_PATH, BASH_BIN)
        putExtra(EXTRA_ARGUMENTS, arrayOf("-c", probeScript(port)))
        putExtra(EXTRA_BACKGROUND, true)
        putExtra(EXTRA_LABEL, "OpencodeStarter 状态探活")
        putExtra(EXTRA_PENDING_INTENT, resultPendingIntent(context, executionId, "probe"))
    }

    /** 停止：后台跑 pkill，结果经 PendingIntent 回传。 */
    fun stop(context: Context, executionId: Int): Intent = baseIntent().apply {
        putExtra(EXTRA_PATH, BASH_BIN)
        putExtra(EXTRA_ARGUMENTS, arrayOf("-c", stopScript()))
        putExtra(EXTRA_BACKGROUND, true)
        putExtra(EXTRA_LABEL, "OpencodeStarter 停止")
        putExtra(EXTRA_PENDING_INTENT, resultPendingIntent(context, executionId, "stop"))
    }

    private fun resultPendingIntent(context: Context, executionId: Int, kind: String): PendingIntent {
        // requestCode 必须每次唯一，否则 FLAG_ONE_SHOT 下只有第一次能收到回执。
        val serviceIntent = Intent(context, PluginResultsService::class.java).apply {
            putExtra(PluginResultsService.EXTRA_EXECUTION_ID, executionId)
            putExtra(PluginResultsService.EXTRA_KIND, kind)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getService(context, executionId, serviceIntent, flags)
    }

    /** Termux 是否已安装（发之前先查，给出明确 toast 而不是静默失败）。 */
    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
