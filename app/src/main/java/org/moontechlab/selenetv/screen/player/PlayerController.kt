package org.moontechlab.selenetv.screen.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.MediaHttp

/**
 * 封装 ExoPlayer，实现 VideoPlayer。位置/时长用毫秒。位置由调用方周期 [syncProgress] 拉取。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerController(
    context: Context,
    dataSourceFactory: androidx.media3.datasource.DataSource.Factory = MediaHttp.dataSourceFactory,
    decodeMode: AppDataService.DecodeMode = AppDataService.DecodeMode.HARDWARE,
) : VideoPlayer {

    private companion object { const val TAG = "Selene.ExoPlayer" }

    /** 若传入的工厂是去广告工厂，保留引用以便兜底时置 bypass 直连原始链接。 */
    private val adFilterFactory = dataSourceFactory as? org.moontechlab.selenetv.service.adfilter.AdFilterDataSource.Factory

    // 兜底重试状态：过滤生效时若播放失败，跳过过滤用原始链接直连重试一次
    private var lastLoadUrl: String? = null
    private var lastLoadPos = 0L
    private var lastLoadAuto = false
    private var fallbackUsed = false

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .apply {
            // 软解：用把系统软件解码器排前的 RenderersFactory；硬解保持默认。
            if (decodeMode == AppDataService.DecodeMode.SOFTWARE) {
                setRenderersFactory(object : DefaultRenderersFactory(context) {
                    override fun buildVideoRenderers(
                        ctx: Context,
                        extensionRendererMode: Int,
                        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                        enableDecoderFallback: Boolean,
                        eventHandler: android.os.Handler,
                        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                        allowedVideoJoiningTimeMs: Long,
                        out: ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) {
                        super.buildVideoRenderers(
                            ctx, extensionRendererMode, SoftwareFirstSelector,
                            enableDecoderFallback, eventHandler, eventListener,
                            allowedVideoJoiningTimeMs, out,
                        )
                    }
                })
            }
        }
        .build()

    override var positionMs by mutableLongStateOf(0L); private set
    override var durationMs by mutableLongStateOf(0L); private set
    override var bufferedMs by mutableLongStateOf(0L); private set
    override var isPlaying by mutableStateOf(false); private set
    override var isBuffering by mutableStateOf(false); private set
    override var error by mutableStateOf<String?>(null); private set
    override var hasStarted by mutableStateOf(false); private set
    override var onEnded: () -> Unit = {}
    override var speed: Float = 1f
        set(value) {
            field = value
            exo.setPlaybackSpeed(value)
        }

    private var playerView: PlayerView? = null

    override var resizeMode: VideoResizeMode = VideoResizeMode.FIT
        set(value) {
            field = value
            playerView?.resizeMode = exoResizeMode(value)
        }

    private fun exoResizeMode(mode: VideoResizeMode): Int = when (mode) {
        VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { hasStarted = true; val d = exo.duration; if (d > 0) durationMs = d }
            if (state == Player.STATE_ENDED) onEnded()
        }
        override fun onPlayerError(e: PlaybackException) {
            // 兜底：广告过滤后播放失败 → 置 bypass 直连原始链接重试一次；仍失败才报错。
            val url = lastLoadUrl
            val f = adFilterFactory
            if (url != null && f != null && !fallbackUsed && !f.bypass) {
                fallbackUsed = true
                f.bypass = true
                android.util.Log.w(TAG, "播放失败(${e.errorCodeName})，跳过广告过滤直连原始链接重试")
                loadInternal(url, lastLoadPos, lastLoadAuto)
                return
            }
            error = e.errorCodeName
        }
    }

    init { exo.addListener(listener) }

    @MainThread
    override fun load(url: String, startMs: Long, playWhenReady: Boolean) {
        // 新的一次播放：重置兜底状态（过滤恢复生效）
        fallbackUsed = false
        adFilterFactory?.bypass = false
        loadInternal(url, startMs, playWhenReady)
    }

    @MainThread
    private fun loadInternal(url: String, startMs: Long, playWhenReady: Boolean) {
        error = null
        hasStarted = false
        lastLoadUrl = url
        lastLoadPos = startMs
        lastLoadAuto = playWhenReady
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = playWhenReady
        syncProgress()
    }

    @MainThread
    override fun seekTo(ms: Long) { exo.seekTo(ms.coerceAtLeast(0)); syncProgress() }

    @MainThread
    override fun togglePlay() { exo.playWhenReady = !exo.playWhenReady }

    @MainThread
    override fun pause() { exo.playWhenReady = false }

    @MainThread
    override fun seekToLiveEdge() { exo.seekToDefaultPosition() }

    @MainThread
    override fun syncProgress() {
        positionMs = exo.currentPosition.coerceAtLeast(0)
        bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        val d = exo.duration
        if (d > 0) durationMs = d
    }

    @MainThread
    override fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        // texture_view 背景的 PlayerView：视频随覆盖层一起淡出（见 R.layout.player_view_texture）。
        AndroidView(
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx)
                    .inflate(org.moontechlab.selenetv.R.layout.player_view_texture, null) as PlayerView)
                    .apply {
                        useController = false
                        player = exo
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        resizeMode = exoResizeMode(this@PlayerController.resizeMode)
                    }
                    .also { playerView = it }
            },
            onRelease = { playerView = null },
            modifier = modifier,
        )
    }
}
