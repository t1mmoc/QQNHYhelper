package com.moe.nekopet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 周期看门狗：用 JobScheduler 把进程唤醒，做一次健康检查与自愈。
 *
 * 为什么是 JobScheduler 而不是别的：
 * - 前台服务需要 FOREGROUND_SERVICE 权限 → 会破坏「零权限」豁免，图标就藏不住了；
 * - 静态广播（BOOT_COMPLETED 等）需要 RECEIVE_BOOT_COMPLETED 权限 → 同样不行，
 *   而且 Android 8+ 已大幅收紧隐式广播对静态接收器的唤醒；
 * - 双进程守护在 Android 8+ 基本失效，且容易被判定为滥用。
 * JobScheduler 是系统 framework API，既不需要权限也不需要额外依赖，
 * 底层走 JobSchedulerService，国产 ROM 对它的干预也相对克制。
 *
 * 周期取 15 分钟：这是系统的最小建议间隔。华为/荣耀的 PowerGenie 会统计
 * 「唤醒系统的频率」，唤醒过密会被标记为异常应用并反向降权，所以宁可稀疏。
 *
 * 补充说明：无障碍服务本身由系统的 AccessibilityManagerService 管理，
 * 只要用户在设置里保持启用，系统会负责绑定、进程被杀也会重建。
 * 本任务只是多兜一层，并不追求「永不死」。
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 20260830
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                    ?: return
                // 已调度就不重复排，否则会不断重置计时器、反而永远不触发
                if (js.allPendingJobs.any { it.id == JOB_ID }) return
                val info = JobInfo.Builder(
                    JOB_ID, ComponentName(context, KeepAliveJobService::class.java)
                )
                    .setPeriodic(INTERVAL_MS)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build()
                js.schedule(info)
                Log.d(TAG, "已调度周期看门狗（15 分钟）")
            } catch (e: Throwable) {
                Log.e(TAG, "调度失败: " + e.message)
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            // 1) 唤醒后先修一遍危险状态：无障碍已关但图标还藏着 → 恢复图标，
            //    保证用户永远有回到本应用的入口。
            AppHider.restoreIconIfAccessibilityOff(this)
            // 2) 顺带把保活/兜底服务拉起来
            KeepAliveService.start(this)
            QQAccessibilityService.addLog("看门狗：已完成一次健康检查")
        } catch (e: Throwable) {
            Log.e(TAG, "执行异常: " + e.message)
        }
        // 工作是同步且很短的，直接结束
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 返回 true 让系统在条件允许时重新调度
        return true
    }
}
