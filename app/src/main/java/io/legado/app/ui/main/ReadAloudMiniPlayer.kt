package io.legado.app.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.model.BookCover
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.AudioPlayService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.config.coverConfig.CoverConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.image.cover.buildCoverImageRequest
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.eventObservable
import io.legado.app.utils.startActivityForBook
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * 播放类型：TTS朗读 或 有声书
 */
enum class PlayType {
    TTS,
    AUDIO_BOOK
}

@Composable
fun ReadAloudMiniPlayer(
    modifier: Modifier = Modifier,
    enableOffset: Boolean = true,
    onDragDelta: ((Float) -> Unit)? = null,
    onTuckChange: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 当前播放类型：TTS 或 有声书
    var playType by remember { mutableStateOf(PlayType.TTS) }
    var status by remember {
        mutableIntStateOf(
            when {
                AudioPlayService.isRun -> if (AudioPlayService.pause) Status.PAUSE else Status.PLAY
                BaseReadAloudService.isRun -> if (BaseReadAloudService.pause) Status.PAUSE else Status.PLAY
                else -> Status.STOP
            }
        )
    }
    var book by remember { mutableStateOf(AudioPlay.book ?: ReadBook.book) }
    var chapterTitle by remember { mutableStateOf(AudioPlay.durChapter?.title ?: ReadBook.curTextChapter?.title.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    var tucked by rememberSaveable { mutableStateOf(false) }
    var verticalOffsetPx by rememberSaveable { mutableFloatStateOf(0f) }
    val maxUpOffsetPx = with(density) { 420.dp.toPx() }
    val tuckOffsetPx = with(density) { 40.dp.toPx() }

    LaunchedEffect(tucked) {
        onTuckChange?.invoke(tucked)
    }

    fun syncTtsState() {
        if (!AudioPlayService.isRun && BaseReadAloudService.isRun) {
            playType = PlayType.TTS
            status = if (BaseReadAloudService.pause) Status.PAUSE else Status.PLAY
            book = ReadBook.book
            chapterTitle = ReadBook.curTextChapter?.title.orEmpty()
        }
    }

    fun syncAudioBookState() {
        if (AudioPlayService.isRun) {
            playType = PlayType.AUDIO_BOOK
            status = if (AudioPlayService.pause) Status.PAUSE else Status.PLAY
            book = AudioPlay.book
            chapterTitle = AudioPlay.durChapter?.title ?: ""
        } else {
            // 有声书停止后，检测TTS是否正在播放
            syncTtsState()
            // 如果TTS也没在播放，则停止
            if (!BaseReadAloudService.isRun) {
                status = Status.STOP
                playType = PlayType.TTS
                book = ReadBook.book
                chapterTitle = ReadBook.curTextChapter?.title.orEmpty()
                expanded = false
                tucked = false
            }
        }
    }

    fun syncState(eventState: Int? = null) {
        // 优先同步有声书状态
        if (AudioPlayService.isRun) {
            syncAudioBookState()
        } else if (BaseReadAloudService.isRun) {
            syncTtsState()
        } else {
            status = Status.STOP
            playType = PlayType.TTS
            book = ReadBook.book
            chapterTitle = ReadBook.curTextChapter?.title.orEmpty()
        }
        if (status == Status.STOP) {
            expanded = false
            tucked = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        syncState()
        val stateObserver = Observer<Int> { state ->
            syncState(state)
        }
        val progressObserver = Observer<Int> {
            syncState()
        }
        val audioStateObserver = Observer<Int> {
            syncAudioBookState()
        }
        val audioProgressObserver = Observer<Int> {
            syncAudioBookState()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncState()
            }
        }
        // TTS 事件
        eventObservable<Int>(EventBus.ALOUD_STATE).observe(lifecycleOwner, stateObserver)
        eventObservable<Int>(EventBus.TTS_PROGRESS).observe(lifecycleOwner, progressObserver)
        // 有声书事件
        eventObservable<Int>(EventBus.AUDIO_STATE).observe(lifecycleOwner, audioStateObserver)
        eventObservable<Int>(EventBus.AUDIO_PROGRESS).observe(lifecycleOwner, audioProgressObserver)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            if (status == Status.STOP) {
                expanded = false
            }
        }
    }

    val currentBook = book
    val currentPlayType = playType
    if (status == Status.STOP || currentBook == null) return

    val isPlaying = status == Status.PLAY
    val playerModifier = modifier
        .then(
            if (enableOffset) {
                Modifier.offset {
                    IntOffset(
                        x = if (tucked) tuckOffsetPx.roundToInt() else 0,
                        y = verticalOffsetPx.roundToInt()
                    )
                }
            } else Modifier
        )
        .pointerInput(maxUpOffsetPx, tucked, enableOffset) {
            detectDragGestures(
                onDragStart = {
                    if (tucked) tucked = false
                }
            ) { change, dragAmount ->
                change.consume()
                if (enableOffset) {
                    verticalOffsetPx = (verticalOffsetPx + dragAmount.y)
                        .coerceIn(-maxUpOffsetPx, 0f)
                }
                onDragDelta?.invoke(dragAmount.y)
            }
        }
        .then(if (expanded) Modifier else Modifier.size(68.dp))
        .animateContentSize()

    if (expanded) {
        Surface(
            modifier = playerModifier,
            shape = RoundedCornerShape(28.dp),
            color = LegadoTheme.colorScheme.surfaceContainerHigh,
            contentColor = LegadoTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            ExpandedReadAloudMiniPlayer(
                book = currentBook,
                chapterTitle = chapterTitle,
                isPlaying = isPlaying,
                playType = currentPlayType,
                onTogglePlay = {
                    if (currentPlayType == PlayType.AUDIO_BOOK) {
                        if (isPlaying) {
                            AudioPlay.pause(context)
                        } else {
                            AudioPlay.resume(context)
                        }
                    } else {
                        if (isPlaying) {
                            ReadAloud.pause(context)
                        } else {
                            ReadAloud.resume(context)
                        }
                    }
                },
                onStop = {
                    if (currentPlayType == PlayType.AUDIO_BOOK) {
                        AudioPlay.stop()
                    } else {
                        ReadAloud.stop(context)
                    }
                    expanded = false
                    tucked = false
                },
                onTuck = {
                    expanded = false
                    tucked = true
                },
                onOpenReader = {
                    context.startActivityForBook(currentBook)
                    expanded = false
                    tucked = false
                }
            )
        }
    } else {
        Box(
            modifier = playerModifier
                .clip(CircleShape)
                .clickable {
                    tucked = false
                    expanded = true
                },
            contentAlignment = Alignment.Center
        ) {
            RotatingCoverDisc(
                book = currentBook,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ExpandedReadAloudMiniPlayer(
    book: Book,
    chapterTitle: String,
    isPlaying: Boolean,
    playType: PlayType,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onTuck: () -> Unit,
    onOpenReader: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(start = 10.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onOpenReader),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RotatingCoverDisc(
                book = book,
                isPlaying = isPlaying,
                modifier = Modifier.size(52.dp)
            )
            Column(
                modifier = Modifier.width(112.dp)
            ) {
                AppText(
                    text = book.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AppText(
                    text = when {
                        chapterTitle.isNotBlank() -> chapterTitle
                        playType == PlayType.AUDIO_BOOK -> stringResource(R.string.audio_play)
                        else -> stringResource(R.string.read_aloud)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(1.dp))
        IconButton(onClick = onTogglePlay) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.PauseCircleOutline else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.resume)
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.stop)
            )
        }
        IconButton(onClick = onTuck) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "收回侧边"
            )
        }
    }
}

@Composable
private fun RotatingCoverDisc(
    book: Book,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = koinInject()
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "readAloudCoverRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "readAloudCoverRotationValue"
    )

    // 获取封面路径，与书籍详情页保持一致
    val displayCover = book.getDisplayCover()
    val useDefaultCover = CoverConfig.useDefaultCover
    val hasCover = !displayCover.isNullOrBlank()

    // 获取随机默认封面，与 CoilBookCover 保持一致
    val randomDefaultBitmap = remember(book.name, book.author) {
        BookCover.getRandomDefaultDrawable(
            seed = book.name ?: book.author ?: "",
            isNight = false
        )?.let { drawable ->
            val bitmap = android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }

    // 跟踪在线封面是否加载成功，与 CoilBookCover 保持一致
    var isOnlineCoverLoaded by remember(displayCover) { mutableStateOf(false) }

    // 与 CoilBookCover 保持一致的封面显示逻辑
    // 优先级：书籍封面 > 随机默认封面 > Book图标
    val showBookCover = !useDefaultCover && hasCover
    val showFallback = !showBookCover || !isOnlineCoverLoaded

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(LegadoTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .graphicsLayer {
                rotationZ = if (isPlaying) rotation else 0f
            },
        contentAlignment = Alignment.Center
    ) {
        // 随机默认封面或 Book 图标（作为备用）
        if (showFallback && randomDefaultBitmap != null) {
            Image(
                bitmap = randomDefaultBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (showFallback) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

        // 书籍封面（优先显示）
        AnimatedVisibility(
            visible = showBookCover,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AsyncImage(
                model = buildCoverImageRequest(
                    context = context,
                    data = displayCover,
                    sourceOrigin = book.origin,
                    loadOnlyWifi = false,
                    crossfade = false
                ),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = {
                    isOnlineCoverLoaded = true
                },
                onError = {
                    isOnlineCoverLoaded = false
                }
            )
        }
    }
}
