package io.legado.app.tts.plugin

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统 TTS 引擎插件，封装 Android [TextToSpeech]。
 *
 * 支持多音色切换（系统 TTS 引擎提供的多语言/多音色）。
 */
class SystemTtsPlugin : TtsEnginePlugin {

    override val engineInfo = EngineInfo(
        id = "system",
        name = "系统 TTS 引擎",
        description = "使用 Android 系统内置的文字转语音引擎",
        builtIn = true
    )

    private var tts: TextToSpeech? = null
    private var initComplete = CompletableDeferred<Boolean>()
    private val synthesisCompleters = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val targetFiles = ConcurrentHashMap<String, File>()

    override suspend fun initialize(context: Context): Boolean {
        return suspendCancellableCoroutine { continuation ->
            initComplete = CompletableDeferred()
            val engine = GSON.fromJsonObject<SelectItem<String>>(AppConfig.ttsEngine)
                .getOrNull()?.value

            tts = if (engine.isNullOrBlank()) {
                TextToSpeech(context) { status ->
                    handleInit(status, continuation)
                }
            } else {
                TextToSpeech(context, { status ->
                    handleInit(status, continuation)
                }, engine)
            }

            continuation.invokeOnCancellation {
                tts?.shutdown()
                tts = null
            }
        }
    }

    private fun handleInit(
        status: Int,
        continuation: kotlinx.coroutines.CancellableContinuation<Boolean>
    ) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { tt ->
                tt.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        utteranceId ?: return
                        val file = targetFiles.remove(utteranceId)
                        val completer = synthesisCompleters.remove(utteranceId)
                        if (completer != null && file != null && file.exists() && file.length() > 0) {
                            completer.complete(file)
                        } else {
                            completer?.complete(null)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onError(utteranceId, TextToSpeech.ERROR)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        targetFiles.remove(utteranceId)
                        synthesisCompleters.remove(utteranceId)?.complete(null)
                    }
                })
            }
            initComplete.complete(true)
            continuation.resume(true)
        } else {
            initComplete.complete(false)
            continuation.resume(false)
        }
    }

    override suspend fun getVoices(): List<TtsVoice> {
        initComplete.await()
        val tt = tts ?: return emptyList()
        val voices = mutableListOf<TtsVoice>()
        try {
            val currentVoice = tt.voice
            if (currentVoice != null) {
                voices.add(
                    TtsVoice(
                        id = currentVoice.name,
                        name = currentVoice.name,
                        locale = currentVoice.locale ?: Locale.getDefault(),
                        gender = mapGender(currentVoice),
                        engineId = engineInfo.id
                    )
                )
            }
            // 默认音色
            if (voices.isEmpty()) {
                val defaultLocale = tt.defaultLanguage ?: Locale.getDefault()
                voices.add(
                    TtsVoice(
                        id = "default",
                        name = "默认音色",
                        locale = defaultLocale,
                        engineId = engineInfo.id
                    )
                )
            }
        } catch (_: Exception) { }
        return voices
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        speed: Float,
        outputFile: File
    ): File? {
        initComplete.await()
        val tt = tts ?: return null

        // 设置语速
        tt.setSpeechRate(speed * 2f - 1f) // 映射到 TTS API 语速范围

        val utteranceId = "sys_${System.nanoTime()}"
        val completer = CompletableDeferred<File?>()
        synthesisCompleters[utteranceId] = completer
        targetFiles[utteranceId] = outputFile

        val params = Bundle().apply {
            putInt("stream", AudioManager.STREAM_MUSIC)
        }

        val result = tt.synthesizeToFile(text, params, outputFile, utteranceId)

        if (result != TextToSpeech.SUCCESS) {
            synthesisCompleters.remove(utteranceId)
            targetFiles.remove(utteranceId)
            return null
        }

        return try {
            withTimeout(60000) { completer.await() }
        } catch (_: Exception) {
            synthesisCompleters.remove(utteranceId)
            targetFiles.remove(utteranceId)
            null
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        synthesisCompleters.values.forEach { it.complete(null) }
        synthesisCompleters.clear()
        targetFiles.clear()
        tts?.shutdown()
        tts = null
    }

    private fun mapGender(voice: android.speech.tts.Voice): VoiceGender {
        val name = voice.name.lowercase()
        return when {
            "female" in name || "xiaoxiao" in name || "xiaoyi" in name -> VoiceGender.FEMALE
            "male" in name || "yunxi" in name || "yunyang" in name -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }
}
