package io.legado.app.tts.plugin

import android.content.Context
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JS 脚本 TTS 引擎插件。
 *
 * 支持加载完整的 JS TTS 引擎（如 猫剪豆问），
 * 通过 Rhino 引擎执行 JS 代码，使用 WebSocket/HTTP 获取音频。
 */
class JsTtsPlugin(
    val httpTts: HttpTTS
) : TtsEnginePlugin {

    override val engineInfo: EngineInfo by lazy {
        EngineInfo(
            id = "js_${httpTts.id}",
            name = httpTts.name,
            description = "JS 脚本引擎: ${httpTts.name}",
            builtIn = false
        )
    }

    private var jsScope: Scriptable? = null
    private var currentWebSocket: JsWebSocket? = null

    override suspend fun initialize(context: Context): Boolean {
        try {
            // 通过 SharedJsScope 加载 JS 代码，确保 PluginJS/EditorJS 等全局对象可用
            val jsLib = httpTts.jsLib
            if (jsLib.isNullOrBlank()) {
                AppLog.put("JsTtsPlugin: jsLib is empty")
                return false
            }
            jsScope = SharedJsScope.getScope(jsLib, null)
            return jsScope != null
        } catch (e: Exception) {
            AppLog.put("JsTtsPlugin init error: ${e.localizedMessage}", e)
            return false
        }
    }

    override suspend fun getVoices(): List<TtsVoice> {
        val scope = jsScope ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val bindings = createBindings()
                bindings.prototype = scope
                val result = RhinoScriptEngine.eval(
                    "EditorJS.getVoices('all');",
                    bindings
                )
                parseVoicesFromResult(result)
            }
        } catch (e: Exception) {
            AppLog.put("JsTtsPlugin getVoices error: ${e.localizedMessage}", e)
            emptyList()
        }
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        speed: Float,
        outputFile: File
    ): File? {
        val scope = jsScope ?: return null

        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                try {
                    val bindings = createBindings()
                    bindings.prototype = scope

                    // 注册 Websocket 构造器
                    registerWebSocketClass(bindings)

                    // 创建音频收集回调
                    val audioCallback = object {
                        val buffer = ByteArrayOutputStream()
                        var error: String? = null

                        @org.mozilla.javascript.annotations.JSFunction
                        fun onAudioChunk(data: Any?) {
                            when (data) {
                                is ByteArray -> buffer.write(data)
                                else -> AppLog.put("JsTtsPlugin: unexpected audio chunk type: ${data?.javaClass?.name}")
                            }
                        }

                        @org.mozilla.javascript.annotations.JSFunction
                        fun onComplete() {
                            if (!cont.isActive) return
                            try {
                                val audioData = buffer.toByteArray()
                                if (audioData.isNotEmpty()) {
                                    outputFile.outputStream().use { it.write(audioData) }
                                }
                            } catch (e: Exception) {
                                // ignore file write error
                            }
                            cont.resume(if (outputFile.exists() && outputFile.length() > 0) outputFile else null)
                            cleanup()
                        }

                        @org.mozilla.javascript.annotations.JSFunction
                        fun onError(msg: Any?) {
                            error = msg?.toString() ?: "unknown error"
                            if (cont.isActive) {
                                cont.resumeWithException(RuntimeException("JS TTS error: $error"))
                            }
                            cleanup()
                        }

                        @org.mozilla.javascript.annotations.JSFunction
                        fun onClose() {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                            cleanup()
                        }
                    }

                    bindings.put("_audioCallback", bindings, audioCallback)

                    // 设置 ttsrv 桥接对象
                    val ttsrv = JsTtsBridge(httpTts.getKey())
                    bindings.put("ttsrv", bindings, ttsrv)

                    // 构造请求并调用 PluginJS.getAudioV2
                    val rate = (speed * 50f).toInt().coerceIn(1, 100)
                    val voice = voiceId ?: ""
                    val requestJson = """{"text":${GSON.toJson(text)},"rate":$rate,"pitch":50,"volume":50,"voice":${GSON.toJson(voice)}}"""

                    val jsCode = """
                        (function() {
                            var req = $requestJson;
                            var cb = _audioCallback;
                            if (typeof PluginJS !== 'undefined' && typeof PluginJS.getAudioV2 === 'function') {
                                PluginJS.getAudioV2(req, cb);
                            } else if (typeof _audioCallback !== 'undefined') {
                                cb.error('PluginJS.getAudioV2 not found');
                            }
                        })();
                    """.trimIndent()

                    RhinoScriptEngine.eval(jsCode, bindings)

                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }
            }
        }
    }

    override fun stop() {
        try {
            currentWebSocket?.close(1000, "user stop")
        } catch (_: Exception) {}
        currentWebSocket = null
    }

    override fun shutdown() {
        stop()
        jsScope = null
    }

    private fun createBindings(): Scriptable {
        return RhinoScriptEngine.getRuntimeScope(ScriptBindings().apply {
            put("cache", CacheManager)
            put("cookie", CookieStore)
        })
    }

    private fun registerWebSocketClass(scope: Scriptable) {
        // 注册 JsWebSocket 供 JS 的 new Websocket(url, headers) 使用
        try {
            val websocketDef = """function Websocket(url, headers) {
                if (!this.__ws) {
                    this.__ws = Packages.io.legado.app.tts.plugin.JsWebSocket(url, headers || {});
                }
                this.on = function(event, handler) {
                    this.__ws.on(event, handler);
                };
                this.send = function(data) {
                    this.__ws.send(data);
                };
                this.close = function() {
                    this.__ws.close();
                };
                this.cancel = function() {
                    this.__ws.close();
                };
            }""".trimIndent()
            RhinoScriptEngine.eval(websocketDef, scope)
        } catch (e: Exception) {
            AppLog.put("JsTtsPlugin: failed to register Websocket class: ${e.localizedMessage}", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVoicesFromResult(result: Any?): List<TtsVoice> {
        val voices = mutableListOf<TtsVoice>()
        try {
            val json = if (result is String) result else GSON.toJson(result)
            val map = GSON.fromJson(json, Map::class.java) as? Map<*, *> ?: return voices
            for (entry in map.entries) {
                val localeKey = entry.key?.toString() ?: continue
                @Suppress("UNCHECKED_CAST")
                val voiceList = entry.value as? List<Map<*, *>> ?: continue
                val locale = try {
                    val parts = localeKey.split("-", "_")
                    if (parts.size >= 2) java.util.Locale(parts[0], parts[1]) else java.util.Locale(localeKey)
                } catch (_: Exception) {
                    java.util.Locale.SIMPLIFIED_CHINESE
                }
                for (v in voiceList) {
                    val vid = (v["id"] ?: v["voice_id"] ?: v["voiceId"])?.toString() ?: continue
                    val vname = (v["name"] ?: v["voice_name"] ?: v["displayName"])?.toString() ?: "未知音色"
                    voices.add(
                        TtsVoice(
                            id = vid,
                            name = vname,
                            locale = locale,
                            gender = VoiceGender.UNKNOWN,
                            engineId = engineInfo.id
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return voices
    }

    private fun cleanup() {
        try { currentWebSocket?.close(1000, "done") } catch (_: Exception) {}
        currentWebSocket = null
    }
}

/**
 * JS 脚本所需的 ttsrv 桥接对象。
 * 提供 httpGet, httpGetString, base64DecodeToBytes, readTxtFile, writeTxtFile,
 * fileExist, strToBytes, file 等插件依赖的方法。
 */
class JsTtsBridge(private val key: String) {
    private val cacheFolder by lazy {
        File(appCtx.cacheDir, "shareJs").also { it.mkdirs() }
    }

    val tts: JsTtsData by lazy { JsTtsData() }

    inner class JsTtsData {
        val data = mutableMapOf<String, String>()
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun httpGet(urlPath: String, headers: Map<String, String>?): okhttp3.Response? {
        return try {
            runBlocking {
                okHttpClient.newCallResponse {
                    url(urlPath)
                    headers?.forEach { (k, v) -> header(k, v) }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun httpGetString(urlPath: String, headers: Map<String, String>?): String? {
        return try {
            runBlocking {
                okHttpClient.newCallStrResponse {
                    url(urlPath)
                    headers?.forEach { (k, v) -> header(k, v) }
                }.body
            }
        } catch (e: Exception) {
            null
        }
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun base64DecodeToBytes(base64: String?): ByteArray? {
        return try {
            if (base64 == null) null else android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun readTxtFile(fileName: String): String? {
        return try {
            val file = File(cacheFolder, fileName)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun writeTxtFile(fileName: String, content: String): Boolean {
        return try {
            val file = File(cacheFolder, fileName)
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun fileExist(fileName: String): Boolean {
        return try {
            File(cacheFolder, fileName).exists()
        } catch (e: Exception) {
            false
        }
    }

    @org.mozilla.javascript.annotations.JSFunction
    fun strToBytes(str: String?): ByteArray? {
        return str?.toByteArray(Charsets.UTF_8)
    }
}

/**
 * WebSocket 客户端，供 JS 插件通过 `new Websocket(url, headers)` 使用。
 *
 * 使用 shared Rhino ContextFactory 确保跨线程的 JS 函数调用安全。
 * 内部使用同步阻塞方式处理 WebSocket 事件。
 */
class JsWebSocket(private val url: String, private val headers: Map<String, String>?) {

    private var ws: WebSocket? = null
    private val handlerMap = mutableMapOf<String, Any>()
    @Volatile private var isOpen = false
    @Volatile private var errorOccurred: String? = null
    private val completeLatch = CountDownLatch(1)

    // 音频数据缓冲
    val audioChunks = mutableListOf<ByteArray>()
    @Volatile var audioLength: Long = 0

    companion object {
        @JvmStatic
        val contextFactory: org.mozilla.javascript.ContextFactory by lazy {
            org.mozilla.javascript.ContextFactory()
        }
    }

    init {
        val request = okhttp3.Request.Builder().url(url).apply {
            headers?.forEach { (k, v) -> this.header(k, v) }
        }.build()

        ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                invokeJsHandler("open", emptyArray<Any>())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 文本消息由插件通过 JSON 解析处理
                invokeJsHandler("text", arrayOf(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                synchronized(audioChunks) {
                    audioChunks.add(data)
                    audioLength += data.size
                }
                invokeJsHandler("binary", arrayOf(data))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                invokeJsHandler("close", arrayOf(code, reason))
                completeLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                invokeJsHandler("close", arrayOf(code, reason))
                completeLatch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                errorOccurred = t.message ?: "WebSocket error"
                invokeJsHandler("error", arrayOf(errorOccurred!!))
                completeLatch.countDown()
            }
        })
    }

    /**
     * 在受保护的 Rhino Context 中调用 JS 事件处理器。
     * 确保跨线程安全。
     */
    private fun invokeJsHandler(event: String, args: Array<Any>) {
        val handler = handlerMap[event] ?: return
        if (handler !is Function) return
        try {
            val cx: org.mozilla.javascript.Context? = try {
                org.mozilla.javascript.Context.getCurrentContext()
            } catch (_: Exception) {
                contextFactory.enterContext()
            }
            val savedCx: org.mozilla.javascript.Context? = if (cx == org.mozilla.javascript.Context.getCurrentContext()) null else {
                contextFactory.enterContext()
            }
            try {
                handler.call(cx ?: org.mozilla.javascript.Context.getCurrentContext(), handler.parentScope, handler, args)
            } finally {
                savedCx?.let { org.mozilla.javascript.Context.exit() }
            }
        } catch (e: Exception) {
            // 忽略处理异常
        }
    }

    fun on(event: String, handler: Any) {
        handlerMap[event] = handler
    }

    fun send(data: Any) {
        when (data) {
            is String -> ws?.send(data)
            is ByteArray -> ws?.send(ByteString.of(*data))
        }
    }

    fun close(code: Any? = 1000, reason: Any? = null) {
        val codeInt = when (code) {
            is Number -> code.toInt()
            is String -> code.toIntOrNull() ?: 1000
            else -> 1000
        }
        val reasonStr = reason?.toString()
        try { ws?.close(codeInt, reasonStr) } catch (_: Exception) {}
        completeLatch.countDown()
    }

    fun cancel() {
        try { ws?.cancel() } catch (_: Exception) {}
        completeLatch.countDown()
    }

    /**
     * 阻塞等待连接关闭，超时 60 秒。
     * @return true 表示正常完成，false 表示超时
     */
    fun awaitCompletion(timeoutSeconds: Long = 60): Boolean {
        return completeLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    }

    /**
     * 获取所有已收集的音频数据合并后的字节数组
     */
    fun getConsolidatedAudio(): ByteArray {
        synchronized(audioChunks) {
            if (audioChunks.isEmpty()) return ByteArray(0)
            val total = audioChunks.sumOf { it.size }
            val result = ByteArray(total)
            var offset = 0
            for (chunk in audioChunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            return result
        }
    }
}
