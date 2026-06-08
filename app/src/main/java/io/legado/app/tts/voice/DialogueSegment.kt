package io.legado.app.tts.voice

/**
 * 对话文本片段，由 [MultiVoiceManager] 解析产生。
 */
data class DialogueSegment(
    /** 片段文本 */
    val text: String,
    /** 是否为对话内容 */
    val isDialogue: Boolean,
    /** 匹配到的角色 ID（非对话时为 null） */
    val characterId: String?,
    /** 该段落中此片段在原文本中的起始位置 */
    val startIndex: Int,
    /** 片段长度 */
    val length: Int
)
