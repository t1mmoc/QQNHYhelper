package com.moe.nekopet

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 保活 + 图标恢复兜底服务（普通后台服务，非前台服务）。
 *
 * ⚠️ 重要约束：本应用必须保持「零 uses-permission」。
 * Android 10（API 29）起，只要应用申请了任何权限，系统就会为它合成一个桌面入口
 * （synthesized activity），导致桌面图标无法真正隐藏；只有「不申请任何权限」的应用
 * 才能享受隐藏豁免。因此这里**不能**用前台服务（需要 FOREGROUND_SERVICE 权限），
 * 只能退化为 START_STICKY 的普通服务，优先级低于前台服务但聊胜于无。
 *
 * 主要职责：周期性检查「无障碍已关闭但图标仍隐藏」这一危险状态并恢复图标，
 * 避免用户关掉无障碍后永远进不了应用。
 */
class KeepAliveService : Service() {

    companion object {
        /** 拉起保活/兜底服务。普通后台服务，无需任何权限。 */
        fun start(context: android.content.Context) {
            try {
                context.startService(Intent(context, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startGuard()
    }

    override fun onDestroy() {
        guardHandler.removeCallbacks(guardRunnable)
        super.onDestroy()
    }

    /**
     * 常驻兜底：只要无障碍没开启、图标却还是隐藏的，就把图标恢复回来。
     *
     * 为什么需要它：关闭无障碍时，恢复逻辑跑在 QQAccessibilityService.onUnbind 的
     * 延迟回调里。国产 ROM 常在解绑后立刻杀进程，那段回调根本来不及执行 ——
     * 结果就是「用户关了无障碍、图标却永久消失，再也进不了应用」。
     */
    private val guardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val guardRunnable = object : Runnable {
        override fun run() {
            try {
                AppHider.restoreIconIfAccessibilityOff(this@KeepAliveService)
            } catch (_: Throwable) {
            }
            guardHandler.postDelayed(this, 30000)
        }
    }

    private fun startGuard() {
        guardHandler.removeCallbacks(guardRunnable)
        guardHandler.postDelayed(guardRunnable, 10000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startGuard()
        return START_STICKY // 进程被杀后系统自动重建
    }
}
