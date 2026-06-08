package io.legado.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.help.LifecycleHelp
import io.legado.app.ui.main.ReadAloudMiniPlayer
import io.legado.app.ui.theme.AppThemeNoBackground

/**
 * 桌面悬浮朗读控件服务
 * 仅在应用退到后台时显示，复用 ReadAloudMiniPlayer 的 UI 与交互逻辑。
 * 通过 WindowManager 直接移动窗口来实现拖拽，避免全屏窗口阻挡触控。
 * 不复用前台通知，依赖同进程 BaseReadAloudService / AudioPlayService 的前台服务保活。
 */
class ReadAloudFloatService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var isAdded = false
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private var baseX = 0
    private var baseY = 0
    private var currentX = 0
    private var currentY = 0
    private val maxUpPx by lazy { (420 * resources.displayMetrics.density).toInt() }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startForegroundCheck()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopForegroundCheck()
        removeFloatView()
        super.onDestroy()
    }

    private fun startForegroundCheck() {
        handler.removeCallbacks(foregroundCheckRunnable)
        handler.post(foregroundCheckRunnable)
    }

    private fun stopForegroundCheck() {
        handler.removeCallbacks(foregroundCheckRunnable)
    }

    private val foregroundCheckRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying()) {
                removeFloatView()
                stopSelf()
                return
            }
            val shouldShow = !LifecycleHelp.isAppInForeground
            if (shouldShow && !isAdded) {
                showFloatView()
            } else if (!shouldShow && isAdded) {
                removeFloatView()
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun isPlaying(): Boolean {
        return AudioPlayService.isRun || BaseReadAloudService.isRun
    }

    private fun showFloatView() {
        if (isAdded) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val density = resources.displayMetrics.density
        baseX = (16 * density).toInt()
        // 初始位置：导航栏上方，再往上抬 1.5 个控件宽度 (68dp * 1.5 = 102dp)
        baseY = getNavBarHeight() + (20 * density).toInt() + (102 * density).toInt()
        currentX = baseX
        currentY = baseY

        composeView = ComposeView(this).also { cv ->
            cv.setViewTreeLifecycleOwner(this)
            cv.setViewTreeSavedStateRegistryOwner(this)
            cv.setContent {
                AppThemeNoBackground {
                    ReadAloudMiniPlayer(
                        enableOffset = false,
                        onDragDelta = { deltaY ->
                            currentY = (currentY - deltaY).toInt()
                            currentY = currentY.coerceIn(baseY, baseY + maxUpPx)
                            params.y = currentY
                            windowManager?.updateViewLayout(cv, params)
                        },
                        onTuckChange = { isTucked ->
                            currentX = if (isTucked) {
                                baseX - (40 * density).toInt()
                            } else {
                                baseX
                            }
                            params.x = currentX
                            windowManager?.updateViewLayout(cv, params)
                        }
                    )
                }
            }
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            x = currentX
            y = currentY
        }

        windowManager?.addView(composeView, params)
        isAdded = true
    }

    private fun removeFloatView() {
        if (isAdded && composeView != null) {
            try {
                windowManager?.removeView(composeView)
            } catch (_: Exception) { }
            isAdded = false
        }
        composeView = null
    }

    private fun getNavBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    companion object {

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: Exception) { }
            }
        }

        fun start(context: Context) {
            if (!canDrawOverlays(context)) {
                return
            }
            val intent = Intent(context, ReadAloudFloatService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadAloudFloatService::class.java))
        }
    }
}
