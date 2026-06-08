package io.legado.app.help

/**
 * TTS 文本预处理增强工具。
 * 参考自 TTS_Server_Android 的 TextProcessor / StringUtils.splitSentences 实现。
 *
 * 增强 Legado 现有的段落拆分逻辑，提供：
 * 1. 智能句子分割（保留标点符号）
 * 2. 静音内容检测
 * 3. 文本长度限制
 */
object TTSReadProcessor {

    /**
     * 句子分割正则：按句号、问号、感叹号、分号等分割，保留分隔符
     */
    private val sentenceSplitRegex by lazy { Regex("([。？！?！!;；])") }

    /**
     * 静音模式匹配：全为空白/控制字符/标点/分隔符/符号
     */
    private val silentPattern by lazy { Regex("[\\s\\p{C}\\p{P}\\p{Z}\\p{S}]") }

    /**
     * 智能分割长句并保留分隔符。
     * 例如："第一句话。第二句话？第三句话！" →
     * ["第一句话。", "第二句话？", "第三句话！"]
     */
    fun splitSentences(text: String): List<String> {
        val parts = text.split(sentenceSplitRegex)
        val result = mutableListOf<String>()

        var i = 0
        while (i < parts.size) {
            val part = parts[i].trim()
            if (part.isEmpty()) {
                i++
                continue
            }
            // 如果下一个 parts 元素是标点符号，附加到当前句
            val nextIsPunctuation = i + 1 < parts.size &&
                    parts[i + 1].length == 1 &&
                    parts[i + 1].matches(sentenceSplitRegex)
            if (nextIsPunctuation) {
                result.add(part + parts[i + 1])
                i += 2
            } else {
                result.add(part)
                i++
            }
        }

        return result.filter { it.isNotBlank() }
    }

    /**
     * 判断文本是否为静音内容（不可朗读）。
     * 与 [io.legado.app.constant.AppPattern.notReadAloudRegex] 功能一致。
     */
    fun isSilent(text: String): Boolean {
        return text.isBlank() || silentPattern.replace(text, "").isEmpty()
    }

    /**
     * 限制字符串长度用于日志输出。
     * @param maxLength 最大长度，默认 80
     * @param suffix 超出时追加的后缀
     */
    fun limitLength(text: String, maxLength: Int = 80, suffix: String = "..."): String {
        return if (text.length >= maxLength) {
            text.substring(0, maxLength) + suffix
        } else {
            text
        }
    }

    /**
     * 预处理朗读文本：
     * 1. 移除不可见控制字符
     * 2. 规范化多余空白
     * 3. 按智能句子分割
     */
    fun preprocessReadAloudText(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        // 规范化文本：移除部分特殊字符、合并多余空白
        var text = rawText
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]"), "")

        // 按段落(\n)拆分，再按句子标点智能拆分
        val result = mutableListOf<String>()
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            val sentences = splitSentences(trimmed)
            if (sentences.isEmpty() || sentences.any { !isSilent(it) }) {
                result.addAll(sentences)
            }
        }

        return result
    }
}
