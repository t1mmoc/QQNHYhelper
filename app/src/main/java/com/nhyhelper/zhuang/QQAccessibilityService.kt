package com.nhyhelper.zhuang

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

        // 内存调试日志缓冲
        private const val LOG_MAX = 400
        private val logList = ArrayDeque<String>()
        /** 界面可注册它，在收到新日志时刷新显示（会在主线程回调） */
        @Volatile
        var logListener: (() -> Unit)? = null

        @Synchronized
        fun addLog(line: String) {
            val ts = android.text.format.DateFormat.format(
                "HH:mm:ss", java.util.Date()
            ).toString()
            logList.addLast("[$ts] $line")
            while (logList.size > LOG_MAX) logList.removeFirst()
            instance?.onNewLog()
        }

        @Synchronized
        fun getLogs(): List<String> = logList.toList()

        @Synchronized
        fun clearLogs() {
            logList.clear()
            instance?.onNewLog()
        }
    }

    /** 有服务实例时直接主线程回调，无实例则忽略（界面下拉刷新兜底） */
    private fun onNewLog() {
        val l = logListener
        if (l != null) mainHandler.post { l() }
    }

    // 当前活动分类下的生效规则与后缀（多条，随机选一条已启用的）
    private var activeRules: List<Rule> = emptyList()
    private var activeSuffixes: List<Suffix> = emptyList()
    // 软件功能总开关：关闭时不做任何替换
    private var funcEnabled = true
    private var lastReplaceTime = 0L
    private var lastSet = ""
    private var lastSuffix = ""
    private var userOriginal = ""
    private var processing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = java.util.Random()
    // 事件日志节流：文本变化事件太多，只定期汇总打印
    private var textChangeCount = 0
    private var lastEventPkg = ""

    override fun onCreate() {
        super.onCreate()
        // 绑定瞬间若初始化抛异常会直接导致开关回弹，做防御保护
        try {
            instance = this
            addLog("服务 onCreate")
            reload()
            Log.d(TAG, "QQAccessibilityService onCreate")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate 异常: " + e.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        addLog("服务 onDestroy 实例已释放")
        instance = null
        Log.d(TAG, "QQAccessibilityService onDestroy")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        addLog("服务 onUnbind 被系统断开绑定")
        val r = super.onUnbind(intent)
        Log.d(TAG, "onUnbind")
        return r
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 绑定成功时初始化若出错也不能崩溃，否则开关会被系统回退
        try {
            reload()
            addLog("服务已连接并启动，功能开关=$funcEnabled")
            Log.d(TAG, "QQAccessibilityService onServiceConnected")
            showToast("QQNHY助手服务已启动")
        } catch (e: Throwable) {
            Log.e(TAG, "onServiceConnected 异常: " + e.message)
        }
    }

    /** 从 ConfigStore 重新加载配置：仅启用当前活动分类中的规则与后缀 */
    fun reload() {
        val cfg = ConfigStore.load(this)
        val active = cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()
        activeRules = active?.rules ?: emptyList()
        activeSuffixes = (active?.suffixes ?: emptyList()).filter { it.enabled && it.text.isNotBlank() }
        funcEnabled = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
            .getBoolean("func_enabled", true)
        Log.d(TAG, "规则分类=[${active?.name}] 规则数=[${activeRules.size}] 生效后缀数=[${activeSuffixes.size}] 功能=[$funcEnabled]")
    }

    /** 由界面设置软件功能总开关 */
    fun setFuncEnabled(enabled: Boolean) {
        funcEnabled = enabled
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件处理全程兜底：任何异常都不能向上抛，否则系统会判定服务崩溃并自动关闭开关
        try {
            handleAccessibilityEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "onAccessibilityEvent 异常: " + e.message)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // 支持 QQ / QQ轻聊版 / Tim 等腾讯系列包名
        if (pkg != "com.tencent.mobileqq" && pkg != "com.tencent.qq"
            && pkg != "com.tencent.tim" && !pkg.startsWith("com.tencent.mobileqq")) return

        when (event.eventType) {
            // 切换窗口：重置防回显状态
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                addLog("收到窗口切换事件 pkg=$pkg")
                textChangeCount = 0
                processing = false
                lastSet = ""
                lastSuffix = ""
                userOriginal = ""
                lastReplaceTime = 0L
            }

            // 点击：若点击的是发送按钮，兜底处理一次（即使实时监听没触发也能替换）
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                addLog("收到点击事件 pkg=$pkg isSendButton=${isSendButton(event)}")
                if (isSendButton(event)) doProcess(false)
            }

            // 输入框文本变化：打字时实时替换
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                doProcess(true)
                // 节流打印：每 50 次文本变化汇总一次
                textChangeCount++
                if (textChangeCount % 50 == 1) {
                    addLog("收到文本变化事件(累计 $textChangeCount 次) pkg=$pkg")
                }
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
        // 功能总开关关闭时不进行替换
        if (!funcEnabled) {
            logThrottled("功能开关已关闭，跳过替换", 500)
            return
        }
        if (processing) return
        processing = true
        try {
            val root = rootInActiveWindow ?: return
            try {
                val inputNode = findInputField(root) ?: run {
                    logThrottled("未找到输入框（root=$root）", 2000)
                    return
                }
                try {
                    val raw = inputNode.text?.toString() ?: ""
                    if (raw.isEmpty()) {
                        userOriginal = ""
                        lastSet = ""
                        lastSuffix = ""
                        return
                    }

                    val now = System.currentTimeMillis()
                    // 防止回显死循环：600ms 内输入框内容仍是我们刚写入的 lastSet，跳过
                    if (lastReplaceTime > 0 && now - lastReplaceTime < 600 && raw == lastSet) {
                        lastReplaceTime = 0
                        return
                    }

                    // 若上次写入带后缀且用户删除了后缀的一部分，自动补全整条后缀
                    if (lastSuffix.isNotEmpty()) {
                        val basePart = if (lastSet.endsWith(lastSuffix)) {
                            lastSet.dropLast(lastSuffix.length)
                        } else {
                            lastSet
                        }
                        // 输入文本 = 「原文」 + 原有后缀的前缀（少了一部分）= 前缀仍在补全范围
                        if (raw.length < lastSet.length && raw.startsWith(basePart)
                            && lastSuffix.startsWith(raw.removePrefix(basePart))) {
                            val full = basePart + lastSuffix
                            if (setText(inputNode, full)) {
                                lastSet = full
                                lastReplaceTime = now
                                setCursorEnd(inputNode, basePart.length)
                                logThrottled("后缀被删，自动补全：\"$full\"", 1000)
                            } else {
                                logThrottled("补全写入失败", 1000)
                            }
                            return
                        }
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

                    val replaced = applyRulesNoSuffix(original)
                    val suffix = pickSuffix()
                    val newText = replaced + suffix
                    if (newText == raw) {
                        userOriginal = original
                        lastSuffix = suffix
                        return
                    }

                    if (setText(inputNode, newText)) {
                        userOriginal = original
                        lastSuffix = suffix
                        lastSet = newText
                        lastReplaceTime = now
                        // 附加后缀后，光标回到用户原文末尾（不含后缀）
                        setCursorEnd(inputNode, replaced.length)
                        logThrottled("替换成功：\"$original\" -> \"$newText\"", 1000)
                    } else {
                        logThrottled("写入文本失败", 1000)
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
        // 不知道随机选中的是哪一条，逐条尝试剥掉结尾的已启用后缀（取最长匹配）
        for (s in activeSuffixes.map { it.text }.sortedByDescending { it.length }) {
            if (s.isNotEmpty() && t.endsWith(s)) {
                t = t.removeSuffix(s)
                break
            }
        }
        // 反向替换最近一次的替换结果，尽量还原
        for (rule in activeRules) {
            if (rule.enabled && rule.to.isNotEmpty()) {
                t = t.replace(rule.to, rule.from)
            }
        }
        return t
    }

    private fun pickSuffix(): String {
        if (activeSuffixes.isEmpty()) return ""
        return activeSuffixes[random.nextInt(activeSuffixes.size)].text
    }

    private fun applyRulesNoSuffix(text: String): String {
        var newText = text
        for (rule in activeRules) {
            if (rule.enabled) newText = newText.replace(rule.from, rule.to)
        }
        return newText
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    /** 把光标定位到文本框指定索引处 */
    private fun setCursorEnd(node: AccessibilityNodeInfo, index: Int) {
        try {
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, index)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, index)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        } catch (_: Exception) {
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

    // 按 minIntervalMs 节流打印，避免刷屏
    private var lastThrottleLog: Long = 0
    private fun logThrottled(msg: String, minIntervalMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastThrottleLog >= minIntervalMs) {
            lastThrottleLog = now
            addLog(msg)
        }
    }

    override fun onInterrupt() {
        addLog("onInterrupt（服务被系统中断）")
        Log.d(TAG, "onInterrupt")
    }

    override fun onGesture(gestureId: Int): Boolean = false
}
