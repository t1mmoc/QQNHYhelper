package com.moe.nekopet

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
            val full = "[$ts] $line"
            logList.addLast(full)
            while (logList.size > LOG_MAX) logList.removeFirst()
            // 同时落 logcat：服务在后台跑，故障时不用打开界面也能排查
            Log.d(TAG, full)
            instance?.onNewLog()
        }

        @Synchronized
        fun getLogs(): List<String> = logList.toList()

        @Synchronized
        fun clearLogs() {
            logList.clear()
            instance?.onNewLog()
        }

        /** 上次「杀桌面清残格」的时间戳，用于冷却，避免反复重启桌面 */
        @Volatile
        var lastLauncherFixTime = 0L
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
    // 替换时机：realtime（实时替换，默认）/ onsend（仅按下发送键时替换）
    private var replaceMode = "realtime"
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
        // 服务销毁（用户关闭无障碍开关）时恢复桌面图标，避免隐藏后无法找回应用
        try {
            if (AppHider.isLauncherIconHidden(this)) {
                AppHider.setLauncherIconHidden(this, false)
                AppHider.cancelRecoveryNotification(this)
                addLog("无障碍服务已关闭，恢复桌面图标")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onDestroy 恢复图标异常: " + e.message)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        addLog("服务 onUnbind 被系统断开绑定")
        val r = super.onUnbind(intent)
        Log.d(TAG, "onUnbind")
        // 关键：onDestroy 不一定被调用（系统可能直接杀进程），恢复逻辑必须放 onUnbind。
        // 立刻检查一次：用户真关掉时系统设置值会马上变化，越早恢复越安全 ——
        // 只等 5 秒的话，进程一旦在这期间被杀，图标就永久隐藏、用户再也进不来。
        // 但解绑瞬间 Settings 读取不可靠，所以这次是「二次确认」而非立即恢复。
        mainHandler.post { checkAndRestoreIfDisabled("立即", confirmLater = true) }
        // 5 秒后再确认一次，此时状态已稳定，可直接恢复。
        mainHandler.postDelayed({ checkAndRestoreIfDisabled("5 秒后", confirmLater = false) }, 5000)
        return r
    }

    /**
     * 若无障碍确实已关闭而图标还隐藏，就恢复（用户必须始终有回到应用的入口）。
     *
     * @param confirmLater true 时不马上恢复，而是 1.5 秒后二次确认再恢复。
     *   华为/荣耀存在「临时解绑后立刻重绑」的行为，解绑瞬间读 Settings 不可靠。
     *   若据此误判成「用户关闭了无障碍」而恢复图标，服务重连后 applyAutoHide 会发现
     *   图标可见 → 再隐藏一次 → 再杀一次桌面，表现为反复弹设置页、反复重启桌面。
     */
    private fun checkAndRestoreIfDisabled(tag: String, confirmLater: Boolean) {
        try {
            if (isAccessibilityEnabled()) {
                addLog("onUnbind ${tag}检查：用户仍启用本服务（系统临时解绑），保持隐藏")
                return
            }
            if (!AppHider.isLauncherIconHidden(this)) return
            if (confirmLater) {
                mainHandler.postDelayed({
                    try {
                        if (!isAccessibilityEnabled() && AppHider.isLauncherIconHidden(this)) {
                            AppHider.setLauncherIconHidden(this, false)
                            AppHider.cancelRecoveryNotification(this)
                            addLog("onUnbind ${tag}检查：二次确认无障碍确已关闭，恢复桌面图标")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "二次确认恢复异常: " + e.message)
                    }
                }, 1500)
                return
            }
            AppHider.setLauncherIconHidden(this, false)
            AppHider.cancelRecoveryNotification(this)
            addLog("onUnbind ${tag}检查：用户已关闭无障碍，恢复桌面图标")
        } catch (e: Throwable) {
            Log.e(TAG, "onUnbind 恢复异常: " + e.message)
        }
    }

    /** 检查用户是否真的启用了本无障碍服务（系统 enabled_accessibility_services 列表） */
    private fun isAccessibilityEnabled(): Boolean = AppHider.isAccessibilityEnabled(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 绑定成功时初始化若出错也不能崩溃，否则开关会被系统回退
        try {
            reload()
            addLog("服务已连接并启动，功能开关=$funcEnabled")
            Log.d(TAG, "QQAccessibilityService onServiceConnected")
            showToast("猫娘已显现喵～")
            applyAutoHide()
            // 保活：普通后台服务 + JobScheduler 周期看门狗。
            // 零权限约束下用不了前台服务，这两层是能拿到的最高优先级组合。
            KeepAliveService.start(this)
            KeepAliveJobService.schedule(this)
        } catch (e: Throwable) {
            Log.e(TAG, "onServiceConnected 异常: " + e.message)
        }
    }

    /**
     * 服务开启后按设置隐藏桌面图标。
     * 无条件隐藏（需求：无障碍服务开启即自动隐藏），不依赖通知权限。
     * 不发送任何「恢复通知」；恢复入口统一走 restoreIconIfAccessibilityOff：
     * 关闭无障碍服务（或从系统设置重新显示）即可让桌面图标自动恢复，无需通知。
     */
    private fun applyAutoHide() {
        try {
            val prefs = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("auto_hide_icon", true)) return
            if (!AppHider.isLauncherIconHidden(this)) {
                // 先主动关闭主界面，再禁用别名组件。
                // 若跳过这步，系统会强行销毁「以 LauncherAlias 身份启动」的 Activity，
                // 历史上正是该路径抛出 FragmentManager 异常并带崩整个进程。
                MainActivity.finishIfOpen()
                AppHider.setLauncherIconHidden(this, true)
                addLog("已隐藏桌面图标（恢复方式：关闭无障碍服务）")
            }
            // 注：本应用保持零权限，Android 10+ 的「合成桌面入口」豁免因此生效，
            // 禁用别名后图标会真正从桌面消失，不再需要任何重启桌面的 hack。
        } catch (e: Throwable) {
            Log.e(TAG, "applyAutoHide 异常: " + e.message)
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
        replaceMode = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
            .getString("replace_mode", "realtime") ?: "realtime"
        Log.d(TAG, "规则分类=[${active?.name}] 规则数=[${activeRules.size}] 生效后缀数=[${activeSuffixes.size}] 功能=[$funcEnabled] 时机=[$replaceMode]")
    }

    /** 由界面设置软件功能总开关 */
    fun setFuncEnabled(enabled: Boolean) {
        funcEnabled = enabled
    }

    /** 由界面设置替换时机 */
    fun setReplaceMode(mode: String) {
        replaceMode = mode
        addLog("替换时机已切换为：${if (mode == "onsend") "仅发送时" else "实时"}")
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

            // 点击：若点击的是发送按钮，按当前时机处理
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val isSend = isSendButton(event)
                addLog("收到点击事件 pkg=$pkg isSendButton=$isSend")
                Log.d(TAG, "CLICKED pkg=$pkg isSend=$isSend replaceMode=$replaceMode")
                if (isSend) {
                    if (replaceMode == "onsend") {
                        onSendReplace()
                    } else {
                        doProcess(false)
                    }
                }
            }

            // 输入框文本变化：仅「实时」模式才替换（仅发送时模式不在此处理）
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (replaceMode != "onsend") {
                    doProcess(true)
                    textChangeCount++
                    if (textChangeCount % 50 == 1) {
                        addLog("收到文本变化事件(累计 $textChangeCount 次) pkg=$pkg")
                    }
                }
            }
        }
    }

    /**
     * 「仅发送时」模式：在按下发送键的一刻才替换。
     * 安卓无障碍的 CLICKED 事件是 onClick 之后派发的，原发送可能已发生，
     * 因此这里改写后会再点击一次发送按钮以发出替换后的内容（用于抵消竞态）。
     */
    private fun onSendReplace() {
        Log.d(TAG, "onSendReplace enter funcEnabled=$funcEnabled processing=$processing")
        if (!funcEnabled) {
            logThrottled("功能开关已关闭，跳过替换", 500)
            return
        }
        if (processing) return
        processing = true
        try {
            val root = rootInActiveWindow ?: return
            try {
                val inputNode = findInputField(root) ?: return
                try {
                    val raw = inputNode.text?.toString() ?: ""
                    if (raw.isEmpty()) {
                        logThrottled("发送时原文为空，跳过", 1000)
                        return
                    }
                    // 去重：若 2 秒内已对同一文本做过「发送前替换」，避免重复发送
                    val now = System.currentTimeMillis()
                    if (pendingResend != null && now - pendingResendTime < 2000 && raw == pendingResend) {
                        return
                    }
                    val original = restoreUserInput(raw)
                    Log.d(TAG, "onSendReplace raw='$raw' original='$original'")
                    if (original.isEmpty()) return
                    val replaced = applyRulesNoSuffix(original) + pickSuffix()
                    Log.d(TAG, "onSendReplace replaced='$replaced'")
                    if (replaced == raw) return
                    val setOk = setText(inputNode, replaced)
                    Log.d(TAG, "onSendReplace setText ok=$setOk")
                    if (setOk) {
                        pendingResend = replaced
                        pendingResendTime = now
                        addLog("发送前替换：\"$original\" -> \"$replaced\"")
                        // 改写后重新点击发送键，发出替换后的文本（抵消可能的原文发送）
                        mainHandler.postDelayed({
                            try {
                                val root2 = rootInActiveWindow ?: return@postDelayed
                                val send = findSendButton(root2)
                                Log.d(TAG, "onSendReplace resend click send=$send")
                                send?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                send?.recycle()
                                root2.recycle()
                            } catch (e: Exception) {
                                Log.d(TAG, "onSendReplace resend exception: ${e.message}")
                            }
                        }, 50)
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

    private var pendingResend: String? = null
    private var pendingResendTime = 0L

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = listOf(
            "com.tencent.mobileqq:id/sendBtn",
            "com.tencent.mobileqq:id/send_btn",
            "com.tencent.mobileqq:id/btn_send",
            "com.tencent.mobileqq:id/sendMessageBtn",
            "com.tencent.mobileqq:id/iv_send",
            "com.tencent.mobileqq:id/send",
            "com.tencent.qq:id/sendBtn"
        )
        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        try {
                            return AccessibilityNodeInfo.obtain(n)
                        } finally {
                            n.recycle()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "查找发送按钮 $id 出错: " + e.message)
            }
        }
        // 兜底：遍历找文字/描述为「发送」的可点击节点
        return findNodeByText(root, "发送")
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        for (i in 0 until root.childCount) {
            try {
                val child = root.getChild(i) ?: continue
                try {
                    val txt = (child.text?.toString() ?: "") + (child.contentDescription?.toString() ?: "")
                    if (child.isClickable && txt.contains(label)) {
                        return AccessibilityNodeInfo.obtain(child)
                    }
                    val found = findNodeByText(child, label)
                    if (found != null) return found
                } finally {
                    child.recycle()
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (replaceMode == "onsend" && funcEnabled && event != null &&
            event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
            event.action == android.view.KeyEvent.ACTION_UP
        ) {
            val root = rootInActiveWindow ?: return super.onKeyEvent(event)
            try {
                val inputNode = findInputField(root)
                    ?: run { root.recycle(); return super.onKeyEvent(event) }
                try {
                    val raw = inputNode.text?.toString() ?: ""
                    if (raw.isNotEmpty()) {
                        val original = restoreUserInput(raw)
                        val replaced = applyRulesNoSuffix(original) + pickSuffix()
                        if (replaced != raw) {
                            if (setText(inputNode, replaced)) {
                                addLog("键盘发送键拦截并替换：\"$original\" -> \"$replaced\"")
                                // 消费该按键，阻止 QQ 用原文发送；替换后的内容已写入，QQ 不会再发
                                return true
                            }
                        }
                    }
                } finally {
                    inputNode.recycle()
                }
                root.recycle()
            } catch (_: Throwable) {
                root.recycle()
            }
        }
        return super.onKeyEvent(event)
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
