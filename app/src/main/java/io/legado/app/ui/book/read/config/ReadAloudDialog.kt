package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.legado.app.R
import io.legado.app.model.BookCover
import io.legado.app.ui.config.coverConfig.CoverConfig
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.OldThemeConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.AiBgMusic
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.StringUtils
import io.legado.app.utils.TTSCacheUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.themeColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

class ReadAloudDialog : DialogFragment(R.layout.dialog_read_aloud) {
    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)

    private lateinit var gestureDetector: GestureDetectorCompat
    private var startX = 0f
    private var startY = 0f
    private var isAnimating = false
    private var isHorizontalSwipe = false

    // 封面轮播相关
    private var coverTimer: Timer? = null
    private var defaultCoverPaths: List<String> = emptyList()
    private var currentCoverIndex = 0

    // 歌词式字幕相关
    private data class SubtitleLine(val text: String, val type: Int) {
        companion object {
            const val PREVIOUS = 0
            const val CURRENT = 1
            const val NEXT = 2
        }
    }
    private val subtitleItems = mutableListOf<SubtitleLine>()
    private var lastCurrentParagraph: String = ""
    private var lastPreviousParagraphs: List<String> = emptyList()
    private var scrollAnimator: ValueAnimator? = null

    // 封面收起/展开动画相关
    private var isCoverCollapsed = false
    private var coverAnimator: ValueAnimator? = null
    private var topContainerOriginalHeight = 0
    private var coverOriginalScaleX = 1f
    private var coverOriginalScaleY = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 使用全屏主题
        setStyle(STYLE_NORMAL, R.style.Theme_App_Dialog_FullScreen)
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        // 使用 Activity 的 context 来获取 LayoutInflater，确保能正确获取动态颜色
        return super.onGetLayoutInflater(savedInstanceState).cloneInContext(activity ?: requireContext())
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.apply {
                requestFeature(Window.FEATURE_NO_TITLE)
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.TOP or Gravity.START
            // 通过设置窗口背景色确保不透明，禁止透明设定
            val surfaceColor = context.themeColor(com.google.android.material.R.attr.colorSurface)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surfaceColor))
            attributes = attr
            // 设置左右滑入动画（打开时）
            setWindowAnimations(R.style.SlideInLeftAnim)
            // 在 onStart 中设置导航栏颜色，避免 DecorView 未初始化导致的崩溃
            kotlin.runCatching {
                updateThemeColors()
            }
            // 设置沉浸式状态栏
            WindowCompat.setDecorFitsSystemWindows(this, false)
            updateStatusBarColor()
            // 为标签栏添加状态栏高度内边距
            binding.tabBar.post {
                val windowInsets = decorView.rootWindowInsets ?: return@post
                val insets = WindowInsetsCompat
                    .toWindowInsetsCompat(windowInsets, decorView)
                val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                if (statusBarInset > 0) {
                    binding.tabBar.setPadding(
                        binding.tabBar.paddingLeft,
                        statusBarInset,
                        binding.tabBar.paddingRight,
                        binding.tabBar.paddingBottom
                    )
                }
            }
        }
    }

    private fun updateThemeColors() {
        val context = activity ?: return
        val surfaceColor = context.themeColor(com.google.android.material.R.attr.colorSurface)
        dialog?.window?.let { window ->
            kotlin.runCatching {
                window.setNavigationBarColorAuto(surfaceColor)
            }
            window.statusBarColor = surfaceColor
        }
    }

    private fun updateStatusBarColor() {
        val context = activity ?: return
        val surfaceColor = context.themeColor(com.google.android.material.R.attr.colorSurface)
        val isLightBar = ColorUtils.isColorLight(surfaceColor)
        dialog?.window?.let { window ->
            // 状态栏设为透明，让模糊封面背景可以延伸至状态栏区域
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.isAppearanceLightStatusBars = isLightBar
            }
        }
    }

    // 当前页面：true=播放页, false=设置页
    private var isPlaybackPage = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog++
        }
        initGestureDetector()
        initCoverCollapseGesture()
        binding.run {
            initData()
            initEvent()
            initTabSwitch()
            loadCover()
            updateSubtitle()
            updateBookInfo()
            initSettingsPage()
        }
        observeLiveBus()
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetectorCompat(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (isAnimating) return false
                val diffX = e2.x - (e1?.x ?: 0f)
                val diffY = e2.y - (e1?.y ?: 0f)
                // 水平滑动且距离大于垂直距离
                if (abs(diffX) > abs(diffY) && abs(diffX) > 100 && abs(velocityX) > 200) {
                    if (diffX > 0) {
                        // 向右滑 → 切换到播放页
                        if (!isPlaybackPage) {
                            switchToPageAnimated(true, false)
                        }
                    } else {
                        // 向左滑 → 切换到设置页
                        if (isPlaybackPage) {
                            switchToPageAnimated(false, true)
                        }
                    }
                    return true
                }
                return false
            }
        })

        // 在根布局上监听触摸，处理 ScrollView 拦截问题
        binding.rootLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isHorizontalSwipe = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - startX)
                    val dy = abs(event.y - startY)
                    // 检测到水平滑动后，禁止子 View（ScrollView）拦截触摸
                    if (!isHorizontalSwipe && dx > dy && dx > 20) {
                        isHorizontalSwipe = true
                        binding.rootLayout.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    // 水平滑动时阻止 ScrollView 滚动
                    if (isHorizontalSwipe) {
                        binding.rootLayout.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.rootLayout.parent?.requestDisallowInterceptTouchEvent(false)
                    binding.rootLayout.requestDisallowInterceptTouchEvent(false)
                    isHorizontalSwipe = false
                }
            }
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun slideOutToRight() {
        if (isAnimating) return
        isAnimating = true
        val view = dialog?.window?.decorView ?: return
        view.animate()
            .translationX(view.width.toFloat())
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                dismissAllowingStateLoss()
            }
            .start()
    }

    private fun slideOutToLeft() {
        if (isAnimating) return
        isAnimating = true
        val view = dialog?.window?.decorView ?: return
        view.animate()
            .translationX(-view.width.toFloat())
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                dismissAllowingStateLoss()
            }
            .start()
    }

    override fun onDestroyView() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        coverAnimator?.cancel()
        coverAnimator = null
        super.onDestroyView()
        stopCoverCarousel()
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog--
        }
    }

    private fun initData() = binding.run {
        upPlayState()
        upTimerText(BaseReadAloudService.timeMinute)
        upSpeedText()
        // 初始化字幕字体样式
        initSubtitleStyle()
    }

    /**
     * 初始化字幕字体样式，跟随小说正文设置
     * 字幕字体大小可由 readAloudSubtitleFontSize 独立调节（0 表示跟随正文设置）
     */
    private fun initSubtitleStyle() {
        // 歌词样式由 renderSubtitleLines 动态管理，这里仅做初始渲染
        updateSubtitle()
    }

    /**
     * 获取阅读字体
     */
    private fun getReadFontTypeface(fontPath: String): Typeface {
        return kotlin.runCatching {
            when {
                fontPath.isContentScheme() -> {
                    requireContext().contentResolver
                        .openFileDescriptor(android.net.Uri.parse(fontPath), "r")!!
                        .use {
                            Typeface.Builder(it.fileDescriptor).build()
                        }
                }
                fontPath.isNotEmpty() -> {
                    Typeface.Builder(File(fontPath)).build()
                }
                else -> {
                    when (AppConfig.systemTypefaces) {
                        1 -> Typeface.SERIF
                        2 -> Typeface.MONOSPACE
                        else -> Typeface.SANS_SERIF
                    }
                }
            }
        }.getOrElse {
            Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    /**
     * 初始化页面切换标签
     */
    private fun initTabSwitch() = binding.run {
        tabPlayback.setOnClickListener {
            switchToPage(true)
        }
        tabSettings.setOnClickListener {
            switchToPage(false)
        }
    }

    /**
     * 切换页面（带动画）
     */
    private fun switchToPageAnimated(toPlayback: Boolean, slideLeft: Boolean) {
        if (isAnimating) return
        if (isPlaybackPage == toPlayback) return
        isAnimating = true

        val context = requireContext()
        val selectedTextColor = context.themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val onSurfaceVariant = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val outgoingPage: View
        val incomingPage: View
        val totalWidth = binding.rootLayout.width

        if (toPlayback) {
            // 切换到播放页
            outgoingPage = binding.settingsPage
            incomingPage = binding.playbackPage
        } else {
            // 切换到设置页
            outgoingPage = binding.playbackPage
            incomingPage = binding.settingsPage
        }

        // 设置进入页初始位置
        val startTranslation = if (slideLeft) totalWidth.toFloat() else -totalWidth.toFloat()
        incomingPage.translationX = startTranslation
        incomingPage.alpha = 1f
        incomingPage.visibility = View.VISIBLE

        // 如果切换到设置页，隐藏底部控件
        if (!toPlayback) {
            binding.bottomContainer.visibility = View.GONE
        }

        // 执行动画
        outgoingPage.animate()
            .translationX(if (slideLeft) -totalWidth.toFloat() else totalWidth.toFloat())
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                outgoingPage.visibility = View.GONE
                outgoingPage.translationX = 0f
                outgoingPage.alpha = 1f
            }
            .start()

        incomingPage.animate()
            .translationX(0f)
            .setDuration(250)
            .withEndAction {
                isAnimating = false
                isPlaybackPage = toPlayback

                // 更新标签颜色
                if (toPlayback) {
                    binding.tabPlayback.setTextColor(selectedTextColor)
                    binding.tabSettings.setTextColor(onSurfaceVariant)
                    binding.bottomContainer.visibility = View.VISIBLE
                    updateBgVisibility(true)
                } else {
                    binding.tabPlayback.setTextColor(onSurfaceVariant)
                    binding.tabSettings.setTextColor(selectedTextColor)
                    updateBgVisibility(false)
                    refreshCacheInfo()
                    updateSettingsSummary()
                }
            }
            .start()
    }
    private fun switchToPage(playback: Boolean) = binding.run {
        if (isPlaybackPage == playback) return
        isPlaybackPage = playback

        val context = requireContext()
        val selectedTextColor = context.themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        val onSurfaceVariant = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        if (playback) {
            tabPlayback.setTextColor(selectedTextColor)
            tabSettings.setTextColor(onSurfaceVariant)
            playbackPage.visibility = View.VISIBLE
            settingsPage.visibility = View.GONE
            bottomContainer.visibility = View.VISIBLE
            updateBgVisibility(true)
        } else {
            tabPlayback.setTextColor(onSurfaceVariant)
            tabSettings.setTextColor(selectedTextColor)
            playbackPage.visibility = View.GONE
            settingsPage.visibility = View.VISIBLE
            bottomContainer.visibility = View.GONE
            updateBgVisibility(false)
            refreshCacheInfo()
            updateSettingsSummary()
        }
    }

    private fun updateBgVisibility(visible: Boolean) = binding.run {
        val vis = if (visible) View.VISIBLE else View.GONE
        ivBg.visibility = if (visible && ivBg.drawable != null) View.VISIBLE else View.GONE
        // mask and gradient depend on bg image being visible
        if (ivBg.visibility == View.VISIBLE && visible) {
            vBgMask.visibility = View.VISIBLE
            vBgGradient.visibility = View.VISIBLE
        } else {
            vBgMask.visibility = View.GONE
            vBgGradient.visibility = View.GONE
        }
    }

    /**
     * 初始化设置页
     */
    private fun initSettingsPage() = binding.run {
        // 管理缓存按钮 - 打开二级弹窗
        btnClearCache.setOnClickListener {
            TtsCacheDetailDialog().show(childFragmentManager, "ttsCacheDetailDialog")
        }

        // 开始缓存按钮
        btnStartCache.setOnClickListener {
            startCacheCurrentBook()
        }

        // 缓存保留时间
        llCacheCleanTime.setOnClickListener {
            showCacheCleanTimePicker()
        }

        // 预加载数量
        llPreloadNum.setOnClickListener {
            showPreloadNumPicker()
        }

        // 发音引擎
        llTtsEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }

        // 初始刷新设置摘要
        updateSettingsSummary()
    }

    /**
     * 清理 TTS 缓存
     */
    private fun clearTtsCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            TTSCacheUtils.clearTtsCache()
            withContext(Dispatchers.Main) {
                toastOnUi("音频缓存已清理")
                refreshCacheInfo()
            }
        }
    }

    /**
     * 开始缓存当前书籍的 TTS 音频
     * 预加载后续若干章节的音频缓存
     */
    private fun startCacheCurrentBook() {
        val book = ReadBook.book ?: run {
            toastOnUi("无法获取当前书籍信息")
            return
        }
        val preloadNum = AppConfig.audioPreDownloadNum
        if (preloadNum <= 0) {
            toastOnUi("预加载数量为 0，请先设置预加载数量")
            return
        }

        val startIndex = book.durChapterIndex
        val endIndex = minOf(book.totalChapterNum - 1, startIndex + preloadNum)
        val chapterCount = endIndex - startIndex + 1

        // 启动朗读服务（会自动缓存音频文件到 httpTTS 目录）
        try {
            val context = requireContext()
            ReadAloud.startCache(context, startIndex, endIndex)
            toastOnUi("开始缓存 第${startIndex + 1}-${endIndex + 1} 章（共${chapterCount}章）")
            TtsCacheDetailDialog().show(childFragmentManager, "ttsCacheDetailDialog")
        } catch (e: Exception) {
            toastOnUi("开始缓存失败：${e.localizedMessage}")
        }
    }

    /**
     * 刷新缓存信息显示
     */
    private fun refreshCacheInfo() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = getTtsCacheDir()
            val fileCount = getCacheFileCount(cacheDir)
            val totalSize = getCacheDirSize(cacheDir)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                binding.run {
                    tvCacheDir.text = cacheDir?.absolutePath ?: "未知"
                    tvCacheFileCount.text = "$fileCount 个文件"
                    tvCacheSize.text = formatFileSize(totalSize)
                }
            }
        }
    }

    /**
     * 获取 TTS 缓存目录
     */
    private fun getTtsCacheDir(): File? {
        val baseDir = requireContext().externalCacheDir ?: requireContext().cacheDir
        val dir = File(baseDir, "httpTTS")
        return if (dir.exists()) dir else null
    }

    /**
     * 获取缓存文件数量
     */
    private fun getCacheFileCount(dir: File?): Int {
        if (dir == null || !dir.exists()) return 0
        return dir.listFiles()?.count { it.isFile } ?: 0
    }

    /**
     * 获取缓存目录总大小
     */
    private fun getCacheDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var totalSize = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
            }
        }
        return totalSize
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * 更新设置摘要信息
     */
    private fun updateSettingsSummary() = binding.run {
        // 缓存保留时间
        val cleanTime = AppConfig.audioCacheCleanTimeOrgin
        tvCacheCleanTimeSummary.text = if (cleanTime == 0) {
            "退出即刻清理"
        } else {
            "保留 $cleanTime 分钟"
        }

        // 预加载数量
        tvPreloadNumSummary.text = "当前 ${AppConfig.audioPreDownloadNum} 个"

        // 发音引擎
        tvTtsEngineSummary.text = getSpeakEngineName()
    }

    /**
     * 获取当前发音引擎名称
     */
    private fun getSpeakEngineName(): String {
        val ttsEngine = ReadAloud.ttsEngine ?: return "系统TTS"
        if (StringUtils.isNumeric(ttsEngine)) {
            return appDb.httpTTSDao.getName(ttsEngine.toLong()) ?: "系统TTS"
        }
        return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title ?: "系统TTS"
    }

    /**
     * 显示缓存保留时间选择器
     */
    private fun showCacheCleanTimePicker() {
        NumberPickerDialog(requireContext())
            .setTitle("音频缓存保留时间(分钟)")
            .setMaxValue(10000)
            .setMinValue(0)
            .setValue(AppConfig.audioCacheCleanTimeOrgin)
            .setCustomButton(R.string.btn_default_s) {
                requireContext().putPrefInt(PreferKey.audioCacheCleanTime, 10)
                updateSettingsSummary()
            }
            .show {
                requireContext().putPrefInt(PreferKey.audioCacheCleanTime, it)
                updateSettingsSummary()
            }
    }

    /**
     * 显示预加载数量选择器
     */
    private fun showPreloadNumPicker() {
        NumberPickerDialog(requireContext())
            .setTitle("听书预加载数量")
            .setMaxValue(10000)
            .setMinValue(0)
            .setValue(AppConfig.audioPreDownloadNum)
            .setCustomButton(R.string.btn_default_s) {
                requireContext().putPrefInt(PreferKey.audioPreDownloadNum, 10)
                updateSettingsSummary()
            }
            .show {
                requireContext().putPrefInt(PreferKey.audioPreDownloadNum, it)
                updateSettingsSummary()
            }
    }

    private fun initEvent() = binding.run {
        ivMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        ivSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        ivCharacterManager.setOnClickListener {
            CharacterManagerDialog().show(childFragmentManager, "characterManagerDialog")
        }
        ivBgmSetting.setOnClickListener {
            showAiBgMusicPlaybackConfig()
        }
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        ivCatalog.setOnClickListener { callBack?.openChapterList() }
        ivDayNight.setOnClickListener {
            val context = requireContext()
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            OldThemeConfig.applyDayNight(context)
            ivDayNight.setImageResource(
                if (AppConfig.isNightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness
            )
        }

        // 语速设定按钮
        btnSpeedSetting.setOnClickListener {
            val speeds = AppConfig.speechRateValues
            // 第一项为"跟随系统"，后续为各语速选项
            val allItems = mutableListOf("跟随系统")
            allItems.addAll(speeds.map { "${it}x" })
            context?.selector("设定语速", allItems) { _, index ->
                if (index == 0) {
                    // 跟随系统
                    AppConfig.ttsFlowSys = true
                    upSpeedText()
                } else {
                    // 选择具体语速
                    AppConfig.ttsFlowSys = false
                    AppConfig.ttsSpeechRate = index - 1
                    upSpeedText()
                }
                upTtsSpeechRate()
            }
        }

        btnTimer.setOnClickListener {
            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
            val timeKeys = times.map { "$it 分钟" }
            context?.selector("设定时间", timeKeys) { _, index ->
                ReadAloud.setTimer(requireContext(), times[index])
                upTimerText(times[index])
            }
        }

        // 封面设定
        btnCoverSize.setOnClickListener {
            showCoverSettingsDialog()
        }

        // 字体大小
        btnFontSize.setOnClickListener {
            NumberPickerDialog(requireContext())
                .setTitle("字体大小")
                .setMaxValue(40)
                .setMinValue(0)
                .setValue(AppConfig.readAloudSubtitleFontSize)
                .setCustomButton(R.string.btn_default_s) {
                    AppConfig.readAloudSubtitleFontSize = 0
                    postEvent(EventBus.READ_ALOUD_SUBTITLE_FONT_SIZE, true)
                }
                .show {
                    AppConfig.readAloudSubtitleFontSize = it
                    postEvent(EventBus.READ_ALOUD_SUBTITLE_FONT_SIZE, true)
                }
        }

    }

    private fun showCoverSettingsDialog() {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_cover_settings, null)
        val coverPicker = view.findViewById<android.widget.NumberPicker>(R.id.number_picker)
        val switchShowCover =
            view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_show_cover)

        coverPicker.minValue = 50
        coverPicker.maxValue = 500
        coverPicker.value = AppConfig.readAloudCoverSize
        switchShowCover.isChecked = AppConfig.readAloudShowCover

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.read_aloud_cover_size)
            .setView(view)
            .setNeutralButton(R.string.btn_default_s) { _, _ ->
                AppConfig.readAloudCoverSize = 300
                AppConfig.readAloudShowCover = true
                postEvent(EventBus.READ_ALOUD_COVER_SIZE, true)
                postEvent(EventBus.READ_ALOUD_SHOW_COVER, true)
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                AppConfig.readAloudCoverSize = coverPicker.value
                AppConfig.readAloudShowCover = switchShowCover.isChecked
                postEvent(EventBus.READ_ALOUD_COVER_SIZE, true)
                postEvent(EventBus.READ_ALOUD_SHOW_COVER, true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun upSpeedText() {
        val context = requireContext()
        if (AppConfig.ttsFlowSys) {
            binding.btnSpeedSetting.text = context.getString(R.string.tts_speed_system)
        } else {
            val speed = AppConfig.speechRateFloat
            binding.btnSpeedSetting.text = context.getString(R.string.tts_speed_value, speed)
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.icon =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.icon =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_play)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.btnTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.btnTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    private fun loadCover() = binding.let { b ->
        val book = ReadBook.book
        val displayCover = book?.getDisplayCover()

        // 根据封面尺寸设置动态调整 ImageView 大小
        val scaleFactor = AppConfig.readAloudCoverSize / 100f
        val baseWidth = 214
        val baseHeight = 278
        val scaledWidth = (baseWidth * scaleFactor).toInt()
        val scaledHeight = (baseHeight * scaleFactor).toInt()
        val lp = b.ivCover.layoutParams
        if (lp != null) {
            lp.width = scaledWidth
            lp.height = scaledHeight
            b.ivCover.layoutParams = lp
        }

        // 停止之前的轮播
        stopCoverCarousel()

        val coverCarouselEnabled = AppConfig.readAloudCoverCarousel

        val request = BookCover.load(
            context = requireContext(),
            path = displayCover,
            loadOnlyWifi = CoverConfig.loadCoverOnlyWifi,
            sourceOrigin = book?.origin,
        ).transform(CenterCrop())

        // 需要启动轮播的情况：
        // 1. useDefaultCover=true（强制显示默认封面）
        // 2. displayCover为空（书没有封面URL）
        // 3. 真实封面加载失败（URL无图），Glide会回退到默认封面
        if (coverCarouselEnabled) {
            when {
                AppConfig.useDefaultCover || displayCover.isNullOrBlank() -> {
                    // 明确使用默认封面 → 立即启动轮播
                    request.into(b.ivCover)
                    startCoverCarousel()
                }
                else -> {
                    // 有封面URL但可能加载失败 → 添加监听，失败时启动轮播
                    request.listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean,
                        ): Boolean {
                            // 真实封面加载失败（显示默认封面）→ 启动轮播
                            startCoverCarousel()
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean,
                        ): Boolean {
                            // 真实封面加载成功 → 不启动轮播
                            return false
                        }
                    }).into(b.ivCover)
                }
            }
        } else {
            request.into(b.ivCover)
        }

        // 加载封面后同步加载模糊背景
        loadBgBlur(displayCover, book?.origin)

        // 应用封面显隐设定
        applyCoverVisibility()

        // 封面重新加载后，重置动画相关状态
        topContainerOriginalHeight = 0
        if (isCoverCollapsed) {
            resetCoverToExpanded()
        }
    }

    /**
     * 根据显示封面开关控制封面图的显隐。
     * 关闭时隐藏封面图，字幕区域自然扩展显示更多内容。
     * 模糊背景始终显示，不受封面显隐影响。
     */
    private fun applyCoverVisibility() {
        val showCover = AppConfig.readAloudShowCover
        binding.ivCover.visibility = if (showCover) View.VISIBLE else View.GONE
        // 背景始终显示（有图时），不随封面一同隐藏
        if (showCover) {
            updateBgVisibility(true)
        }
        // 封面显示开关变化时，重置动画状态
        if (showCover && isCoverCollapsed) {
            // 开关从关闭变为开启，需要恢复封面
            coverAnimator?.cancel()
            resetCoverToExpanded()
        } else if (!showCover) {
            // 开关关闭，取消动画并重置状态
            coverAnimator?.cancel()
            isCoverCollapsed = false
        }
    }

    /**
     * 重置封面为展开状态（无动画），用于切换设置后的快速恢复。
     */
    private fun resetCoverToExpanded() {
        val topContainer = binding.topContainer
        val coverView = binding.ivCover
        val bookTitle = binding.tvBookTitle
        val chapterTitle = binding.tvChapterTitle

        if (topContainerOriginalHeight > 0) {
            val lp = topContainer.layoutParams
            lp.height = topContainerOriginalHeight
            topContainer.layoutParams = lp
        }
        coverView.visibility = View.VISIBLE
        coverView.alpha = 1f
        coverView.scaleX = coverOriginalScaleX
        coverView.scaleY = coverOriginalScaleY
        bookTitle.alpha = 1f
        bookTitle.translationY = 0f
        chapterTitle.alpha = 1f
        chapterTitle.translationY = 0f
        isCoverCollapsed = false
    }

    /**
     * 初始化字幕区域点击手势，用于封面收起/展开动画。
     * 仅在封面显示时（readAloudShowCover = true）点击字幕区域才触发动画。
     */
    private fun initCoverCollapseGesture() {
        // 用 flag 防止动画过程中的双击冲突
        var lastClickTime = 0L
        val clickListener = View.OnClickListener {
            // 只有封面显示开关打开时才支持收起/展开动画
            if (!AppConfig.readAloudShowCover) return@OnClickListener
            // 防止快速双击
            val now = System.currentTimeMillis()
            if (now - lastClickTime < 500) return@OnClickListener
            lastClickTime = now
            toggleCoverCollapse()
        }
        // 在字幕容器和内部字幕行上都设置监听，确保点击不被子 View 拦截
        binding.subtitleContainer.setOnClickListener(clickListener)
        binding.subtitleLines.setOnClickListener(clickListener)
        binding.scrollView.setOnClickListener(clickListener)
    }

    /**
     * 切换封面收起/展开状态，带动画。
     */
    private fun toggleCoverCollapse() {
        // 取消之前的动画
        coverAnimator?.cancel()

        val topContainer = binding.topContainer
        val coverView = binding.ivCover
        val bookTitle = binding.tvBookTitle
        val chapterTitle = binding.tvChapterTitle

        // 记录 topContainer 的原始高度和封面的原始 scale
        if (topContainerOriginalHeight == 0) {
            topContainerOriginalHeight = topContainer.height
            coverOriginalScaleX = coverView.scaleX
            coverOriginalScaleY = coverView.scaleY
        }

        val targetCollapsed = !isCoverCollapsed

        // 以 topContainer 的实际当前高度为起始值
        val startHeight = topContainer.height
        val targetHeight: Int
        val targetCoverAlpha: Float
        val targetCoverScaleX: Float
        val targetCoverScaleY: Float
        val targetTitleAlpha: Float
        val targetTitleTranslationY: Float

        if (targetCollapsed) {
            // 收起：封面缓缓缩小消失，书名和章节淡出，topContainer 高度缩小
            targetHeight = dpToPxInt(12)
            targetCoverAlpha = 0f
            targetCoverScaleX = 0.75f
            targetCoverScaleY = 0.75f
            targetTitleAlpha = 0f
            targetTitleTranslationY = -dpToPxInt(8).toFloat()
        } else {
            // 展开：封面缓缓放大恢复，书名和章节淡入，topContainer 恢复到原始高度
            targetHeight = topContainerOriginalHeight
            targetCoverAlpha = 1f
            targetCoverScaleX = coverOriginalScaleX
            targetCoverScaleY = coverOriginalScaleY
            targetTitleAlpha = 1f
            targetTitleTranslationY = 0f
        }

        val startCoverAlpha = coverView.alpha
        val startCoverScaleX = coverView.scaleX
        val startCoverScaleY = coverView.scaleY
        val startTitleAlpha = bookTitle.alpha
        val startTitleTranslationY = bookTitle.translationY

        coverAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            // 线性插值器，由自定义缓动函数完全控制曲线
            interpolator = android.view.animation.LinearInterpolator()

            addUpdateListener { animator ->
                val fraction = animator.animatedFraction

                // 使用 ease-out 让动画尾部柔和减速
                val easedFraction = easeOutQuint(fraction)

                // 更新 topContainer 高度
                val currentHeight = (startHeight + (targetHeight - startHeight) * easedFraction).toInt()
                val lp = topContainer.layoutParams
                lp.height = currentHeight
                topContainer.layoutParams = lp

                // 更新封面 alpha 和 scale
                coverView.alpha = startCoverAlpha + (targetCoverAlpha - startCoverAlpha) * easedFraction
                coverView.scaleX = startCoverScaleX + (targetCoverScaleX - startCoverScaleX) * easedFraction
                coverView.scaleY = startCoverScaleY + (targetCoverScaleY - startCoverScaleY) * easedFraction

                // 更新书名和章节的 alpha 和 translationY
                val titleAlpha = startTitleAlpha + (targetTitleAlpha - startTitleAlpha) * easedFraction
                bookTitle.alpha = titleAlpha
                chapterTitle.alpha = titleAlpha
                val titleTY = startTitleTranslationY + (targetTitleTranslationY - startTitleTranslationY) * easedFraction
                bookTitle.translationY = titleTY
                chapterTitle.translationY = titleTY

                // 封面收起时逐渐设置封面为不可点击（避免视觉残留）
                if (targetCollapsed && easedFraction > 0.95f) {
                    coverView.visibility = View.INVISIBLE
                } else if (!targetCollapsed && easedFraction < 0.05f) {
                    coverView.visibility = View.VISIBLE
                }
            }

            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    // 展开时确保封面先变为可见
                    if (!targetCollapsed) {
                        coverView.visibility = View.VISIBLE
                    }
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isCoverCollapsed = targetCollapsed
                    // 确保最终状态正确
                    val lp = topContainer.layoutParams
                    lp.height = targetHeight
                    topContainer.layoutParams = lp

                    coverView.alpha = targetCoverAlpha
                    coverView.scaleX = targetCoverScaleX
                    coverView.scaleY = targetCoverScaleY
                    coverView.visibility = if (targetCollapsed) View.GONE else View.VISIBLE

                    bookTitle.alpha = targetTitleAlpha
                    chapterTitle.alpha = targetTitleAlpha
                    bookTitle.translationY = targetTitleTranslationY
                    chapterTitle.translationY = targetTitleTranslationY
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })

            start()
        }
    }

    /**
     * Quint ease-out 缓动函数，前期自然启动，尾部非常柔和地减速到零，极度丝滑。
     */
    private fun easeOutQuint(t: Float): Float {
        val t1 = 1f - t
        return 1f - t1 * t1 * t1 * t1 * t1
    }

    /**
     * dp 转 px 整型
     */
    private fun dpToPxInt(dp: Int): Int {
        return (dp * requireContext().resources.displayMetrics.density).toInt()
    }

    /**
     * 启动默认封面轮播
     * 从封面池中轮播切换封面，每3秒一张
     */
    private fun startCoverCarousel() {
        stopCoverCarousel()

        // 获取封面池中的封面列表
        val isNightTheme = AppConfig.isNightTheme
        val key = if (isNightTheme) PreferKey.defaultCoverDark else PreferKey.defaultCover
        defaultCoverPaths = requireContext().getPrefString(key)?.split(",")?.filter { it.isNotBlank() }
            ?: emptyList()

        // 如果封面池只有一张或没有封面，不进行轮播
        if (defaultCoverPaths.size <= 1) return

        // 随机选择一个起始位置
        currentCoverIndex = kotlin.random.Random.nextInt(defaultCoverPaths.size)

        // 启动定时器，每3秒切换封面
        coverTimer = Timer()
        coverTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                activity?.runOnUiThread {
                    switchToNextDefaultCover()
                }
            }
        }, 3000, 3000)
    }

    /**
     * 切换到下一张默认封面，使用淡入淡出动画
     */
    private fun switchToNextDefaultCover() {
        if (defaultCoverPaths.isEmpty()) return

        val coverView = binding.ivCover
        val bgView = binding.ivBg

        // 更新索引
        currentCoverIndex = (currentCoverIndex + 1) % defaultCoverPaths.size
        val nextCoverPath = defaultCoverPaths[currentCoverIndex]

        // 直接解码指定路径的封面图
        val nextDrawable = kotlin.runCatching {
            BitmapUtils.decodeBitmap(nextCoverPath, 600, 900)
        }.getOrNull()?.toDrawable(requireContext().resources)
            ?: return

        // 淡出当前封面
        coverView.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                // 更新封面
                coverView.setImageDrawable(nextDrawable)
                // 淡入新封面
                coverView.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .start()
            }
            .start()

        // 同时更新模糊背景
        if (bgView.visibility == View.VISIBLE) {
            bgView.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    if (!isAdded) return@withEndAction
                    BookCover.loadBlur(requireContext(), nextCoverPath)
                        .into(bgView)
                    bgView.alpha = 0f
                    bgView.animate()
                        .alpha(1f)
                        .setDuration(500)
                        .start()
                }
                .start()
        }
    }

    /**
     * 停止封面轮播定时器
     */
    private fun stopCoverCarousel() {
        coverTimer?.cancel()
        coverTimer = null
    }

    /**
     * 加载模糊封面作为背景，效果与书籍详情页相同
     * API 31+ 使用 RenderEffect 硬件模糊，低版本使用 Glide BlurTransformation
     */
    private fun loadBgBlur(coverPath: String?, sourceOrigin: String?) {
        if (coverPath.isNullOrBlank()) return
        if (AppConfig.isEInkMode) return
        binding.run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BookCover.load(requireContext(), coverPath, sourceOrigin = sourceOrigin)
                    .into(ivBg)
                val blurEffect = RenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.CLAMP)
                ivBg.setRenderEffect(blurEffect)
            } else {
                BookCover.loadBlur(requireContext(), coverPath, sourceOrigin = sourceOrigin)
                    .into(ivBg)
            }
            ivBg.visibility = View.VISIBLE
            vBgMask.visibility = View.VISIBLE
            vBgGradient.visibility = View.VISIBLE
        }
    }

    private fun updateBookInfo() = binding.run {
        val book = ReadBook.book
        tvBookTitle.text = book?.name ?: ""
        tvChapterTitle.text = ReadBook.curTextChapter?.title ?: ""
    }

    private fun updateSubtitle() {
        val text = BaseReadAloudService.currentParagraphText
        val indent = ReadBookConfig.paragraphIndent
        val trimmedText = if (text.startsWith(indent)) {
            text.removePrefix(indent)
        } else {
            text
        }
        val currentText = trimmedText.ifEmpty { "" }

        val prevParagraphs = BaseReadAloudService.previousParagraphs.map {
            if (it.startsWith(indent)) it.removePrefix(indent) else it
        }
        val nextParagraphs = BaseReadAloudService.nextParagraphs.map {
            if (it.startsWith(indent)) it.removePrefix(indent) else it
        }

        // 仅当内容变化时重建字幕列表
        if (currentText == lastCurrentParagraph && prevParagraphs == lastPreviousParagraphs) return
        lastCurrentParagraph = currentText
        lastPreviousParagraphs = prevParagraphs

        // 重建歌词文本列表
        subtitleItems.clear()
        for (p in prevParagraphs) {
            subtitleItems.add(SubtitleLine(p, SubtitleLine.PREVIOUS))
        }
        subtitleItems.add(SubtitleLine(currentText, SubtitleLine.CURRENT))
        for (n in nextParagraphs) {
            subtitleItems.add(SubtitleLine(n, SubtitleLine.NEXT))
        }

        renderSubtitleLines()
    }

    private fun renderSubtitleLines() {
        val container = binding.subtitleLines ?: return
        val context = requireContext()
        val textSizeSp = getSubtitleTextSize()
        val typeface = getSubtitleTypeface()
        val primaryColor = context.themeColor(androidx.appcompat.R.attr.colorPrimary)
        val onSurfaceVariant = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        // 构建目标文本列表，用于匹配已有视图
        val targetTexts = subtitleItems.map { it.text }
        val existingViews = (0 until container.childCount).map { container.getChildAt(it) as? TextView }

        // 找出需要移除的视图（文本不在目标列表中）
        val toRemove = mutableListOf<TextView>()
        for ((idx, tv) in existingViews.withIndex()) {
            if (tv != null && tv.text?.toString() !in targetTexts) {
                toRemove.add(tv)
            }
        }
        // 移除旧视图：带淡出上滑动画
        for (tv in toRemove) {
            container.removeView(tv)
        }
        // 对退出的旧视图做动画（在 detached 前触发一次 layout pass 后再做）
        // 这里直接通过 alpha 变化来淡出，但因为是同步 removeAll，改为不先 remove，用动画回调
        // 更简洁做法：先不清除，diff 后再处理

        // 重新使用 diff 方式构建
        // 先清理不在目标中的 view（带动画）
        val viewsToFadeOut = mutableListOf<TextView>()
        val remainingViews = mutableListOf<TextView>()
        for (i in 0 until container.childCount) {
            val tv = container.getChildAt(i) as? TextView ?: continue
            if (tv.text?.toString() !in targetTexts) {
                viewsToFadeOut.add(tv)
            } else {
                remainingViews.add(tv)
            }
        }

        // 先移除所有 view 并重建，但用动画过渡
        // 实际做法：对于依然保留的 view，做属性动画过渡；新 view 从下方滑入
        // 这里采用"保留旧 view 位置，用动画过渡"的策略

        // 构建新的 views 列表
        val newTargets = mutableListOf<SubtitleLine>()
        for (item in subtitleItems) {
            newTargets.add(item)
        }

        // 检查是否有实质性变化（文本内容变化才算变化）
        val existingTexts = remainingViews.map { it.text?.toString() }
        val hasAnyChange = targetTexts != existingTexts ||
                toRemove.isNotEmpty() ||
                remainingViews.size != targetTexts.size

        // 如果没有变化，只做微调的 alpha/scale 刷新
        if (!hasAnyChange) {
            refreshSubtitleProperties(remainingViews, textSizeSp, typeface, primaryColor, onSurfaceVariant)
            scrollToCurrentLine(container)
            return
        }

        // 有变化：做全文重建，但对每个 view 用平滑动画过渡
        container.removeAllViews()

        // 当前句索引
        val currentIndex = subtitleItems.indexOfFirst { it.type == SubtitleLine.CURRENT }

        for ((index, item) in subtitleItems.withIndex()) {
            val tv = createSubtitleTextView(context, item, textSizeSp, typeface, primaryColor, onSurfaceVariant)
            container.addView(tv)

            val isNewCurrent = index == currentIndex
            val wasPreviousCurrent = subtitleItems.getOrNull(currentIndex)?.text == item.text

            if (isNewCurrent) {
                // 新当前句：从下方滑入并放大
                tv.translationY = 36f
                tv.scaleX = 0.85f
                tv.scaleY = 0.85f
                tv.alpha = 0.6f
                tv.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(500L)
                    .setStartDelay(80L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2.5f))
                    .start()
            } else if (item.type == SubtitleLine.PREVIOUS) {
                // 前一句：从当前位置向上微移 + 缩小
                tv.translationY = -8f
                tv.alpha = 0.7f
                tv.scaleX = 1.05f
                tv.scaleY = 1.05f
                val targetAlpha = calcPrevAlpha(subtitleItems, index)
                tv.animate()
                    .translationY(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .alpha(targetAlpha)
                    .setDuration(600L)
                    .setStartDelay(60L + index * 40L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                    .start()
            } else {
                // 下一句：从下方缓缓滑入
                val targetAlpha = calcNextAlpha(subtitleItems, index, currentIndex)
                tv.translationY = 28f
                tv.scaleX = 0.88f
                tv.scaleY = 0.88f
                tv.alpha = 0.4f
                tv.animate()
                    .translationY(0f)
                    .scaleX(0.88f)
                    .scaleY(0.88f)
                    .alpha(targetAlpha)
                    .setDuration(550L)
                    .setStartDelay(100L + (index - currentIndex.coerceAtLeast(0)) * 60L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f))
                    .start()
            }
        }

        // 等布局完成后滚动到当前行
        scrollToCurrentLine(container)
    }

    private fun createSubtitleTextView(
        context: android.content.Context,
        item: SubtitleLine,
        textSizeSp: Float,
        typeface: android.graphics.Typeface,
        primaryColor: Int,
        onSurfaceVariant: Int
    ): TextView {
        val tv = TextView(context)
        tv.text = item.text
        tv.gravity = android.view.Gravity.CENTER
        tv.maxLines = 3
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setLineSpacing(6f.dpToPx(), 1f)
        tv.setPadding(
            16.dpToPx().toInt(),
            12.dpToPx().toInt(),
            16.dpToPx().toInt(),
            12.dpToPx().toInt()
        )
        tv.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tv.setTypeface(typeface, android.graphics.Typeface.NORMAL)

        // 设置初始属性
        applySubtitleStyle(tv, item, textSizeSp, typeface, primaryColor, onSurfaceVariant)
        return tv
    }

    private fun applySubtitleStyle(
        tv: TextView,
        item: SubtitleLine,
        textSizeSp: Float,
        typeface: android.graphics.Typeface,
        primaryColor: Int,
        onSurfaceVariant: Int
    ) {
        when (item.type) {
            SubtitleLine.CURRENT -> {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp + 4f)
                tv.setTextColor(primaryColor)
                tv.setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            SubtitleLine.PREVIOUS -> {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp - 2f)
                tv.setTextColor(onSurfaceVariant)
                tv.setTypeface(typeface, android.graphics.Typeface.NORMAL)
            }
            SubtitleLine.NEXT -> {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp - 2f)
                tv.setTextColor(onSurfaceVariant)
                tv.setTypeface(typeface, android.graphics.Typeface.NORMAL)
            }
            else -> {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                tv.setTextColor(onSurfaceVariant)
            }
        }
    }

    private fun calcPrevAlpha(items: List<SubtitleLine>, pos: Int): Float {
        val totalPrev = items.count { it.type == SubtitleLine.PREVIOUS }
        val posInPrev = items.take(pos).count { it.type == SubtitleLine.PREVIOUS }
        return if (totalPrev > 0) {
            (0.25f + 0.55f * (posInPrev + 1) / totalPrev).coerceIn(0.25f, 0.8f)
        } else 0.3f
    }

    private fun calcNextAlpha(items: List<SubtitleLine>, pos: Int, currentIndex: Int): Float {
        val totalNext = items.count { it.type == SubtitleLine.NEXT }
        val nextIdx = pos - currentIndex - 1
        return if (totalNext > 0) {
            (0.8f - 0.5f * (nextIdx + 1) / totalNext).coerceIn(0.3f, 0.8f)
        } else 0.3f
    }

    private fun refreshSubtitleProperties(
        views: List<TextView>,
        textSizeSp: Float,
        typeface: android.graphics.Typeface,
        primaryColor: Int,
        onSurfaceVariant: Int
    ) {
        for ((index, tv) in views.withIndex()) {
            val item = subtitleItems.getOrNull(index) ?: continue
            // 微调颜色/alpha（纯刷新，不需要动画）
            when (item.type) {
                SubtitleLine.CURRENT -> {
                    tv.setTextColor(primaryColor)
                    tv.alpha = 1f
                    tv.scaleX = 1f
                    tv.scaleY = 1f
                }
                SubtitleLine.PREVIOUS -> {
                    tv.setTextColor(onSurfaceVariant)
                    val alpha = calcPrevAlpha(subtitleItems, index)
                    tv.alpha = alpha
                    tv.scaleX = 0.85f
                    tv.scaleY = 0.85f
                }
                SubtitleLine.NEXT -> {
                    tv.setTextColor(onSurfaceVariant)
                    val currentIndex = subtitleItems.indexOfFirst { it.type == SubtitleLine.CURRENT }
                    val alpha = calcNextAlpha(subtitleItems, index, currentIndex)
                    tv.alpha = alpha
                    tv.scaleX = 0.88f
                    tv.scaleY = 0.88f
                }
                else -> {
                    tv.setTextColor(onSurfaceVariant)
                    tv.alpha = 0.5f
                }
            }
        }
    }

    private fun scrollToCurrentLine(container: android.view.ViewGroup) {
        val currentIndex = subtitleItems.indexOfFirst { it.type == SubtitleLine.CURRENT }
        binding.scrollView.post {
            if (currentIndex >= 0 && container.childCount > currentIndex) {
                val currentChild = container.getChildAt(currentIndex)
                val svHeight = binding.scrollView.height
                val paddingTop = binding.scrollView.paddingTop
                val childTop = currentChild.top
                val childHeight = currentChild.height
                val targetScroll = (childTop + childHeight / 2 - svHeight / 2 + paddingTop).coerceAtLeast(0)

                scrollAnimator?.cancel()
                scrollAnimator = ValueAnimator.ofInt(binding.scrollView.scrollY, targetScroll).apply {
                    duration = 600L
                    interpolator = android.view.animation.DecelerateInterpolator(2.5f)
                    addUpdateListener { animator ->
                        binding.scrollView.scrollTo(0, animator.animatedValue as Int)
                    }
                    start()
                }
            }
        }
    }

    private fun getSubtitleTypeface(): Typeface {
        val baseTypeface = getReadFontTypeface(ReadBookConfig.textFont)
        val textBold = ReadBookConfig.textBold
        val textItalic = ReadBookConfig.textItalic
        return when {
            textBold == 1 && textItalic -> Typeface.create(baseTypeface, Typeface.BOLD_ITALIC)
            textBold == 1 -> Typeface.create(baseTypeface, Typeface.BOLD)
            textBold == 2 && textItalic -> Typeface.create(baseTypeface, Typeface.ITALIC)
            else -> baseTypeface
        }
    }

    private fun getSubtitleTextSize(): Float {
        val subtitleFontSize = AppConfig.readAloudSubtitleFontSize
        return if (subtitleFontSize > 0) {
            subtitleFontSize.toFloat()
        } else {
            ReadBookConfig.textSize.toFloat()
        }
    }

    private fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { state ->
            upPlayState()
            // 播放状态变化时同步更新字幕和书籍信息
            updateSubtitle()
            updateBookInfo()
        }
        observeEvent<Int>(EventBus.TTS_PROGRESS) {
            // TTS 进度更新时刷新字幕
            updateSubtitle()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_PLAY) {
            // 朗读播放位置变化时更新字幕和书籍信息
            updateSubtitle()
            updateBookInfo()
        }
        // 监听主题变化事件
        observeEvent<String>(EventBus.RECREATE) {
            // 主题变化时更新颜色
            updateThemeColors()
            updateStatusBarColor()
        }
        // 监听封面尺寸变化事件
        observeEvent<String>(EventBus.READ_ALOUD_COVER_SIZE) {
            loadCover()
        }
        // 监听封面显隐变化事件
        observeEvent<String>(EventBus.READ_ALOUD_SHOW_COVER) {
            applyCoverVisibility()
        }
        // 监听字幕字体大小变化事件
        observeEvent<String>(EventBus.READ_ALOUD_SUBTITLE_FONT_SIZE) {
            initSubtitleStyle()
        }
        // 监听实时缓存进度
        observeEvent<TtsCacheProgress>(EventBus.TTS_CACHE_PROGRESS) { progress ->
            binding.tvDownloadProgress.apply {
                visibility = View.VISIBLE
                text = if (progress.total > 0) {
                    "正在缓存 ${progress.bookName}：第 ${progress.current}/${progress.total} 章"
                } else {
                    "正在缓存 ${progress.bookName}"
                }
            }
        }
    }

    private fun showAiBgMusicPlaybackConfig() {
        val items = listOf("设置", "频率", "播放列表", "AI 分析", "重新分析")
        context?.selector("背景音乐播放配置", items) { _, index ->
            when (index) {
                0 -> callBack?.openAiBgMusicSettings()
                1 -> callBack?.showAiBgMusicFrequency()
                2 -> callBack?.showAiBgMusicPlaylist()
                3 -> callBack?.showAiBgMusicAnalysis()
                4 -> callBack?.reanalyzeAiBgMusic()
            }
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun generateAudiobookCache()
        fun openAiBgMusicSettings()
        fun showAiBgMusicFrequency()
        fun showAiBgMusicPlaylist()
        fun showAiBgMusicAnalysis()
        fun reanalyzeAiBgMusic()
        fun finish()
    }
}
