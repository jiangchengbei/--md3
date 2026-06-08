package io.legado.app.tts.plugin

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/**
 * HTTP TTS 引擎插件，将现有 [HttpTTS] 实体封装为标准插件接口。
 *
 * 通过 HTTP 请求远程 TTS 服务获取音频流，写入本地文件。
 *
 * 设计为桥接层：不改变现有 HttpTTS 的解析逻辑，
 * 仅将其包装为符合 [TtsEnginePlugin] 接口的调用方式。
 */
class HttpTtsPlugin(
    val httpTts: HttpTTS
) : TtsEnginePlugin {

    override val engineInfo: EngineInfo by lazy {
        EngineInfo(
            id = "http_${httpTts.id}",
            name = httpTts.name,
            description = "HTTP 在线朗读: ${httpTts.url}",
            builtIn = false
        )
    }

    private val cacheDir: File by lazy {
        File(appCtx?.externalCacheDir ?: appCtx?.cacheDir, "httpTTS").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    override suspend fun initialize(context: Context): Boolean {
        appCtx = context.applicationContext
        return true
    }

    override suspend fun getVoices(): List<TtsVoice> {
        // HTTP TTS 通常是单音色，所以这里只返回一个代表声音
        return listOf(
            TtsVoice(
                id = httpTts.id.toString(),
                name = httpTts.name,
                locale = Locale.SIMPLIFIED_CHINESE,
                gender = VoiceGender.UNKNOWN,
                engineId = engineInfo.id
            )
        )
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        speed: Float,
        outputFile: File
    ): File? {
        // 映射速度
        val speakSpeed = ((speed * 10f) - 5f).toInt()
        val requestTimeout = 300L * 1000L  // 5 minute timeout for HTTP TTS requests
        var lastException: Exception? = null
        val maxRetries = 3  // 3 retries max

        for (attempt in 0..maxRetries) {
            currentCoroutineContext().ensureActive()
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = text,
                    speakSpeed = speakSpeed,
                    source = httpTts,
                    readTimeout = requestTimeout,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()

                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as okhttp3.Response
                }

                response.headers["Content-Type"]?.let { contentType ->
                    val ct = httpTts.contentType
                    if (ct?.isNotBlank() == true) {
                        val bareType = contentType.substringBefore(";")
                        if (!bareType.matches(ct.toRegex())) {
                            throw IllegalStateException("TTS服务器返回非预期类型: $contentType")
                        }
                    }
                }

                response.body.byteStream().use { input ->
                    outputFile.outputStream().use { out ->
                        input.copyTo(out)
                    }
                }

                return if (outputFile.exists() && outputFile.length() > 0) outputFile else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                AppLog.put("HTTP TTS 合成失败(attempt=$attempt): ${e.localizedMessage}", e)
            }
        }

        return null
    }

    override fun stop() {
        // HTTP TTS 的请求由协程管理，此处做空实现
    }

    override fun shutdown() {
        // 无需释放资源
    }

    companion object {
        @Volatile
        private var appCtx: Context? = null
    }
}
