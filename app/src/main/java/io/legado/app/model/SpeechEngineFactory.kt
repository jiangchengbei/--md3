package io.legado.app.model

import io.legado.app.data.entities.HttpTTS
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.TTSReadAloudService

/**
 * TTS 朗读引擎工厂，统一管理引擎选择逻辑。
 * 参考自 TTS_Server_Android 的 SpeechServiceFactory，
 * 适配为 Legado 的 Android Service 模式。
 */
object SpeechEngineFactory {

    /**
     * 引擎选择结果
     */
    data class EngineResult(
        val serviceClass: Class<*>,
        val httpTTS: HttpTTS? = null
    )

    /**
     * 根据 ttsEngine 配置解析出对应的朗读服务类。
     *
     * - 空字符串或 null → 系统本地 TTS
     * - 纯数字字符串 → 通过 ID 查找 HttpTTS，存在则使用 HTTP TTS
     * - 其他 → 系统本地 TTS
     */
    fun resolve(ttsEngine: String?, httpTTSProvider: (Long) -> HttpTTS?): EngineResult {
        if (ttsEngine.isNullOrBlank()) {
            return EngineResult(TTSReadAloudService::class.java, null)
        }

        val engine = ttsEngine.trim()
        if (engine.all { it.isDigit() }) {
            val id = engine.toLongOrNull()
            if (id != null) {
                val httpTts = httpTTSProvider(id)
                if (httpTts != null) {
                    return EngineResult(HttpReadAloudService::class.java, httpTts)
                }
            }
        }

        return EngineResult(TTSReadAloudService::class.java, null)
    }
}
