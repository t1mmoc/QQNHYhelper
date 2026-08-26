package com.nhyhelper.zhuang

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nhyhelper.zhuang.databinding.ActivityMainBinding
import com.nhyhelper.zhuang.databinding.DialogAddRuleBinding
import com.nhyhelper.zhuang.databinding.ItemRuleBinding
import com.nhyhelper.zhuang.databinding.ItemSuffixBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cfg: ConfigStore.Config

    // 列表默认显示条数
    private val maxVisible = 3
    private var suffixesExpanded = false
    private var rulesExpanded = false

    // 导出：写文本文件
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(ConfigStore.toExportJson(cfg).toByteArray())
                }
                toast("已导出规则")
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
        }
    }

    // 导入：读文本文件
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                val imported = if (text != null) ConfigStore.parseImport(text) else null
                if (imported == null) {
                    toast("导入失败：文件格式无效")
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("导入规则")
                        .setMessage("将用导入内容覆盖当前所有规则（共 ${imported.categories.size} 个分类），确定吗？")
                        .setPositiveButton("导入") { _, _ ->
                            cfg = imported
                            applyConfig()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                toast("导入失败：" + e.message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用保存的主题（明暗 + 配色）
        val themeCfg = ThemeStore.load(this)
        AppCompatDelegate.setDefaultNightMode(ThemeStore.appcompatNightMode(themeCfg.themeMode))
        setTheme(ThemeStore.themeStyleRes(themeCfg.palette))
        super.onCreate(savedInstanceState)
        if (themeCfg.palette == Palette.SYSTEM) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        lastThemeVersion = themeCfg.version

        cfg = ConfigStore.load(this)
        setupBottomNav()
        setupServiceCard()
        setupFuncSwitch()
        setupSuffixControls()
        setupCategoryControls()
        setupListToggles()
        setupFab()
        setupSettingsButtons()
        setupDebugLog()
        setupInfoButton()
        setupVersionBadge()
        updateServiceStatus()
        updateThemeStatus()
        showTab(R.id.nav_home)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        // 主题可能在 ThemeActivity 中被修改，返回时若版本变化则重建本页面
        val v = ThemeStore.load(this).version
        if (lastThemeVersion != -1 && v != lastThemeVersion) {
            lastThemeVersion = v
            recreate()
            return
        }
        lastThemeVersion = v
        updateThemeStatus()
    }

    private fun updateThemeStatus() {
        val t = ThemeStore.load(this)
        binding.tabSettings.themeStatus.text =
            "${ThemeStore.modeLabel(t.themeMode)} / ${ThemeStore.paletteLabel(t.palette)}"
    }

    private var lastThemeVersion = -1

    override fun onDestroy() {
        super.onDestroy()
        QQAccessibilityService.logListener = null
        super.onDestroy()
    }

    // ---------- 全面屏 / 系统栏适配 ----------

    private fun setupEdgeToEdge() {
        // 让内容延伸到系统栏后方（状态栏 + 底部手势条），再由各栏自身避让
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // 跟随手势滑动显示/隐藏系统栏
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun applySystemBarInsets() {
        // 底部手势条区域：底部导航避让，背景延伸到其后（顶栏由 AppBarLayout 自动处理）
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        // 状态栏/导航栏图标明暗：暗色模式下用浅色图标
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    // ---------- 页签 ----------

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
    }

    private fun showTab(menuId: Int) {
        val main = menuId == R.id.nav_home
        val rules = menuId == R.id.nav_rules
        val settings = menuId == R.id.nav_settings
        binding.tabMain.root.visibility = if (main) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabRules.root.visibility = if (rules) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabSettings.root.visibility = if (settings) android.view.View.VISIBLE else android.view.View.GONE
        if (rules) refreshRulesUi()
        if (settings) refreshDebugLog()
    }

    // ---------- 功能介绍弹窗 ----------

    private fun setupInfoButton() {
        binding.tabMain.infoButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("关于本软件")
                .setMessage(
                    "【功能介绍】\n" +
                        "· 在 QQ 中实时改写输入框文本\n" +
                        "· 支持自定义替换规则与规则分类\n" +
                        "· 句末随机附加文字，可自由编辑\n" +
                        "· 内置猫娘等预设口癖，一键切换\n\n" +
                        "【使用前提】\n" +
                        "使用前需先在系统「无障碍」中开启本服务的开关。\n\n" +
                        "【额外声明】\n" +
                        "在 QQ 里直接安装本软件会导致无障碍功能无法启动。请卸载后在其他应用（如文件管理）中重新安装。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    // ---------- 服务状态 / 功能开关 ----------

    private fun setupServiceCard() {
        binding.tabMain.serviceCard.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private val funcKey = "func_enabled"

    private fun setupFuncSwitch() {
        binding.tabMain.funcSwitch.isChecked = isFuncEnabled()
        updateFuncStatus()
        binding.tabMain.funcSwitch.setOnCheckedChangeListener { _, checked ->
            darkPrefs().edit().putBoolean(funcKey, checked).apply()
            QQAccessibilityService.instance?.setFuncEnabled(checked)
            updateFuncStatus()
        }
    }

    private fun isFuncEnabled(): Boolean =
        darkPrefs().getBoolean(funcKey, true)

    private fun updateFuncStatus() {
        val on = isFuncEnabled()
        binding.tabMain.funcSwitch.isChecked = on
        binding.tabMain.funcStatus.text = if (on) "替换功能已开启" else "替换功能已关闭"
    }

    private fun updateServiceStatus() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isEnabled = am.isEnabled && QQAccessibilityService.instance != null
        binding.tabMain.serviceStatus.text = if (isEnabled) "已开启" else "未开启"
        updateFuncStatus()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- 分类 ----------

    private fun setupCategoryControls() {
        binding.tabRules.addCategoryButton.setOnClickListener {
            showNewCategoryDialog()
        }
    }

    private fun rebuildCategoryChips() {
        val group = binding.tabRules.categoryChips
        group.removeAllViews()
        for ((i, cat) in cfg.categories.withIndex()) {
            val chip = Chip(this).apply {
                text = cat.name
                isCheckable = true
                isChecked = cat.active
                setOnClickListener { activateCategory(i) }
                setOnLongClickListener {
                    showCategoryActions(i)
                    true
                }
            }
            group.addView(chip)
        }
    }

    private fun activateCategory(index: Int) {
        if (index < 0 || index >= cfg.categories.size) return
        cfg.categories.forEachIndexed { i, c -> c.active = i == index }
        applyConfig()
    }

    private fun showNewCategoryDialog() {
        val input = EditText(this).apply {
            hint = "分类名称"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("新建分类")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("分类名称不能为空")
                } else {
                    cfg.categories.add(Category(name))
                    applyConfig()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCategoryActions(index: Int) {
        val cat = cfg.categories.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("分类：${cat.name}")
            .setItems(arrayOf("重命名", "删除")) { _, which ->
                when (which) {
                    0 -> showRenameCategoryDialog(index)
                    1 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("删除分类")
                            .setMessage("确定删除分类「${cat.name}」及其所有规则吗？")
                            .setPositiveButton("删除") { _, _ ->
                                cfg.categories.removeAt(index)
                                if (cfg.categories.isNotEmpty() && cfg.categories.none { it.active }) {
                                    cfg.categories[0].active = true
                                }
                                applyConfig()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showRenameCategoryDialog(index: Int) {
        val cat = cfg.categories.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(cat.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名分类")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    cat.name = name
                    applyConfig()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun activeCategory(): Category? =
        cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()

    // ---------- 列表（默认最多显示 3 条，超出可展开） ----------

    private fun setupListToggles() {
        binding.tabRules.suffixToggle.setOnClickListener {
            suffixesExpanded = !suffixesExpanded
            refreshRulesUi()
        }
        binding.tabRules.rulesToggle.setOnClickListener {
            rulesExpanded = !rulesExpanded
            refreshRulesUi()
        }
    }

    private fun renderSuffixList() {
        val container = binding.tabRules.suffixListContainer
        container.removeAllViews()
        val suffixes = activeCategory()?.suffixes ?: emptyList()
        val toShow = if (suffixesExpanded) suffixes else suffixes.take(maxVisible)

        for (suffix in toShow) {
            val b = ItemSuffixBinding.inflate(LayoutInflater.from(this), container, false)
            b.sfxText.text = suffix.text
            b.sfxSwitch.isChecked = suffix.enabled
            b.sfxSwitch.setOnCheckedChangeListener { _, checked ->
                suffix.enabled = checked
                applyConfig()
            }
            b.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除附加文字")
                    .setMessage("确定删除「${suffix.text}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        activeCategory()?.suffixes?.remove(suffix)
                        applyConfig()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            container.addView(b.root)
        }

        val toggle = binding.tabRules.suffixToggle
        if (suffixes.size > maxVisible) {
            toggle.text = if (suffixesExpanded) "收起附加文字" else "显示全部附加文字（${suffixes.size}）"
            toggle.visibility = android.view.View.VISIBLE
        } else {
            toggle.visibility = android.view.View.GONE
        }
    }

    private fun renderRulesList() {
        val container = binding.tabRules.rulesListContainer
        container.removeAllViews()
        val rules = activeCategory()?.rules ?: emptyList()
        val toShow = if (rulesExpanded) rules else rules.take(maxVisible)

        for (rule in toShow) {
            val b = ItemRuleBinding.inflate(LayoutInflater.from(this), container, false)
            b.ruleFrom.text = rule.from
            b.ruleTo.text = rule.to
            b.ruleSwitch.isChecked = rule.enabled
            b.ruleSwitch.setOnCheckedChangeListener { _, checked ->
                rule.enabled = checked
                applyConfig()
            }
            b.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除规则")
                    .setMessage("确定删除「${rule.from} → ${rule.to}」？")
                    .setPositiveButton("删除") { _, _ ->
                        activeCategory()?.rules?.remove(rule)
                        applyConfig()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            container.addView(b.root)
        }

        val toggle = binding.tabRules.rulesToggle
        if (rules.size > maxVisible) {
            toggle.text = if (rulesExpanded) "收起规则" else "显示全部规则（${rules.size}）"
            toggle.visibility = android.view.View.VISIBLE
        } else {
            toggle.visibility = android.view.View.GONE
        }
    }

    private fun refreshRulesUi() {
        rebuildCategoryChips()
        renderSuffixList()
        renderRulesList()
        val cat = activeCategory()
        binding.tabRules.currentCategoryName.text =
            if (cat != null) "当前：${cat.name}（${cat.rules.size} 条）" else ""
        binding.tabRules.emptyRulesHint.visibility =
            if (cat == null || cat.rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------- 后缀输入 ----------

    private fun setupSuffixControls() {
        binding.tabRules.addSuffixButton.setOnClickListener {
            val cat = activeCategory()
            val text = binding.tabRules.newSuffixInput.text?.toString()?.trim()
            if (cat == null) {
                toast("请先创建一个分类")
            } else if (text.isNullOrEmpty()) {
                toast("附加文字不能为空")
            } else {
                cat.suffixes.add(Suffix(text, true))
                binding.tabRules.newSuffixInput.setText("")
                applyConfig()
            }
        }
    }

    private fun setupFab() {
        binding.tabRules.addRuleFab.setOnClickListener {
            val cat = activeCategory()
            if (cat == null) {
                toast("请先创建一个分类")
            } else {
                showAddRuleDialog()
            }
        }
    }

    private fun showAddRuleDialog() {
        val dialogBinding = DialogAddRuleBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle("添加规则")
            .setView(dialogBinding.root)
            .setPositiveButton("添加") { _, _ ->
                val from = dialogBinding.fromInput.text.toString()
                val to = dialogBinding.toInput.text.toString()
                if (from.isEmpty() || to.isEmpty()) {
                    toast("原文本与替换为不能为空")
                } else {
                    activeCategory()?.rules?.add(Rule(from, to, true))
                    applyConfig()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 设置 ----------

    private fun setupSettingsButtons() {
        binding.tabSettings.exportRuleButton.setOnClickListener {
            exportLauncher.launch("qqnhy_rules.json")
        }
        binding.tabSettings.importRuleButton.setOnClickListener {
            importLauncher.launch(arrayOf("text/plain", "application/json", "*/*"))
        }
        binding.tabSettings.resetRulesButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("重置规则")
                .setMessage("确定将所有分类、规则与附加文字恢复到默认状态吗？")
                .setPositiveButton("重置") { _, _ ->
                    cfg = ConfigStore.Config(ConfigStore.defaultCategories())
                    applyConfig()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.tabSettings.projectLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sehhan667/QQNHYhelper")))
            } catch (e: Exception) {
                toast("无法打开链接")
            }
        }
        binding.tabSettings.licenseText.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("MIT 许可证")
                .setMessage(
                    "MIT License\n\n" +
                        "Copyright (c) 2026 qaqdym, AnotherCream\n\n" +
                        "Permission is hereby granted, free of charge, to any person obtaining a copy " +
                        "of this software and associated documentation files (the \"Software\"), to deal " +
                        "in the Software without restriction, including without limitation the rights " +
                        "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
                        "copies of the Software, and to permit persons to whom the Software is " +
                        "furnished to do so, subject to the following conditions:\n\n" +
                        "The above copyright notice and this permission notice shall be included in " +
                        "all copies or substantial portions of the Software.\n\n" +
                        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
                        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
                        "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
                        "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
                        "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
                        "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
                        "SOFTWARE."
                )
                .setPositiveButton("关闭", null)
                .show()
        }
        binding.tabSettings.themeCard.setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
    }

    // ---------- 调试日志 ----------

    private fun setupDebugLog() {
        binding.tabSettings.refreshLogButton.setOnClickListener { refreshDebugLog() }
        binding.tabSettings.clearLogButton.setOnClickListener {
            QQAccessibilityService.clearLogs()
            refreshDebugLog()
        }
        QQAccessibilityService.logListener = { runOnUiThread { refreshDebugLog() } }
        refreshDebugLog()
    }

    private fun refreshDebugLog() {
        val logs = QQAccessibilityService.getLogs()
        binding.tabSettings.debugLogText.text =
            if (logs.isEmpty()) "（暂无日志）" else logs.joinToString("\n")
    }

    private fun darkPrefs() = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)

    private fun setupVersionBadge() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.tabMain.versionBadge.text = "v${info.versionName}"
            binding.tabSettings.aboutAppVersion.text = "v ${info.versionName}"
        } catch (e: Exception) {
            binding.tabMain.versionBadge.text = "v1.3"
            binding.tabSettings.aboutAppVersion.text = "v 1.3"
        }
    }

    // ---------- 通用：保存并让服务 / UI 更新 ----------

    private fun applyConfig() {
        ConfigStore.save(this, cfg)
        QQAccessibilityService.instance?.reload()
        refreshRulesUi()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}