package com.qqreply.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qqreply.app.databinding.ActivityMainBinding
import com.qqreply.app.databinding.DialogAddRuleBinding
import com.qqreply.app.databinding.ItemRuleBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RulesAdapter
    private lateinit var cfg: ConfigStore.Config

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
        // Android 12+ 启用系统动态取色；低于 12 沿用主题默认色
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cfg = ConfigStore.load(this)
        setupBottomNav()
        setupServiceSwitch()
        setupSuffixInput()
        setupCategoryControls()
        setupRecyclerView()
        setupFab()
        setupSettingsButtons()
        updateServiceStatus()
        showTab(R.id.nav_home)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

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
    }

    private fun setupServiceSwitch() {
        binding.tabMain.serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) openAccessibilitySettings()
            else binding.tabMain.serviceSwitch.isChecked = isEnabled()
        }
    }

    private fun updateServiceStatus() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val isEnabled = am.isEnabled && QQAccessibilityService.instance != null
        binding.tabMain.serviceSwitch.isChecked = isEnabled
        binding.tabMain.serviceStatus.text =
            if (isEnabled) "服务状态：已开启 ✓" else "服务状态：未开启 ✗"
    }

    private fun isEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isEnabled && QQAccessibilityService.instance != null
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
                                // 若删除的是活动分类，激活剩余第一个
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

    // ---------- 后缀 ----------

    private fun setupSuffixInput() {
        binding.tabRules.suffixInput.setText(cfg.suffix)
        binding.tabRules.suffixInput.doAfterTextChanged {
            cfg.suffix = it?.toString() ?: ConfigStore.DEFAULT_SUFFIX
            applyConfig(notifyList = false)
        }
    }

    // ---------- 规则列表 ----------

    private fun setupRecyclerView() {
        adapter = RulesAdapter()
        binding.tabRules.rulesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tabRules.rulesRecyclerView.adapter = adapter
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

    private fun refreshRulesUi() {
        rebuildCategoryChips()
        adapter.notifyDataSetChanged()
        val cat = activeCategory()
        binding.tabRules.currentCategoryName.text =
            if (cat != null) "当前：${cat.name}（${cat.rules.size} 条）" else ""
        binding.tabRules.emptyRulesHint.visibility =
            if (cat == null || cat.rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    inner class RulesAdapter : RecyclerView.Adapter<RulesAdapter.VH>() {

        inner class VH(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)

        private fun activeRules(): List<Rule> = activeCategory()?.rules ?: emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val list = activeRules()
            val rule = list.getOrNull(pos) ?: return
            holder.binding.ruleFrom.text = rule.from
            holder.binding.ruleTo.text = rule.to
            holder.binding.ruleSwitch.isChecked = rule.enabled

            holder.binding.ruleSwitch.setOnCheckedChangeListener { _, isChecked ->
                rule.enabled = isChecked
                applyConfig(notifyList = false)
            }

            holder.itemView.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@MainActivity)
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
        }

        override fun getItemCount() = activeRules().size
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
                .setMessage("确定将所有分类和规则恢复到默认状态吗？")
                .setPositiveButton("重置") { _, _ ->
                    cfg = ConfigStore.Config(ConfigStore.DEFAULT_SUFFIX, ConfigStore.defaultCategories())
                    applyConfig()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ---------- 通用：保存并让服务 / UI 更新 ----------

    private fun applyConfig(notifyList: Boolean = true) {
        ConfigStore.save(this, cfg)
        QQAccessibilityService.instance?.reload()
        if (notifyList) {
            refreshRulesUi()
        } else {
            adapter.notifyDataSetChanged()
            val cat = activeCategory()
            binding.tabRules.emptyRulesHint.visibility =
                if (cat == null || cat.rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}