package io.legado.app.tts.rule

/**
 * 规则链管理器（单例）。
 * 按优先级顺序依次应用已启用的规则处理文本。
 */
object TtsRuleChain {

    private val rules = mutableListOf<TtsProcessRule>()
    private var initialized = false

    /** 存储用户自定义的正则替换规则 */
    private val customRegexRules = mutableListOf<Pair<String, String>>()

    @Synchronized
    fun init() {
        if (initialized) return
        val allBuiltins = BuiltinRules.allDefaults()
        val savedConfigs = TtsRuleConfig.load().associateBy { it.ruleId }

        for (rule in allBuiltins) {
            val config = savedConfigs[rule.id]
            if (config != null) {
                rule.enabled = config.enabled
                rule.priority = config.priority
                // 恢复正则替换规则
                if (rule.id == BuiltinRules.Ids.REGEX_REPLACE) {
                    val count = config.params["count"]?.toIntOrNull() ?: 0
                    customRegexRules.clear()
                    for (i in 0 until count) {
                        val p = config.params["pattern_$i"] ?: continue
                        val r = config.params["replacement_$i"] ?: ""
                        customRegexRules.add(p to r)
                    }
                }
            }
            rules.add(rule)
        }
        rules.sortBy { it.priority }
        initialized = true
    }

    fun addRule(rule: TtsProcessRule) {
        rules.add(rule)
        rules.sortBy { it.priority }
    }

    fun removeRule(ruleId: String) {
        rules.removeAll { it.id == ruleId }
    }

    fun getAllRules(): List<TtsProcessRule> = rules.toList()

    fun getEnabledRules(): List<TtsProcessRule> = rules.filter { it.enabled }.sortedBy { it.priority }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        rules.find { it.id == ruleId }?.let { it.enabled = enabled }
    }

    fun setRulePriority(ruleId: String, priority: Int) {
        rules.find { it.id == ruleId }?.let { it.priority = priority }
        rules.sortBy { it.priority }
    }

    /** 添加自定义正则替换规则 */
    fun addRegexRule(pattern: String, replacement: String) {
        customRegexRules.add(pattern to replacement)
    }

    /** 删除自定义正则替换规则 */
    fun removeRegexRule(index: Int) {
        if (index in customRegexRules.indices) {
            customRegexRules.removeAt(index)
        }
    }

    /** 获取所有自定义正则替换规则 */
    fun getRegexRules(): List<Pair<String, String>> = customRegexRules.toList()

    fun saveConfig() {
        // 更新正则规则的配置
        rules.find { it.id == BuiltinRules.Ids.REGEX_REPLACE }?.let { rule ->
            // 将自定义规则注入到配置中
        }
        TtsRuleConfig.save(rules.map { it.toConfig() }.map {
            if (it.ruleId == BuiltinRules.Ids.REGEX_REPLACE) {
                val params = mutableMapOf<String, String>()
                customRegexRules.forEachIndexed { i, (k, v) ->
                    params["pattern_$i"] = k
                    params["replacement_$i"] = v
                }
                params["count"] = customRegexRules.size.toString()
                it.copy(params = params)
            } else it
        })
    }

    suspend fun process(text: String, context: TtsProcessContext = TtsProcessContext()): String {
        var result = text

        // 先应用自定义正则替换
        for ((pattern, replacement) in customRegexRules) {
            if (pattern.isBlank()) continue
            kotlin.runCatching { result = result.replace(Regex(pattern), replacement) }
        }

        // 再应用其他已启用规则
        for (rule in getEnabledRules()) {
            if (rule.id == BuiltinRules.Ids.REGEX_REPLACE) continue // 正则已在上面处理
            if (result.isEmpty()) break
            result = rule.process(result, context)
        }
        return result
    }

    fun reset() {
        rules.clear()
        customRegexRules.clear()
        initialized = false
        init()
    }
}
