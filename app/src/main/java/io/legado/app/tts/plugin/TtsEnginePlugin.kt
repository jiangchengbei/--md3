package io.legado.app.tts.plugin

import android.content.Context
import java.io.File

/**
 * TTS 引擎插件接口。
 *
 * 所有 TTS 引擎（系统 TTS、HTTP TTS、Edge TTS 等）都实现此接口，
 * 由 [TtsPluginManager] 统一管理。
 *
 * 用法：
 * 1. 引擎启动时注册到 [TtsPluginManager.registerPlugin]
 * 2. 朗读服务通过 [TtsPluginManager.getCurrentPlugin] 获取当前引擎
 * 3. 调用 [initialize] → [getVoices] → [synthesize] → [shutdown] 完成朗读
 */
interface TtsEnginePlugin {

    /** 引擎元数据 */
    val engineInfo: EngineInfo

    /**
     * 初始化引擎。在主线程调用。
     * @return 是否初始化成功
     */
    suspend fun initialize(context: Context): Boolean

    /**
     * 获取可用的声音列表。
     * 必须在 [initialize] 之后调用。
     */
    suspend fun getVoices(): List<TtsVoice>

    /**
     * 合成文本为音频文件。
     *
     * @param text 要合成的文本
     * @param voiceId 指定音色 ID，null 表示使用默认音色
     * @param speed 语速倍率，1.0 为正常
     * @param outputFile 输出文件路径
     * @return 合成后的音频文件（即 outputFile）
     */
    suspend fun synthesize(
        text: String,
        voiceId: String? = null,
        speed: Float = 1.0f,
        outputFile: File
    ): File?

    /**
     * 停止当前合成。
     */
    fun stop()

    /**
     * 释放引擎资源。
     */
    fun shutdown()
}
