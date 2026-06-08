package io.legado.app.tts.plugin

import android.content.Context
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import splitties.init.appCtx

/**
 * TTS 引擎插件管理器（单例）。
 * 负责注册、查找、切换 TTS 引擎插件。
 *
 * 内置引擎在工作时自动创建并注册（SystemTtsPlugin、HttpTtsPlugin），
 * 扩展引擎可通过 [registerPlugin] 注入。
 */
object TtsPluginManager {

    private val plugins = mutableMapOf<String, TtsEnginePlugin>()
    private var currentPlugin: TtsEnginePlugin? = null

    /**
     * 注册一个引擎插件。
     * 如果已存在相同 ID 的插件，将被覆盖。
     */
    fun registerPlugin(plugin: TtsEnginePlugin) {
        plugins[plugin.engineInfo.id] = plugin
    }

    /**
     * 根据引擎 ID 获取插件。
     */
    fun getPlugin(id: String): TtsEnginePlugin? = plugins[id]

    /**
     * 获取所有已注册插件。
     */
    fun getAllPlugins(): List<TtsEnginePlugin> = plugins.values.toList()

    /**
     * 获取所有可用引擎信息。
     */
    fun getAvailableEngines(): List<EngineInfo> = plugins.values
        .filter { it.engineInfo.available }
        .map { it.engineInfo }

    /**
     * 根据当前配置（AppConfig.ttsEngine）解析获取对应的引擎插件。
     * 如果引擎尚未注册，会自动创建并注册。
     *
     * @param context Android Context（用于引擎初始化）
     * @return 当前配置的引擎插件，如果失败则返回系统 TTS 插件
     */
    suspend fun getCurrentPlugin(context: Context): TtsEnginePlugin {
        val cached = currentPlugin
        if (cached != null && isPluginForCurrentConfig(cached)) {
            return cached
        }

        val engineId = resolveEngineId()
        val plugin = getOrCreatePlugin(engineId, context)
        currentPlugin = plugin
        return plugin
    }

    /**
     * 强制切换到指定引擎。
     */
    suspend fun switchTo(context: Context, engineId: String, voiceId: String? = null) {
        currentPlugin?.shutdown()
        currentPlugin = null
        AppConfig.ttsEngine = engineId
        val plugin = getOrCreatePlugin(engineId, context)
        currentPlugin = plugin
    }

    /**
     * 重置当前插件（下次获取时重新创建）。
     */
    fun reset() {
        currentPlugin?.shutdown()
        currentPlugin = null
    }

    // ====== private ======

    private fun resolveEngineId(): String {
        val raw = AppConfig.ttsEngine
        if (raw.isNullOrBlank()) return "system"
        val trimmed = raw.trim()
        return if (trimmed.all { it.isDigit() }) "http_$trimmed" else trimmed
    }

    private fun isPluginForCurrentConfig(plugin: TtsEnginePlugin): Boolean {
        val raw = AppConfig.ttsEngine
        val engineId = plugin.engineInfo.id
        return when {
            raw.isNullOrBlank() -> engineId == "system"
            raw.trim().all { it.isDigit() } -> engineId == "http_${raw.trim()}"
            else -> engineId == raw.trim()
        }
    }

    private suspend fun getOrCreatePlugin(engineId: String, context: Context): TtsEnginePlugin {
        plugins[engineId]?.let { return it }

        val plugin: TtsEnginePlugin = when {
            engineId == "system" -> SystemTtsPlugin()
            engineId.startsWith("http_") -> {
                val id = engineId.removePrefix("http_").toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid HTTP TTS engine ID: $engineId")
                val httpTts = appDb.httpTTSDao.get(id)
                    ?: throw IllegalArgumentException("HTTP TTS not found: $id")
                HttpTtsPlugin(httpTts)
            }
            else -> throw IllegalArgumentException("Unknown engine ID: $engineId")
        }

        plugin.initialize(context)
        registerPlugin(plugin)
        return plugin
    }
}
