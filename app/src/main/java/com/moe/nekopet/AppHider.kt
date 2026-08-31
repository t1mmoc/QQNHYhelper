package com.moe.nekopet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 应用自身隐藏控制。
 *
 * 可以隐藏（无需 root）：
 * 1. 桌面图标 —— 通过启用/禁用本应用的 launcher 别名组件实现。
 *    Android 允许应用修改**自己包内**组件的启用状态，这是普通权限，无需 root。
 * 2. 最近任务（任务后台）—— 通过 Manifest 中 android:excludeFromRecents="true" 实现。
 *
 * 无法隐藏（需 root / 系统签名，本需求已跳过）：
 * · 系统「设置 → 应用管理」列表中的本应用。该列表由系统读取设备上所有已安装包生成，
 *   普通应用无权把自己从中移除，只能借助 pm hide 等 root 手段。
 *
 * 重要：隐藏图标后必须保留一条回到应用的通路（这里是常驻通知），
 * 否则用户将永远无法再进入设置页把图标恢复回来。
 */
object AppHider {

    private const val ALIAS_NAME = "com.moe.nekopet.LauncherAlias"
    private const val CHANNEL_ID = "app_hider"
    private const val NOTIFICATION_ID = 1001

    /** Android 13+ 需要运行时授予通知权限 */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isLauncherIconHidden(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(
            ComponentName(context, ALIAS_NAME)
        )
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }


    /**
     * 当前无障碍服务是否在系统里处于启用状态。
     * 注意：读的是系统设置里"用户是否勾选了本服务"，不是"服务进程是否活着"。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val expected = "${context.packageName}/${QQAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(expected, ignoreCase = true) }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 兜底恢复：只要无障碍没开启，桌面图标就不该是隐藏状态。
     *
     * 场景：用户在无障碍开启状态下重启手机 / 系统强杀进程，
     * QQAccessibilityService.onUnbind 里那段 5 秒延迟恢复来不及执行，
     * 图标会永久停留在隐藏状态，用户再也找不到应用。
     * KeepAliveService 每 30 秒轮询、以及每次进入 MainActivity 都会调这里兜底。
     */
    fun restoreIconIfAccessibilityOff(context: Context) {
        try {
            if (!isAccessibilityEnabled(context) && isLauncherIconHidden(context)) {
                setLauncherIconHidden(context, false)
                cancelRecoveryNotification(context)
            }
        } catch (e: Throwable) {
            // 兜底逻辑本身不允许抛异常
        }
    }

    fun setLauncherIconHidden(context: Context, hidden: Boolean) {
        val state = if (hidden) {
            // 必须用 DISABLED。实测 DISABLED_UNTIL_USED 无效：
            // PMS 在 queryIntentActivities 时仍把它当可用组件返回（dumpsys 里落在
            // enabledComponents），桌面图标反而一个都不消失。
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        // DONT_KILL_APP：切换图标时不要把正在运行的应用和无障碍服务一起杀掉
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, ALIAS_NAME), state, PackageManager.DONT_KILL_APP
        )
    }

    fun cancelRecoveryNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
