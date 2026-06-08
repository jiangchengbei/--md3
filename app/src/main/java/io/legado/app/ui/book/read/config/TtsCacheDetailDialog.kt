package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.TtsCacheManager
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.themeColor
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 音频缓存详情对话框 - 按书本分组显示缓存，
 * 展示每本书的章节缓存进度，支持分别删除。
 *
 * 性能优化：
 * 1. 使用 TtsCacheManager 单例维护常驻内存的 MD5 索引和分组数据。
 * 2. 打开弹窗时直接渲染上次数据，后台增量刷新，无需等待。
 * 3. 删除缓存只做增量移除，不重建全部索引。
 * 4. 监听 TTS_CACHE_PROGRESS 事件实时更新缓存数量。
 */
class TtsCacheDetailDialog : DialogFragment() {

    companion object {
        fun invalidateCache() {
            TtsCacheManager.clear()
        }
    }

    private var cacheListContainer: LinearLayout? = null
    private var loadingIndicator: View? = null
    private var tvCacheProgress: TextView? = null

    /**
     * 书本分组缓存信息
     */
    data class CacheGroup(
        val bookName: String,
        val bookUrl: String = "",
        val chapterCount: Int,
        val totalChapterCount: Int = 0,
        val fileCount: Int,
        val totalSize: Long,
        val titleMd5Set: Set<String>,
        val chapterDetail: List<ChapterCacheInfo> = emptyList()
    )

    data class ChapterCacheInfo(
        val chapterTitle: String,
        val titleMd5: String,
        val fileCount: Int,
        val size: Long,
        val chapterIndex: Int = 0
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireActivity(), R.style.dialog_style)
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_bottom_sheet_dialog)
            setWindowAnimations(R.style.dialog_style)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            attributes = attributes?.apply {
                verticalMargin = 0f
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_tts_cache_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cacheListContainer = view.findViewById(R.id.cache_list_container)
        loadingIndicator = view.findViewById(R.id.loading_container)
        tvCacheProgress = view.findViewById(R.id.tv_cache_progress)

        view.findViewById<MaterialButton>(R.id.btn_clear_all).setOnClickListener {
            clearAllCache()
        }

        view.findViewById<MaterialButton>(R.id.btn_start_cache).setOnClickListener {
            startCacheCurrentBook()
        }

        loadCacheData()
        observeCacheProgress()
    }

    /**
     * 监听实时缓存进度事件
     */
    private fun observeCacheProgress() {
        observeEvent<TtsCacheProgress>(EventBus.TTS_CACHE_PROGRESS) { progress ->
            updateProgressText(progress)
            // 如果正在下载的是当前已显示的书籍，增量刷新对应条目
            if (progress.bookUrl.isNotBlank()) {
                incrementalUpdateForBook(progress.bookUrl, progress.titleMd5)
            }
        }
    }

    private fun updateProgressText(progress: TtsCacheProgress) {
        tvCacheProgress?.apply {
            visibility = View.VISIBLE
            text = if (progress.total > 0) {
                "正在缓存 ${progress.bookName}：第 ${progress.current}/${progress.total} 章"
            } else {
                "正在缓存 ${progress.bookName}"
            }
        }
    }

    /**
     * 开始缓存当前书籍
     */
    private fun startCacheCurrentBook() {
        val book = io.legado.app.model.ReadBook.book ?: run {
            toastOnUi("无法获取当前书籍信息")
            return
        }
        val preloadNum = io.legado.app.help.config.AppConfig.audioPreDownloadNum
        if (preloadNum <= 0) {
            toastOnUi("预加载数量为 0，请先设置预加载数量")
            return
        }

        val startIndex = book.durChapterIndex
        val endIndex = minOf(book.totalChapterNum - 1, startIndex + preloadNum)
        val chapterCount = endIndex - startIndex + 1

        try {
            val context = requireContext()
            io.legado.app.model.ReadAloud.startCache(context, startIndex, endIndex)
            toastOnUi("开始缓存 第${startIndex + 1}-${endIndex + 1} 章（共${chapterCount}章）")
        } catch (e: Exception) {
            toastOnUi("开始缓存失败：${e.localizedMessage}")
        }
    }

    /**
     * 加载缓存数据：
     * 1. 若 TtsCacheManager 已有分组数据，直接渲染，不显示 loading。
     * 2. 后台做增量刷新（对比文件快照，只处理新增/删除）。
     * 3. 若首次无数据，显示 loading 并构建索引。
     */
    private fun loadCacheData() {
        val container = cacheListContainer ?: return
        val lastGroups = TtsCacheManager.lastGroups

        if (lastGroups.isNotEmpty()) {
            // 直接显示旧数据
            loadingIndicator?.visibility = View.GONE
            container.visibility = View.VISIBLE
            renderGroups(container, lastGroups)
            // 后台刷新
            refreshInBackground()
        } else {
            // 首次打开：显示 loading，后台构建
            container.removeAllViews()
            container.visibility = View.GONE
            loadingIndicator?.visibility = View.VISIBLE
            refreshInBackground()
        }
    }

    private fun refreshInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            val groups = collectCacheGroups()
            TtsCacheManager.updateSnapshot(TtsCacheManager.scanMp3Files(), groups)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                loadingIndicator?.visibility = View.GONE
                cacheListContainer?.visibility = View.VISIBLE
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    /**
     * 收集缓存分组：优先使用 TtsCacheManager 的常驻索引，支持增量更新。
     */
    private fun collectCacheGroups(): List<CacheGroup> {
        val mp3Files = TtsCacheManager.scanMp3Files()
        if (mp3Files.isEmpty()) return emptyList()

        val fingerprint = TtsCacheManager.calcFingerprint(mp3Files)
        val (addedFiles, removedFiles) = TtsCacheManager.diffFiles(mp3Files)

        // 仅当索引从未构建时才触发完整重建；删除文件不需要重建索引
        if (!TtsCacheManager.indexBuilt && !TtsCacheManager.isBuilding) {
            TtsCacheManager.buildFullIndex()
        }

        // 对于新增的文件，如果其 titleMd5 不在索引中，尝试通过当前阅读书籍快速补全
        if (addedFiles.isNotEmpty()) {
            val currentBook = io.legado.app.model.ReadBook.book
            val currentChapter = io.legado.app.model.ReadBook.curTextChapter?.chapter
            if (currentBook != null && currentChapter != null) {
                for (fileName in addedFiles) {
                    val nameWithoutExt = File(fileName).nameWithoutExtension
                    if (nameWithoutExt == "silent") continue
                    val underscoreIdx = nameWithoutExt.indexOf('_')
                    if (underscoreIdx <= 0) continue
                    val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                    if (TtsCacheManager.getMd5Index().containsKey(titleMd5)) continue
                    // 尝试匹配当前章节
                    val currentMd5 = MD5Utils.md5Encode16(currentChapter.title.trim())
                    if (titleMd5 == currentMd5) {
                        TtsCacheManager.addEntry(
                            titleMd5,
                            currentBook.name,
                            currentBook.bookUrl,
                            currentChapter.title,
                            currentChapter.index
                        )
                    }
                }
            }
        }

        return buildGroupsFromFiles(mp3Files)
    }

    /**
     * 根据文件列表和当前索引构建分组结果。
     */
    private fun buildGroupsFromFiles(mp3Files: Array<File>): List<CacheGroup> {
        val resolvedMd5s = TtsCacheManager.getMd5Index()
        val bookInfoByUrl = TtsCacheManager.getBookInfo()

        val titleMd5ToFiles = mutableMapOf<String, MutableList<File>>()
        val orphanFiles = mutableListOf<File>()
        for (file in mp3Files) {
            val nameWithoutExt = file.nameWithoutExtension
            if (nameWithoutExt == "silent") continue
            val underscoreIdx = nameWithoutExt.indexOf('_')
            if (underscoreIdx <= 0) {
                orphanFiles.add(file)
            } else {
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                titleMd5ToFiles.getOrPut(titleMd5) { mutableListOf() }.add(file)
            }
        }

        if (titleMd5ToFiles.isEmpty() && orphanFiles.isEmpty()) return emptyList()

        val bookUrlToMd5s = mutableMapOf<String, MutableSet<String>>()
        val unknownMd5s = mutableSetOf<String>()

        for ((titleMd5, _) in titleMd5ToFiles) {
            val resolved = resolvedMd5s[titleMd5]
            if (resolved != null) {
                bookUrlToMd5s.getOrPut(resolved.bookUrl) { mutableSetOf() }.add(titleMd5)
            } else {
                unknownMd5s.add(titleMd5)
            }
        }

        val result = mutableListOf<CacheGroup>()

        for ((bookUrl, md5Set) in bookUrlToMd5s) {
            val info = bookInfoByUrl[bookUrl]
            val bookName = info?.first ?: "未知书籍"
            val totalChapters = info?.second ?: 0
            var fileCount = 0
            var totalSize = 0L
            val chapterDetails = mutableListOf<ChapterCacheInfo>()

            for (md5 in md5Set) {
                val files = titleMd5ToFiles[md5] ?: continue
                val size = files.sumOf { it.length() }
                fileCount += files.size
                totalSize += size
                val entry = resolvedMd5s[md5]
                chapterDetails.add(
                    ChapterCacheInfo(
                        chapterTitle = entry?.chapterTitle ?: md5,
                        titleMd5 = md5,
                        fileCount = files.size,
                        size = size,
                        chapterIndex = entry?.chapterIndex ?: Int.MAX_VALUE
                    )
                )
            }

            result.add(
                CacheGroup(
                    bookName = bookName,
                    bookUrl = bookUrl,
                    chapterCount = md5Set.size,
                    totalChapterCount = totalChapters,
                    fileCount = fileCount,
                    totalSize = totalSize,
                    titleMd5Set = md5Set,
                    chapterDetail = chapterDetails.sortedBy { it.chapterIndex }
                )
            )
        }

        if (unknownMd5s.isNotEmpty() || orphanFiles.isNotEmpty()) {
            var fileCount = 0
            var totalSize = 0L
            val chapterDetails = mutableListOf<ChapterCacheInfo>()

            for (md5 in unknownMd5s) {
                val files = titleMd5ToFiles[md5] ?: continue
                val size = files.sumOf { it.length() }
                fileCount += files.size
                totalSize += size
                chapterDetails.add(
                    ChapterCacheInfo(
                        chapterTitle = "未知章节($md5)",
                        titleMd5 = md5,
                        fileCount = files.size,
                        size = size
                    )
                )
            }

            if (orphanFiles.isNotEmpty()) {
                val orphanMd5 = "__orphan__"
                val orphanSize = orphanFiles.sumOf { it.length() }
                fileCount += orphanFiles.size
                totalSize += orphanSize
                titleMd5ToFiles[orphanMd5] = orphanFiles.toMutableList()
                chapterDetails.add(
                    ChapterCacheInfo(
                        chapterTitle = "格式异常文件",
                        titleMd5 = orphanMd5,
                        fileCount = orphanFiles.size,
                        size = orphanSize
                    )
                )
                unknownMd5s.add(orphanMd5)
            }

            result.add(
                CacheGroup(
                    bookName = "未知来源",
                    chapterCount = unknownMd5s.size,
                    totalChapterCount = 0,
                    fileCount = fileCount,
                    totalSize = totalSize,
                    titleMd5Set = unknownMd5s,
                    chapterDetail = chapterDetails.sortedBy { it.chapterIndex }
                )
            )
        }

        return result.sortedByDescending { it.totalSize }
    }

    /**
     * 增量更新某一本书的缓存显示（用于实时进度）。
     * 当收到缓存进度事件时，只重新扫描该 bookUrl 对应的文件，更新对应 UI 条目。
     */
    private fun incrementalUpdateForBook(bookUrl: String, titleMd5: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 确保该 titleMd5 已在索引中
            if (!TtsCacheManager.getMd5Index().containsKey(titleMd5)) {
                val currentBook = io.legado.app.model.ReadBook.book
                val currentChapter = io.legado.app.model.ReadBook.curTextChapter?.chapter
                if (currentBook != null && currentChapter != null) {
                    val md5 = MD5Utils.md5Encode16(currentChapter.title.trim())
                    if (md5 == titleMd5) {
                        TtsCacheManager.addEntry(
                            titleMd5,
                            currentBook.name,
                            currentBook.bookUrl,
                            currentChapter.title,
                            currentChapter.index
                        )
                    }
                }
            }

            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    private fun renderGroups(container: LinearLayout, groups: List<CacheGroup>) {
        container.removeAllViews()

        if (groups.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "暂无缓存数据"
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 48)
                setTextColor(
                    requireContext().themeColor(
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                textSize = 14f
            }
            container.addView(tv)
            return
        }

        for (group in groups) {
            if (container.childCount > 0) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        setMargins(16, 0, 16, 0)
                    }
                    setBackgroundColor(
                        requireContext().themeColor(
                            com.google.android.material.R.attr.colorOutlineVariant
                        )
                    )
                }
                container.addView(divider)
            }

            val bookHeader = layoutInflater.inflate(R.layout.item_tts_cache_book, container, false)

            bookHeader.findViewById<TextView>(R.id.tv_book_name).text = group.bookName
            val progressText = if (group.totalChapterCount > 0) {
                "已缓存 ${group.chapterCount}/${group.totalChapterCount} 章"
            } else {
                "已缓存 ${group.chapterCount} 章"
            }
            bookHeader.findViewById<TextView>(R.id.tv_chapter_progress).text = progressText
            bookHeader.findViewById<TextView>(R.id.tv_file_count).text = "${group.fileCount} 个音频"
            bookHeader.findViewById<TextView>(R.id.tv_size).text = formatFileSize(group.totalSize)

            val expandBtn = bookHeader.findViewById<MaterialButton>(R.id.btn_expand)
            var isExpanded = false
            expandBtn.setOnClickListener {
                isExpanded = !isExpanded
                val chapterContainer = bookHeader.findViewById<LinearLayout>(R.id.chapter_container)
                if (isExpanded) {
                    expandBtn.text = "收起章节详情 ▴"
                    renderChapterDetails(chapterContainer, group)
                    chapterContainer.visibility = View.VISIBLE
                } else {
                    expandBtn.text = "展开章节详情 ▾"
                    chapterContainer.visibility = View.GONE
                }
            }

            bookHeader.findViewById<MaterialButton>(R.id.btn_clear_book).setOnClickListener {
                clearGroup(group = group)
            }

            container.addView(bookHeader)
        }
    }

    private fun renderChapterDetails(container: LinearLayout, group: CacheGroup) {
        container.removeAllViews()

        if (group.chapterDetail.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "无章节缓存数据"
                textSize = 12f
                setTextColor(
                    requireContext().themeColor(
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                setPadding(8, 8, 8, 8)
            }
            container.addView(tv)
            return
        }

        for ((index, chapter) in group.chapterDetail.withIndex()) {
            if (index > 0) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    setBackgroundColor(
                        requireContext().themeColor(
                            com.google.android.material.R.attr.colorOutlineVariant
                        )
                    )
                }
                container.addView(divider)
            }

            val chapterItem = layoutInflater.inflate(
                R.layout.item_tts_cache_chapter, container, false
            )

            chapterItem.findViewById<TextView>(R.id.tv_chapter_title).text = chapter.chapterTitle
            chapterItem.findViewById<TextView>(R.id.tv_chapter_file_count).text =
                "${chapter.fileCount} 个音频"
            chapterItem.findViewById<TextView>(R.id.tv_chapter_size).text =
                formatFileSize(chapter.size)

            chapterItem.findViewById<TextView>(R.id.btn_clear_chapter).setOnClickListener {
                deleteChapterCache(chapter)
            }

            container.addView(chapterItem)
        }
    }

    private fun deleteChapterCache(chapter: ChapterCacheInfo) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = getTtsCacheDir()
            var deleted = 0
            cacheDir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".mp3")) return@forEach
                val nameWithoutExt = file.nameWithoutExtension
                val underscoreIdx = nameWithoutExt.indexOf('_')
                if (underscoreIdx <= 0) return@forEach
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                if (titleMd5 == chapter.titleMd5 && file.delete()) {
                    deleted++
                }
            }
            // 增量更新：从索引中移除，并更新分组
            TtsCacheManager.removeEntries(setOf(chapter.titleMd5))
            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                toastOnUi("已清理「${chapter.chapterTitle}」的 $deleted 个缓存文件")
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    private fun getTtsCacheDir(): File {
        val baseDir = appCtx.externalCacheDir ?: appCtx.cacheDir
        return File(baseDir, "httpTTS")
    }

    private fun clearGroup(group: CacheGroup) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = getTtsCacheDir()
            if (!dir.exists()) return@launch

            var deleted = 0
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".mp3")) return@forEach
                val nameWithoutExt = file.nameWithoutExtension
                val underscoreIdx = nameWithoutExt.indexOf('_')
                if (underscoreIdx <= 0) return@forEach
                val titleMd5 = nameWithoutExt.substring(0, underscoreIdx)
                if (titleMd5 in group.titleMd5Set) {
                    if (file.delete()) deleted++
                }
            }

            // 增量更新：只移除该组的 MD5，不重建全部索引
            TtsCacheManager.removeEntries(group.titleMd5Set)
            val allFiles = TtsCacheManager.scanMp3Files()
            val groups = buildGroupsFromFiles(allFiles)
            TtsCacheManager.updateSnapshot(allFiles, groups)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                toastOnUi("已清理「${group.bookName}」的 $deleted 个缓存文件")
                renderGroups(cacheListContainer!!, groups)
            }
        }
    }

    private fun clearAllCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = getTtsCacheDir()
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                toastOnUi("已清理全部缓存")
                TtsCacheManager.clear()
                dismiss()
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

/**
 * TTS 缓存进度事件数据类
 */
data class TtsCacheProgress(
    val bookUrl: String,
    val bookName: String,
    val chapterTitle: String,
    val titleMd5: String,
    val current: Int,
    val total: Int,
    val fileSize: Long = 0
)
