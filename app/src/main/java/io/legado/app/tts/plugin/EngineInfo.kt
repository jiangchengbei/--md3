package io.legado.app.tts.plugin

/**
 * TTS 引擎元数据，用于在插件管理界面展示。
 */
data class EngineInfo(
    /** 引擎唯一标识，如 "system"、"http_12345"、"edge_tts" */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 描述 */
    val description: String = "",
    /** 版本号 */
    val version: String = "1.0",
    /** 是否可用 */
    val available: Boolean = true,
    /** 是否为内置引擎 */
    val builtIn: Boolean = true
)
