package com.nhyhelper.zhuang

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 单条替换规则 */
data class Rule(val from: String, val to: String, var enabled: Boolean = true)

/** 一条句尾附加文字，可独立开关是否参与随机 */
data class Suffix(val text: String, var enabled: Boolean = true)

/** 一个规则分类，同一时刻只启用一个分类；后缀归入分类（多条，随机选一条） */
data class Category(
    var name: String,
    val rules: MutableList<Rule> = mutableListOf(),
    var active: Boolean = false,
    val suffixes: MutableList<Suffix> = mutableListOf()
)

/**
 * 规则/分类/后缀的统一存取与导入导出。
 * 后缀属于分类，同一条文本发送时从该分类的多条后缀中随机选一条。
 */
object ConfigStore {

    private const val PREFS_NAME = "qq_reply_rules"
    private const val KEY_CONFIG = "config_json"
    // 旧数据 key（用于迁移）
    private const val LEGACY_KEY_RULES = "rules_json"
    private const val LEGACY_KEY_SUFFIX = "suffix_text"

    const val DEFAULT_SUFFIX = "嘻嘻嘻嘻哈哈哈哈"

    data class Config(val categories: MutableList<Category>)

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG, null)
        // 兼容旧版：全局后缀单独存
        val legacySuffix = prefs.getString(LEGACY_KEY_SUFFIX, DEFAULT_SUFFIX) ?: DEFAULT_SUFFIX

        if (json == null) {
            // 迁移旧版扁平规则与全局后缀到"默认"分类
            val legacyRulesJson = prefs.getString(LEGACY_KEY_RULES, null)
            val cats = if (legacyRulesJson != null) {
                try {
                    val arr = JSONArray(legacyRulesJson)
                    val rules = mutableListOf<Rule>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        rules.add(Rule(o.getString("from"), o.getString("to"), o.getBoolean("enabled")))
                    }
                    if (rules.isEmpty()) defaultCategories(legacySuffix)
                    else mutableListOf(Category("默认", rules, true, mutableListOf(Suffix(legacySuffix, true))))
                } catch (e: Exception) {
                    defaultCategories(legacySuffix)
                }
            } else {
                defaultCategories(legacySuffix)
            }
            return Config(cats.ensureCatgirlPreset())
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
                    val sfxArr = cj.optJSONArray("suffixes")
                    val suffixes = mutableListOf<Suffix>()
                    if (sfxArr != null) {
                        for (j in 0 until sfxArr.length()) {
                            val item = sfxArr.opt(j)
                            when (item) {
                                is JSONObject -> {
                                    val s = item.optString("text")
                                    if (s.isNotBlank()) suffixes.add(Suffix(s, item.optBoolean("enabled", true)))
                                }
                                else -> {
                                    val s = sfxArr.optString(j)
                                    if (s.isNotBlank()) suffixes.add(Suffix(s, true))
                                }
                            }
                        }
                    }
                    cats.add(
                        Category(
                            cj.optString("name", "未命名"),
                            rules,
                            cj.optBoolean("active", false),
                            suffixes
                        )
                    )
                }
            }
            if (cats.isEmpty()) return Config(defaultCategories(legacySuffix))

            // 旧版全局后缀迁移：若活动分类没有后缀，则把旧全局后缀放进去
            val oldGlobal = obj.optString("suffix", "").ifBlank { legacySuffix }
            if (oldGlobal.isNotBlank()) {
                val active = cats.firstOrNull { it.active } ?: cats.first()
                if (active.suffixes.isEmpty()) active.suffixes.add(Suffix(oldGlobal, true))
            }
            Config(cats.ensureOnlyOneActive().ensureCatgirlPreset())
        } catch (e: Exception) {
            Config(defaultCategories(legacySuffix))
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
        val cats = JSONArray()
        for (c in cfg.categories) {
            val cj = JSONObject()
            cj.put("name", c.name)
            cj.put("active", c.active)
            val rArr = JSONArray()
            for (r in c.rules) {
                val rj = JSONObject()
                rj.put("from", r.from)
                rj.put("to", r.to)
                rj.put("enabled", r.enabled)
                rArr.put(rj)
            }
            cj.put("rules", rArr)
            val sArr = JSONArray()
            for (s in c.suffixes) {
                val sj = JSONObject()
                sj.put("text", s.text)
                sj.put("enabled", s.enabled)
                sArr.put(sj)
            }
            cj.put("suffixes", sArr)
            cats.put(cj)
        }
        obj.put("categories", cats)
        prefs.edit().putString(KEY_CONFIG, obj.toString()).apply()
    }

    fun defaultCategories(suffix: String = DEFAULT_SUFFIX): MutableList<Category> = mutableListOf(
        Category(
            "默认",
            mutableListOf(Rule("你", "缸"), Rule("我", "小薄饼群主酱")),
            active = true,
            suffixes = mutableListOf(Suffix(suffix, true))
        ),
        catgirlPreset()
    )

    /** 预设的猫娘分类（不默认启用，方便切换到猫娘口癖） */
    fun catgirlPreset(): Category = Category(
        "猫娘",
        mutableListOf(
            Rule("我", "本喵", true),
            Rule("你", "主人", true),
            Rule("啊", "喵", true),
            Rule("呢", "喵", true),
            Rule("吗", "喵", true),
            Rule("嗯", "喵", true),
            Rule("好的", "好喵", true),
            Rule("哈哈", "喵喵喵", true)
        ),
        active = false,
        suffixes = mutableListOf(
            Suffix("喵~", true),
            Suffix("喵喵", true),
            Suffix("喵呜~", true),
            Suffix("喵！", true),
            Suffix("喵喵喵~", true),
            Suffix("～喵", true),
            Suffix("(≧ω≦)", true),
            Suffix("(๑•̀ㅂ•́)و✧", true),
            Suffix("ฅ(♡ơ ₃ơ)ฅ", true),
            Suffix("嘻嘻", true),
            Suffix("喵呜♡", true)
        )
    )

    /** 确保存在"猫娘"预设分类（缺失时补一个，不激活），兼容已有老数据 */
    private fun MutableList<Category>.ensureCatgirlPreset(): MutableList<Category> {
        if (none { it.name == "猫娘" }) add(catgirlPreset())
        return this
    }

    /** 生成可导出/导入的 JSON 文本。若 categories 为空则导出默认分类。 */
    fun toExportJson(cfg: Config): String {
        val obj = JSONObject()
        val cats = JSONArray()
        val src = if (cfg.categories.isEmpty()) defaultCategories() else cfg.categories
        for (c in src) {
            val cj = JSONObject()
            cj.put("name", c.name)
            cj.put("active", c.active)
            val rArr = JSONArray()
            for (r in c.rules) {
                val rj = JSONObject()
                rj.put("from", r.from)
                rj.put("to", r.to)
                rj.put("enabled", r.enabled)
                rArr.put(rj)
            }
            cj.put("rules", rArr)
            val sArr = JSONArray()
            for (s in c.suffixes) {
                val sj = JSONObject()
                sj.put("text", s.text)
                sj.put("enabled", s.enabled)
                sArr.put(sj)
            }
            cj.put("suffixes", sArr)
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
                val suffixes = mutableListOf<Suffix>()
                val sArr = cj.optJSONArray("suffixes")
                if (sArr != null) {
                    for (j in 0 until sArr.length()) {
                        val item = sArr.opt(j)
                        when (item) {
                            is JSONObject -> {
                                val s = item.optString("text")
                                if (s.isNotBlank()) suffixes.add(Suffix(s, item.optBoolean("enabled", true)))
                            }
                            else -> {
                                val s = sArr.optString(j)
                                if (s.isNotBlank()) suffixes.add(Suffix(s, true))
                            }
                        }
                    }
                }
                cats.add(
                    Category(
                        cj.optString("name", "未命名"),
                        rules,
                        cj.optBoolean("active", false),
                        suffixes
                    )
                )
            }
            if (cats.isEmpty()) return null
            Config(cats.ensureOnlyOneActive())
        } catch (e: Exception) {
            null
        }
    }
}