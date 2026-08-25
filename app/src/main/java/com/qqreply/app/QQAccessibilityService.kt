package com.qqreply.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class QQAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QQAccessibilityService? = null
        const val TAG = "QQReply"
    }

    // 当前活动分类下的生效规则
    private var activeRules: List<Rule> = emptyList()
    private var suffix = ConfigStore.DEFAULT_SUFFIX
    private var lastReplaceTime = 0L
    private var lastSet = ""
    private var userOriginal = ""
    private var processing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        reload()
        Log.d(TAG, "QQAccessibilityService onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "QQAccessibilityService onDestroy")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        reload()
        Log.d(TAG, "QQAccessibilityService onServiceConnected")
        showToast("QQNHY助手服务已启动")
    }

    /** 从 ConfigStore 重新加载配置：仅启用当前活动分类中的规则 */
    fun reload() {
        val cfg = ConfigStore.load(this)
        suffix = cfg.suffix
        val active = cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()
        activeRules = active?.rules ?: emptyList()
        Log.d(TAG, "规则分类=[${active?.name}] 规则数=[${activeRules.size}] 后缀=[$suffix]")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // 支持 QQ / QQ轻聊版 / Tim 等腾讯系列包名
        if (pkg != "com.tencent.mobileqq" && pkg != "com.tencent.qq"
            && pkg != "com.tencent.tim" && !pkg.startsWith("com.tencent.mobileqq")) return

        when (event.eventType) {
            // 切换窗口：重置防回显状态
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                processing = false
                lastSet = ""
                userOriginal = ""
                lastReplaceTime = 0L
            }

            // 点击：若点击的是发送按钮，兜底处理一次（即使实时监听没触发也能替换）
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isSendButton(event)) doProcess(false)
            }

            // 输入框文本变化：打字时实时替换
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                doProcess(true)
            }
        }
    }

    private fun isSendButton(event: AccessibilityEvent): Boolean {
        val node = event.source ?: return false
        return try {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            val label = text + desc
            // 按钮文字/描述含"发送/send"，或资源 id 是 send 系列（兼容新版 QQ 的可访问性标签差异）
            label.contains("发送") || label.contains("send", true) ||
                (cls.contains("Button", true) && desc.contains("发送")) ||
                id.lowercase().contains("send")
        } finally {
            node.recycle()
        }
    }

    private fun doProcess(isTextChange: Boolean) {
        if (processing) return
        processing = true
        try {
            val root = rootInActiveWindow ?: return
            try {
                val inputNode = findInputField(root) ?: return
                try {
                    val raw = inputNode.text?.toString() ?: ""
                    if (raw.isEmpty()) {
                        userOriginal = ""
                        return
                    }

                    val now = System.currentTimeMillis()
                    // 防止回显死循环：600ms 内输入框内容仍是我们刚写入的 lastSet，跳过
                    if (lastReplaceTime > 0 && now - lastReplaceTime < 600 && raw == lastSet) {
                        lastReplaceTime = 0
                        return
                    }

                    // 增量还原用户真实输入：若当前文本是"上次写入 + 新输入"，则只追加新增部分；
                    // 否则（用户删改/从中间修改），从当前文本剥离我们加的尾巴来还原
                    val original = if (lastSet.isNotEmpty() && raw.startsWith(lastSet)) {
                        userOriginal + raw.removePrefix(lastSet)
                    } else if (userOriginal.isNotEmpty() && lastSet == raw) {
                        // 回显已被上次判断拦截，走到这里说明是原始输入
                        userOriginal
                    } else {
                        restoreUserInput(raw)
                    }
                    if (original.isEmpty()) return

                    val newText = applyRules(original)
                    if (newText == raw) {
                        userOriginal = original
                        return
                    }

                    if (setText(inputNode, newText)) {
                        userOriginal = original
                        lastSet = newText
                        lastReplaceTime = now
                    }
                } finally {
                    inputNode.recycle()
                }
            } finally {
                root.recycle()
            }
        } finally {
            processing = false
        }
    }

    // 把含替换结果和尾巴的当前文本还原为接近用户原始输入
    private fun restoreUserInput(text: String): String {
        var t = text
        if (t.endsWith(suffix) && suffix.isNotEmpty()) {
            t = t.removeSuffix(suffix)
        }
        // 反向替换最近一次的替换结果，尽量还原
        for (rule in activeRules) {
            if (rule.enabled && rule.to.isNotEmpty()) {
                t = t.replace(rule.to, rule.from)
            }
        }
        return t
    }

    private fun applyRules(text: String): String {
        var newText = text
        for (rule in activeRules) {
            if (rule.enabled) newText = newText.replace(rule.from, rule.to)
        }
        return newText + suffix
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                // 把光标移动到文本末尾，保证后续输入位置正确
                val sel = Bundle()
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.tencent.mobileqq:id/inputBar",
            "com.tencent.mobileqq:id/chat_input",
            "com.tencent.mobileqq:id/message_edit",
            "com.tencent.mobileqq:id/sendMessageEditText",
            "com.tencent.mobileqq:id/msg_input_et",
            "com.tencent.mobileqq:id/footer_et_msg",
            "com.tencent.mobileqq:id/input_edit",
            "com.tencent.mobileqq:id/input",
            "com.tencent.mobileqq:id/edtInput",
            "com.tencent.mobileqq:id/et_input",
            "com.tencent.mobileqq:id/inputEdt",
            "com.tencent.mobileqq:id/inputbar",
            "com.tencent.qq:id/input",
            "com.tencent.qq:id/edtInput"
        )

        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        try {
                            if (n.isEditable) {
                                // 返回一个副本，避免 finally 里回收后返回已回收的节点
                                return AccessibilityNodeInfo.obtain(n)
                            }
                        } finally {
                            n.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "查找ID $id 出错: " + e.message)
            }
        }

        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findEditable(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun showToast(text: String) {
        mainHandler.post { Toast.makeText(this@QQAccessibilityService, text, Toast.LENGTH_LONG).show() }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onGesture(gestureId: Int): Boolean = false
}
