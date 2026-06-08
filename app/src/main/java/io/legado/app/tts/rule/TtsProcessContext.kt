package io.legado.app.tts.rule

import io.legado.app.data.entities.Book

/**
 * 文本处理规则的上下文信息。
 * 规则在处理文本时可以访问这些上下文来做决策。
 */
data class TtsProcessContext(
    /** 当前章节标题 */
    val chapterTitle: String = "",
    /** 当前书籍 */
    val book: Book? = null,
    /** 段落索引 */
    val paragraphIndex: Int = -1,
    /** 自定义键值对，供规则之间传递数据 */
    val extras: Map<String, Any> = emptyMap()
)
