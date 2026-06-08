package io.legado.app.tts.config

import io.legado.app.tts.plugin.TtsPluginManager
import io.legado.app.tts.rule.TtsRuleChain
import io.legado.app.tts.voice.MultiVoiceManager

/**
 * 全局 TTS 配置管理器。
 *
 * 统一管理插件、规则链、多人朗读的初始化与配置。
 * 应用启动时调用 [TtsGlobalConfig.init] 完成初始化。
 */
object TtsGlobalConfig {

    private var initialized = false

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            // 初始化规则链
            TtsRuleChain.init()

            // 加载多人朗读配置
            MultiVoiceManager.load()

            initialized = true
        }
    }

    /**
     * 规则链是否启用。
     */
    var ruleChainEnabled: Boolean
        get() = TtsRuleChain.getEnabledRules().isNotEmpty()
        set(value) {
            if (!value) {
                // 禁用所有规则
                TtsRuleChain.getAllRules().forEach { it.enabled = false }
                TtsRuleChain.saveConfig()
            }
        }

    /**
     * 多人朗读是否启用。
     */
    var multiVoiceEnabled: Boolean
        get() = MultiVoiceManager.enabled
        set(value) {
            MultiVoiceManager.enabled = value
        }
}
