package com.qqreply.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 单条替换规则 */
data class Rule(val from: String, val to: String, var enabled: Boolean = true)

/** 一个规则分类，同一时刻只启用一个分类 */
data class Category(
    var name: String,
    val rules: MutableList<Rule> = mutableListOf(),
    var active: Boolean = false
)

/**
 * 规则/分类/后缀的统一存取与导入导出。
 * MainActivity 与 QQAccessibilityService 共用，保证数据一致。
 */
object ConfigStore {

    private const val PREFS_NAME = "qq_reply_rules"
    private const val KEY_CONFIG = "config_json"
    // 旧数据 key（用于迁移）
    private const val LEGACY_KEY_RULES = "rules_json"
    private const val LEGACY_KEY_SUFFIX = "suffix_text"

    const val DEFAULT_SUFFIX = "嘻嘻嘻嘻哈哈哈哈"

    data class Config(
        var suffix: String,
        val categories: MutableList<Category>
    )

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG, null)
        // 兼容旧版：suffix 单独存
        val legacySuffix = prefs.getString(LEGACY_KEY_SUFFIX, DEFAULT_SUFFIX) ?: DEFAULT_SUFFIX

        if (json == null) {
            // 迁移旧版扁平规则到"默认"分类
            val legacyRulesJson = prefs.getString(LEGACY_KEY_RULES, null)
            val cats = if (legacyRulesJson != null) {
                try {
                    val arr = JSONArray(legacyRulesJson)
                    val rules = mutableListOf<Rule>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        rules.add(Rule(o.getString("from"), o.getString("to"), o.getBoolean("enabled")))
                    }
                    if (rules.isEmpty()) defaultCategories()
                    else mutableListOf(Category("默认", rules, true))
                } catch (e: Exception) {
                    defaultCategories()
                }
            } else {
                defaultCategories()
            }
            return Config(legacySuffix, cats)
        }

        return try {
            val obj = JSONObject(json)
            val cats = mutableListOf<Category>()
            val catsJson = obj.optJSONArray("categories")
            if (catsJson != null) {
                for (i in 0 until catsJson.length()) {
                    val cj = catsJson.getJSONObject(i)
                    val rulesArr = cj.optJSONArray("rules")
                    val rules = mutableListOf<Rule>()
                    if (rulesArr != null) {
                        for (j in 0 until rulesArr.length()) {
                            val rj = rulesArr.getJSONObject(j)
                            rules.add(
                                Rule(
                                    rj.optString("from"),
                                    rj.optString("to"),
                                    rj.optBoolean("enabled", true)
                                )
                            )
                        }
                    }
                    cats.add(
                        Category(
                            cj.optString("name", "未命名"),
                            rules,
                            cj.optBoolean("active", false)
                        )
                    )
                }
            }
            val suffix = obj.optString("suffix", legacySuffix)
            if (cats.isEmpty()) Config(suffix, defaultCategories())
            else Config(suffix, cats.ensureOnlyOneActive())
        } catch (e: Exception) {
            Config(legacySuffix, defaultCategories())
        }
    }

    private fun MutableList<Category>.ensureOnlyOneActive(): MutableList<Category> {
        if (none { it.active }) {
            if (isNotEmpty()) this[0].active = true
        }
        return this
    }

    fun save(context: Context, cfg: Config) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject()
        obj.put("suffix", cfg.suffix)

        val cats = JSONArray()
        for (c in cfg.categories) {
            val cj = JSONObject()
            cj.put("name", c.name)
            cj.put("active", c.active)
            val arr = JSONArray()
            for (r in c.rules) {
                val rj = JSONObject()
                rj.put("from", r.from)
                rj.put("to", r.to)
                rj.put("enabled", r.enabled)
                arr.put(rj)
            }
            cj.put("rules", arr)
            cats.put(cj)
        }
        obj.put("categories", cats)
        prefs.edit().putString(KEY_CONFIG, obj.toString()).apply()
    }

    fun defaultCategories(): MutableList<Category> = mutableListOf(
        Category("默认", mutableListOf(Rule("你", "缸"), Rule("我", "小薄饼群主酱")), active = true)
    )

    /**
     * 生成可导出/导入的 JSON 文本。若 categories 为空，则导出默认分类，保证导入端可解析。
     */
    fun toExportJson(cfg: Config): String {
        val obj = JSONObject()
        obj.put("suffix", cfg.suffix)
        val cats = JSONArray()
        val src = if (cfg.categories.isEmpty()) defaultCategories() else cfg.categories
        for (c in src) {
            val cj = JSONObject()
            cj.put("name", c.name)
            cj.put("active", c.active)
            val arr = JSONArray()
            for (r in c.rules) {
                val rj = JSONObject()
                rj.put("from", r.from)
                rj.put("to", r.to)
                rj.put("enabled", r.enabled)
                arr.put(rj)
            }
            cj.put("rules", arr)
            cats.put(cj)
        }
        obj.put("categories", cats)
        return obj.toString(2)
    }

    /** 解析导入文本，格式合法返回 Config（可空表示失败） */
    fun parseImport(text: String): Config? {
        return try {
            val obj = JSONObject(text)
            val cats = mutableListOf<Category>()
            val catsJson = obj.optJSONArray("categories") ?: return null
            for (i in 0 until catsJson.length()) {
                val cj = catsJson.getJSONObject(i)
                val rules = mutableListOf<Rule>()
                val rulesArr = cj.optJSONArray("rules")
                if (rulesArr != null) {
                    for (j in 0 until rulesArr.length()) {
                        val rj = rulesArr.getJSONObject(j)
                        rules.add(
                            Rule(
                                rj.optString("from"),
                                rj.optString("to"),
                                rj.optBoolean("enabled", true)
                            )
                        )
                    }
                }
                cats.add(
                    Category(
                        cj.optString("name", "未命名"),
                        rules,
                        cj.optBoolean("active", false)
                    )
                )
            }
            if (cats.isEmpty()) return null
            val suffix = obj.optString("suffix", DEFAULT_SUFFIX)
            Config(suffix, cats.ensureOnlyOneActive())
        } catch (e: Exception) {
            null
        }
    }
}