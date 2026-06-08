package io.legado.app.tts.rule

import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ChineseUtils

/**
 * 内置规则集合。
 */
object BuiltinRules {

    object Ids {
        const val CLEAN_CONTROL_CHARS = "clean_control_chars"
        const val NORMALIZE_WHITESPACE = "normalize_whitespace"
        const val FILTER_SILENT = "filter_silent"
        const val CHINESE_CONVERT = "chinese_convert"
        const val REGEX_REPLACE = "regex_replace"
        const val DIALOGUE_ENHANCE = "dialogue_enhance"
    }

    fun cleanControlChars() = object : TtsProcessRule {
        override val id = Ids.CLEAN_CONTROL_CHARS
        override val name = "清理控制字符"
        override val description = "移除文本中的不可见控制字符"
        override val category = RuleCategory.CLEANUP
        override var enabled = true
        override var priority = 10
        override suspend fun process(text: String, context: TtsProcessContext): String =
            text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]"), "")
    }

    fun normalizeWhitespace() = object : TtsProcessRule {
        override val id = Ids.NORMALIZE_WHITESPACE
        override val name = "空白规范化"
        override val description = "合并多余空格，去除行首行尾空白"
        override val category = RuleCategory.CLEANUP
        override var enabled = true
        override var priority = 20
        override suspend fun process(text: String, context: TtsProcessContext): String =
            text.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun filterSilentContent() = object : TtsProcessRule {
        override val id = Ids.FILTER_SILENT
        override val name = "过滤静音内容"
        override val description = "移除纯标点、纯空白等无法朗读的内容"
        override val category = RuleCategory.FILTER
        override var enabled = true
        override var priority = 30
        override suspend fun process(text: String, context: TtsProcessContext): String {
            val t = text.trim()
            if (t.matches(AppPattern.notReadAloudRegex)) return ""
            val cleaned = t.replace(Regex("[\\s\\p{P}\\p{Z}\\p{S}]"), "")
            return if (cleaned.isEmpty()) "" else t
        }
    }

    fun chineseConvert() = object : TtsProcessRule {
        override val id = Ids.CHINESE_CONVERT
        override val name = "简繁转换"
        override val description = "将繁体中文转换为简体中文，提高 TTS 识别率"
        override val category = RuleCategory.TRANSFORM
        override var enabled = true
        override var priority = 50
        override suspend fun process(text: String, context: TtsProcessContext): String {
            val t = AppConfig.chineseConverterType
            return when {
                t == 1 -> ChineseUtils.t2s(text)
                t == 2 -> ChineseUtils.s2t(text)
                else -> text
            }
        }
    }

    fun regexReplace(patterns: List<Pair<String, String>>) = object : TtsProcessRule {
        override val id = Ids.REGEX_REPLACE
        override val name = "正则替换"
        override val description = "用户自定义的正则表达式替换规则"
        override val category = RuleCategory.CUSTOM
        override var enabled = true
        override var priority = 40
        private val _p = patterns.toMutableList()
        val savedPatterns: List<Pair<String, String>> get() = _p.toList()
        fun restore(config: RuleConfigData) {
            _p.clear()
            val count = config.params["count"]?.toIntOrNull() ?: 0
            for (i in 0 until count) {
                val p = config.params["pattern_$i"] ?: continue
                val r = config.params["replacement_$i"] ?: ""
                _p.add(p to r)
            }
        }
        override suspend fun process(text: String, context: TtsProcessContext): String {
            var r = text
            for ((p, rep) in _p) {
                if (p.isBlank()) continue
                kotlin.runCatching { r = r.replace(Regex(p), rep) }
            }
            return r
        }
        override fun toConfig(): RuleConfigData {
            val params = mutableMapOf<String, String>()
            _p.forEachIndexed { i, (k, v) -> params["pattern_$i"] = k; params["replacement_$i"] = v }
            params["count"] = _p.size.toString()
            return RuleConfigData(id, enabled = enabled, priority = priority, params = params)
        }
    }

    fun dialogueEnhance() = object : TtsProcessRule {
        override val id = Ids.DIALOGUE_ENHANCE
        override val name = "对话标记增强"
        override val description = "为引号内的对话内容添加标记，辅助多人朗读解析"
        override val category = RuleCategory.ENHANCE
        override var enabled = true
        override var priority = 60
        override suspend fun process(text: String, context: TtsProcessContext): String = text
    }

    fun allDefaults(): List<TtsProcessRule> = listOf(
        cleanControlChars(),
        normalizeWhitespace(),
        filterSilentContent(),
        chineseConvert(),
        regexReplace(emptyList()),
        dialogueEnhance()
    )
}
