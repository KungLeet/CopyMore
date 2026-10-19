package com.copymore.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/** Runs against the user's already-paired development build; never reads clipboard contents. */
class BackgroundProbe : Instrumentation() {
    private var seconds = 60
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        seconds = arguments?.getString("seconds")?.toIntOrNull()?.coerceIn(15, 180) ?: 60
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val repo = Repository.get(targetContext)
            check(repo.state.value.server != null) { "请先配对 Mac" }
            check(repo.store.enabled) { "后台常驻未开启" }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (!repo.state.value.connected && SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
            check(repo.state.value.connected) { "未连接：${repo.state.value.status}; ${repo.state.value.lastDisconnect}" }
            report("前台连接成功")
            runOnMainSync { check(activity.moveTaskToBack(true)) { "无法将测试页面移入后台" } }
            verifyConnection(repo, "切到后台", seconds)
            runOnMainSync { activity.finishAndRemoveTask() }
            verifyConnection(repo, "移除最近任务", seconds)
            result.putString("result", "PASS: 后台和移除任务各 $seconds 秒保持局域网连接")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("result", "FAIL: ${failure.message}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun verifyConnection(repo: Repository, phase: String, duration: Int) {
        report("开始 $phase 检查")
        repeat(duration) {
            Thread.sleep(1000)
            check(repo.state.value.serviceRunning && repo.state.value.connected) {
                "$phase 后第 ${it + 1} 秒断连：${repo.state.value.lastDisconnect}; 服务=${repo.state.value.serviceRunning}"
            }
            if ((it + 1) % 20 == 0) report("$phase ${it + 1} 秒：服务与连接正常")
        }
    }
    private fun report(message: String) = sendStatus(0, Bundle().apply { putString("progress", message) })
}
