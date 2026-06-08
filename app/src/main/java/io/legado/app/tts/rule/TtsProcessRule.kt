package io.legado.app.tts.rule

/**
 * TTS 文本预处理规则接口。
 *
 * 灵感基于 TTS_Server_Android 的文本处理器，
 * 采用责任链模式，每条规则对输入文本执行一次变换。
 */
interface TtsProcessRule {

    /** 规则唯一标识 */
    val id: String
    /** 显示名称 */
    val name: String
    /** 一句话描述 */
    val description: String
    /** 规则分类 */
    val category: RuleCategory

    /**
     * 是否启用此规则。
     * 默认启用，可在规则链中禁用。
     */
    var enabled: Boolean

    /**
     * 规则排序优先级（数字越小越先执行）。
     */
    var priority: Int

    /**
     * 对输入文本执行处理。
     *
     * @param text 原始文本
     * @param context 处理上下文（章节信息等）
     * @return 处理后的文本
     */
    suspend fun process(text: String, context: TtsProcessContext): String

    /**
     * 规则配置数据（用于序列化/持久化）。
     * 实现类需覆盖此方法以提供自定义配置的序列化形式。
     */
    fun toConfig(): RuleConfigData = RuleConfigData(id)
}

enum class RuleCategory(val displayName: String) {
    CLEANUP("文本清理"),
    FILTER("内容过滤"),
    TRANSFORM("文本转换"),
    ENHANCE("朗读增强"),
    CUSTOM("自定义")
}

/**
 * 规则配置的可序列化数据容器。
 */
data class RuleConfigData(
    val ruleId: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val params: Map<String, String> = emptyMap()
)
