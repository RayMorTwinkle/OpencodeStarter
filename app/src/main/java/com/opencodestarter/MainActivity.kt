package com.opencodestarter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etWorkDir: EditText
    private lateinit var etPort: EditText
    private lateinit var cbListenAll: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var tvParsed: TextView

    /** 上次启动方式（重启时复用），默认后台 */
    private var lastMode: String = "background"

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = intent.getStringExtra(PluginResultsService.EXTRA_KIND) ?: "?"
            val text = intent.getStringExtra(PluginResultsService.EXTRA_RESULT_TEXT) ?: ""
            tvStatus.text = text
            if (kind == "probe") tvParsed.text = parseProbe(text, currentPortOrNull())
            else if (kind == "stop") tvParsed.text = parseProbeHintAfterStop(text)
            else tvStatus.append("") // start 回执仅展示原文
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWorkDir = findViewById(R.id.etWorkDir)
        etPort = findViewById(R.id.etPort)
        cbListenAll = findViewById(R.id.cbListenAll)
        tvStatus = findViewById(R.id.tvStatus)
        tvParsed = findViewById(R.id.tvParsed)
        savedInstanceState?.let {
            tvStatus.text = it.getString("status", "")
            tvParsed.text = it.getString("parsed", "")
            lastMode = it.getString("lastMode", "background")
        }

        findViewById<Button>(R.id.btnForeground).setOnClickListener { doStart(foreground = true) }
        findViewById<Button>(R.id.btnBackground).setOnClickListener { doStart(foreground = false) }
        findViewById<Button>(R.id.btnOpenTermux).setOnClickListener { openTermux() }

        // root 预热（后台线程，su 约 100-200ms，不卡 UI）
        Thread {
            val rooted = RootUtil.hasRoot()
            runOnUiThread {
                findViewById<TextView>(R.id.tvRoot).text =
                    if (rooted) "● root 自唤起：可用（Termux 未运行时自动拉起，无需手动点下面按钮）"
                    else "○ root 自唤起：不可用（请手动点下面按钮先打开 Termux 并挂后台）"
            }
        }.start()
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { doProbe() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { confirmStop() }
        findViewById<Button>(R.id.btnRestart).setOnClickListener { doRestart() }

        ContextCompat.registerReceiver(
            this, resultReceiver,
            IntentFilter(PluginResultsService.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("status", tvStatus.text.toString())
        outState.putString("parsed", tvParsed.text.toString())
        outState.putString("lastMode", lastMode)
        // EditText 带 id 会自动保存输入，无需手动处理（旋转不丢输入）
    }

    // ---------- 输入解析 ----------

    /** 端口：空 = 随机；非空必须 1-65535，否则 toast 并返回 null（调用方中止）。 */
    private fun currentPortOrNull(validate: Boolean = false): Int? {
        val raw = etPort.text.toString().trim()
        if (raw.isEmpty()) return null
        val n = raw.toIntOrNull()
        if (n == null || n !in 1..65535) {
            if (validate) toast("端口填写无效：请输入 1-65535，留空=随机端口")
            return INVALID
        }
        return n
    }

    private fun currentTarget(validatePort: Boolean = true): TermuxCommand.OpencodeTarget? {
        val port = currentPortOrNull(validatePort)
        if (validatePort && port == INVALID) return null
        val dir = etWorkDir.text.toString().trim()
        if (dir.isNotEmpty() && !dir.startsWith("/") && !dir.startsWith("~") && !dir.startsWith("\$PREFIX")) {
            toast("工作目录建议填绝对路径（如 /data/data/com.termux/files/home/...），空=HOME")
        }
        return TermuxCommand.OpencodeTarget(
            workDir = dir,
            port = if (port == INVALID) null else port,
            listenAll = cbListenAll.isChecked,
        )
    }

    /** 打开 Termux 并挂后台：Termux 进程没起来时外部启动指令会被系统拦掉，先走这一步。 */
    private fun openTermux() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(TermuxCommand.TERMUX_PACKAGE)
            if (launch == null) {
                toast("未检测到 Termux（com.termux），请先安装 Termux 0.118.x GITHUB 版")
                return
            }
            startActivity(launch)
            toast("已打开 Termux：稍等其启动后按 Home 键回来挂后台，再点启动")
        } catch (e: Exception) {
            toast("打开 Termux 失败：${e.message}，请手动打开一次并挂后台")
        }
    }

    // ---------- Termux 就绪门槛（root 自唤起） ----------

    /**
     * 有 root：Termux 没运行时自动拉起（shell 通道，无跨应用弹窗），就绪后再执行业务；
     * 无 root：直接执行业务（靠「先打开 Termux」按钮手动兜底，老链路）。
     */
    private fun withTermuxReady(action: () -> Unit) {
        if (!RootUtil.hasRoot()) {
            action()
            return
        }
        if (RootUtil.isTermuxRunning()) {
            action()
            return
        }
        tvStatus.text = "检测到 Termux 未运行，正在用 root 自动唤起…"
        Thread {
            RootUtil.startTermux()
            var ok = false
            val deadline = System.currentTimeMillis() + 9000
            while (!ok && System.currentTimeMillis() < deadline) {
                ok = RootUtil.isTermuxRunning()
                if (!ok) Thread.sleep(500)
            }
            runOnUiThread {
                if (ok) {
                    toast("Termux 已自动唤起，继续执行")
                    action()
                } else {
                    tvStatus.text = "root 自动唤起 Termux 超时：请点「先打开 Termux」手动开一次并挂后台，再重试。"
                    toast("自动唤起超时，请手动点「先打开 Termux」")
                }
            }
        }.start()
    }

    // ---------- 四按钮 ----------

    private fun doStart(foreground: Boolean) {
        val target = currentTarget() ?: return
        if (!TermuxCommand.isTermuxInstalled(this)) {
            toast("未检测到 Termux（com.termux），请先安装 Termux 0.118.x GITHUB 版")
            return
        }
        lastMode = if (foreground) "foreground" else "background"
        withTermuxReady { sendStart(foreground, target) }
    }

    private fun sendStart(foreground: Boolean, target: TermuxCommand.OpencodeTarget) {
        try {
            if (foreground) {
                startService(TermuxCommand.foregroundStart(target))
                bringTermuxToFront()
                toast("已发送前台启动，正在切到 Termux 窗口看日志")
                tvStatus.text = "已发送前台启动命令（前台 transcript 经 PendingIntent 回传不可靠，请直接看 Termux 窗口日志那行 Web interface: ...）"
            } else {
                val id = PluginResultsService.nextExecutionId()
                startService(TermuxCommand.backgroundStart(this, target, id))
                tvStatus.text = "后台启动命令已发送（#$id），等回执或点「刷新状态」…\n若长期无回执：系 Termux 版本不支持 PendingIntent，降级去 Termux 看。"
            }
        } catch (e: SecurityException) {
            toast("发送失败（权限）：${e.message}。去系统设置授予“Run commands in Termux”，Termux 内设 allow-external-apps=true")
        } catch (e: Exception) {
            toast("发送失败：${e.message}。已降级：去 Termux 窗口看")
        }
    }

    private fun doProbe() {
        val port = currentPortOrNull(validate = true)
        if (port == INVALID) return
        if (!TermuxCommand.isTermuxInstalled(this)) {
            toast("未检测到 Termux（com.termux）")
            return
        }
        withTermuxReady {
            try {
                val id = PluginResultsService.nextExecutionId()
                startService(TermuxCommand.probe(this, if (port == INVALID) null else port, id))
                toast("探活已发送（#$id）")
            } catch (e: SecurityException) {
                toast("探活发送失败（权限）：${e.message}")
            } catch (e: Exception) {
                toast("探活发送失败：${e.message}")
            }
        }
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("停止 opencode？")
            .setMessage("将执行 pkill -f 'opencode (web|serve)'，重复点不炸（幂等）。")
            .setPositiveButton("停止") { _, _ -> doStop() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doStop() {
        if (!TermuxCommand.isTermuxInstalled(this)) {
            toast("未检测到 Termux（com.termux）")
            return
        }
        withTermuxReady {
            try {
                val id = PluginResultsService.nextExecutionId()
                startService(TermuxCommand.stop(this, id))
                toast("停止已发送（#$id）")
            } catch (e: SecurityException) {
                toast("停止发送失败（权限）：${e.message}")
            } catch (e: Exception) {
                toast("停止发送失败：${e.message}")
            }
        }
    }

    private fun doRestart() {
        val target = currentTarget() ?: return
        if (!TermuxCommand.isTermuxInstalled(this)) {
            toast("未检测到 Termux（com.termux）")
            return
        }
        withTermuxReady {
            try {
                // 停止（幂等，进程不存在也不炸）
                startService(TermuxCommand.stop(this, PluginResultsService.nextExecutionId()))
                // 按当前输入 + 上次模式重新启动
                if (lastMode == "foreground") {
                    startService(TermuxCommand.foregroundStart(target))
                    bringTermuxToFront()
                    toast("重启已发送（前台模式）")
                } else {
                    val id = PluginResultsService.nextExecutionId()
                    startService(TermuxCommand.backgroundStart(this, target, id))
                    toast("重启已发送（后台模式 #$id）")
                }
            } catch (e: SecurityException) {
                toast("重启发送失败（权限）：${e.message}")
            } catch (e: Exception) {
                toast("重启发送失败：${e.message}")
            }
        }
    }

    /** 把 Termux 切到前台：有 root 走 shell 通道（无弹窗），无 root 走 App 跳转（Oplus 首次弹确认框）。 */
    private fun bringTermuxToFront() {
        if (RootUtil.hasRoot()) {
            Thread { RootUtil.bringTermuxForeground() }.start()
        } else {
            try {
                packageManager.getLaunchIntentForPackage(TermuxCommand.TERMUX_PACKAGE)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            } catch (_: Exception) { /* 切前台失败也不影响命令已送达 */ }
        }
    }

    // ---------- 解析 ----------

    private fun parseProbe(raw: String, port: Int?): String {
        val lines = raw.lines()
        val pgrepLines = lines.filter { it.contains("opencode") && !it.startsWith("[") }
            .filterNot { it.contains("pgrep -af") }
        val running = pgrepLines.isNotEmpty()
        val pid = pgrepLines.firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: "-"
        val url = if (port != null) "http://127.0.0.1:$port/" else "随机端口：看 Termux 窗口日志那行 Web interface: ..."
        return "是否运行：${if (running) "是" else "否"}\nPID：${if (running) pid else "-"}\nURL：${if (running) url else "-"}"
    }

    private fun parseProbeHintAfterStop(raw: String): String {
        val nose = !raw.contains("opencode web") && !raw.contains("opencode serve")
        return if (nose) "是否运行：否\nPID：-\nURL：-（已停止）" else "回执原文见上，pgrep 仍有残留可再点一次停止（幂等）"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val INVALID = -1
    }
}
