package com.moe.nekopet

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityManager

/**
 * 系统快捷设置面板的服务开关磁贴。
 * 受系统限制无法静默开启无障碍服务：磁贴实时显示服务状态，
 * 点击直接跳转到系统的无障碍设置页，便于快速开启。
 */
class QuickReplyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        refreshState()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivityAndCollapse(intent)
        } catch (e: Exception) {
            // 兜底：若收起启动失败则直接普通启动（可能不收起面板）
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshState() {
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = isAccessibilityEnabled()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "服务已开启" else "服务未开启"
        }
        tile.updateTile()
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si?.packageName == packageName && si?.name == QQAccessibilityService::class.java.name
                }
        } catch (e: Exception) {
            false
        }
    }
}