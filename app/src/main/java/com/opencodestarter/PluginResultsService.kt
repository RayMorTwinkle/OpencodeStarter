package com.opencodestarter

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Termux 结果接收 Service。
 *
 * 官方只支持 PendingIntent 回传（EXTRA_PENDING_INTENT），不支持 ResultReceiver：
 * Termux 把 stdout/stderr/exitCode/err/errmsg 装进 key="result" 的 Bundle，
 * 经 PendingIntent 调起本 Service。本 Service 再转成同包显式广播，由 MainActivity 展示。
 *
 * 降级路径：若 Termux 版本过旧（<0.109）或回执丢失，App 端靠超时文案提示
 * "已发送，去 Termux 看"，见 MainActivity.sendWithFeedback()。
 */
class PluginResultsService : Service() {

    companion object {
        const val TAG = "PluginResults"
        const val EXTRA_EXECUTION_ID = "execution_id"
        const val EXTRA_KIND = "kind" // start | probe | stop

        const val ACTION_RESULT = "com.opencodestarter.RESULT"
        const val EXTRA_RESULT_TEXT = "result_text"

        private var nextId = 1000

        @Synchronized
        fun nextExecutionId(): Int = nextId++
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val kind = intent?.getStringExtra(EXTRA_KIND) ?: "?"
            val id = intent?.getIntExtra(EXTRA_EXECUTION_ID, 0) ?: 0
            val bundle = intent?.getBundleExtra(TermuxCommand.RESULT_BUNDLE)
            val text = formatResult(id, kind, bundle)
            Log.d(TAG, text)
            sendBroadcast(Intent(ACTION_RESULT).apply {
                setPackage(packageName)
                putExtra(EXTRA_EXECUTION_ID, id)
                putExtra(EXTRA_KIND, kind)
                putExtra(EXTRA_RESULT_TEXT, text)
            })
        } catch (e: Exception) {
            Log.e(TAG, "handle result failed: ${e.message}", e)
        } finally {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun formatResult(id: Int, kind: String, b: Bundle?): String {
        if (b == null) return "[$kind #$id] 未收到 result Bundle（Termux 版本过旧或回执丢失，去 Termux 窗口看）"
        val stdout = b.getString(TermuxCommand.RESULT_STDOUT, "") ?: ""
        val stderr = b.getString(TermuxCommand.RESULT_STDERR, "") ?: ""
        val exit = b.getInt(TermuxCommand.RESULT_EXIT_CODE, -999)
        val err = b.getInt(TermuxCommand.RESULT_ERR, -999)
        val errmsg = b.getString(TermuxCommand.RESULT_ERRMSG, "") ?: ""
        return buildString {
            append("[$kind #$id] exit=$exit err=$err")
            if (stdout.isNotBlank()) append("\nstdout:\n$stdout")
            if (stderr.isNotBlank()) append("\nstderr:\n$stderr")
            if (errmsg.isNotBlank()) append("\nerrmsg:\n$errmsg")
        }.take(4000)
    }
}
