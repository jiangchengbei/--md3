package io.legado.app.tts.rule

import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON
import io.legado.app.utils.putPrefString
import io.legado.app.utils.getPrefString
import splitties.init.appCtx

/**
 * 规则配置持久化管理。
 *
 * 将启用/禁用状态和规则参数存储到 SharedPreferences，
 * 支持导入/导出。
 */
object TtsRuleConfig {
    private const val PREF_KEY_RULES = "tts_rule_configs"

    /**
     * 保存规则配置列表。
     */
    fun save(configs: List<RuleConfigData>) {
        val json = GSON.toJson(configs)
        appCtx.putPrefString(PREF_KEY_RULES, json)
    }

    /**
     * 加载规则配置列表。
     */
    fun load(): List<RuleConfigData> {
        val json = appCtx.getPrefString(PREF_KEY_RULES)
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<RuleConfigData>>() {}.type
            GSON.fromJson<List<RuleConfigData>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
