package io.legado.app.utils

import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.digest.Digester
import java.io.InputStream
import java.security.MessageDigest
import kotlin.concurrent.getOrSet

/**
 * 将字符串转化为MD5
 */
@Suppress("unused")
object MD5Utils {

    private val threadLocal = ThreadLocal<Digester>()

    private val MD5Digester
        get() = threadLocal.getOrSet {
            DigestUtil.digester("MD5")
        }

    /** 超过此字符串长度使用分块处理，避免 String.getBytes() 全量分配内存导致 OOM */
    private const val LARGE_STRING_THRESHOLD = 512 * 1024  // 512KB
    private const val CHUNK_SIZE = 8192  // 8KB

    /** 当可用堆内存低于此比例时，即使小字符串也使用分块处理 */
    private const val LOW_MEMORY_RATIO = 0.15

    /**
     * MD5 编码字符串。
     * 对超大字符串自动使用分块 MessageDigest 处理，避免 hutool Digester.digestHex(str)
     * 内部调用 str.getBytes() 导致全量字节数组分配而 OOM。
     * 当可用堆内存低于 15% 时，即使小字符串也强制使用分块处理。
     */
    fun md5Encode(str: String?): String {
        if (str == null) return ""
        return if (str.length <= LARGE_STRING_THRESHOLD && !isLowMemory()) {
            // 小字符串直接使用 hutool，更高效
            MD5Digester.digestHex(str)
        } else {
            // 大字符串或低内存时分块处理，避免一次性分配完整字节数组
            md5EncodeLargeString(str)
        }
    }

    /**
     * 检查 JVM 堆内存是否紧张（可用内存占比低于 15%）。
     * 在内存紧张时即使处理小字符串也应使用分块方式，避免 getBytes() 一次性分配压垮堆。
     */
    private fun isLowMemory(): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val freeMemory = runtime.freeMemory() + (maxMemory - runtime.totalMemory())
            freeMemory.toDouble() / maxMemory.toDouble() < LOW_MEMORY_RATIO
        } catch (_: Exception) {
            false
        }
    }

    /** 分块计算字符串的 MD5，每次只处理 CHUNK_SIZE 个字符 */
    private fun md5EncodeLargeString(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        var offset = 0
        val len = str.length
        while (offset < len) {
            val end = minOf(offset + CHUNK_SIZE, len)
            val chunk = str.substring(offset, end)
            md.update(chunk.toByteArray(Charsets.UTF_8))
            // chunk 是 substring 返回的，只占用很小内存，下次循环时即可被 GC
            offset = end
        }
        return HexUtil.encodeHexStr(md.digest())
    }

    fun md5Encode(inputStream: InputStream): String {
        return MD5Digester.digestHex(inputStream)
    }

    fun md5Encode16(str: String): String {
        var reStr = md5Encode(str)
        reStr = reStr.substring(8, 24)
        return reStr
    }
}
