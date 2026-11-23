package com.agoratocando.floatwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.agoratocando.floatwidget.databinding.OverlayWidgetBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Desenha e controla a bolha flutuante. Fica rodando como foreground service
 * (precisa, pra Android deixar uma janela de overlay viva de forma confiavel)
 * e so reage ao que o MediaNotificationListener publica no NowPlayingBus —
 * nao sabe nada sobre Spotify/YouTube Music/etc especificamente.
 */
class OverlayService : LifecycleService() {

    companion object {
        private const val TAG = "Compasso"
        const val ACTION_STOP = "com.agoratocando.floatwidget.action.STOP"
        private const val CHANNEL_ID = "agora_tocando_overlay"
        private const val NOTIFICATION_ID = 1001
        // Maior que antes (era 6s): agora o cartao expandido pode mostrar letra,
        // e 6 segundos nao da nem pra ler uma linha direito.
        private const val AUTO_COLLAPSE_MS = 15000L
        private const val TAP_MAX_MOVEMENT_PX = 18f
        private const val TAP_MAX_DURATION_MS = 300L
        private const val DEFAULT_ACCENT = "#B388FF"
        private const val SHIMMER_COLOR = "#00E5C7"
        private const val BLUR_RADIUS_DP = 24f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var binding: OverlayWidgetBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { setExpanded(false) }

    private var expanded = false
    private var lastState: NowPlayingState? = null
    private var lastArtBitmap: Bitmap? = null
    private var lastBlurArtBitmap: Bitmap? = null
    private var springX: SpringAnimation? = null
    private var shimmerTick: Runnable? = null

    // Letra sincronizada do cartao expandido
    private var lyricsJob: Job? = null
    private var lyricsTrackKey: String? = null
    private var lyricsResult: LyricsResult? = null
    private var lastLyricsLineIndex = -1

    // estado do arraste
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var downTime = 0L
    private var dragging = false

    private val expandedTick = object : Runnable {
        override fun run() {
            lastState?.let {
                updateProgressFillNow(it)
                updateLyricsLine(it)
            }
            if (expanded && lastState?.isPlaying == true) {
                binding.overlayRoot.postOnAnimation(this)
            }
        }
    }

    private val windowXProperty = object : FloatPropertyCompat<View>("overlayWindowX") {
        override fun getValue(view: View): Float = layoutParams.x.toFloat()
        override fun setValue(view: View, value: Float) {
            layoutParams.x = value.toInt()
            safeUpdateViewLayout()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Tudo que monta a bolha fica num try/catch bem largo de proposito: isso
        // nunca foi testado num aparelho real antes, entao se algo especifico do
        // fabricante do celular der errado, o servico so desiste silenciosamente
        // (e grava o motivo no Logcat) em vez de derrubar o app inteiro em loop.
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            if (!Settings.canDrawOverlays(this)) {
                // Sem essa permissao a bolha nao tem como ser desenhada; nao ha o
                // que fazer alem de encerrar (a MainActivity so deixa ativar o
                // servico com a permissao ja concedida — isso aqui e so defesa).
                Log.w(TAG, "Permissao de overlay ausente ao iniciar o servico")
                stopSelf()
                return
            }

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            binding = OverlayWidgetBinding.inflate(LayoutInflater.from(this))
            binding.overlayRoot.visibility = View.GONE

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 300
            }

            windowManager.addView(binding.overlayRoot, layoutParams)

            setupTouchHandling()
            setupControls()

            try {
                shimmerTick = ShimmerHelper.attach(binding.expandedTitle, Color.parseColor(SHIMMER_COLOR))
            } catch (e: Exception) {
                // So o brilho no titulo e puramente cosmetico — se por algum motivo
                // falhar num aparelho especifico, a bolha continua funcionando normal.
                Log.w(TAG, "Nao foi possivel ligar o brilho do titulo", e)
            }

            lifecycleScope.launch {
                NowPlayingBus.state.collect { state ->
                    try {
                        onStateChanged(state)
                    } catch (e: Exception) {
                        Log.e(TAG, "Falha ao atualizar a bolha", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar a bolha flutuante", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Prefs.setWidgetEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        lyricsJob?.cancel()
        if (::binding.isInitialized) {
            binding.overlayRoot.removeCallbacks(expandedTick)
            shimmerTick?.let { ShimmerHelper.detach(binding.expandedTitle, it) }
        }
        springX?.cancel()
        if (::windowManager.isInitialized && ::binding.isInitialized) {
            try {
                windowManager.removeView(binding.overlayRoot)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    // --- Reage ao que esta tocando ---

    private fun onStateChanged(state: NowPlayingState?) {
        lastState = state
        if (state == null) {
            binding.overlayRoot.visibility = View.GONE
            return
        }
        binding.overlayRoot.visibility = View.VISIBLE

        binding.bubbleView.setArt(state.art)
        binding.bubbleView.setPlaybackInfo(
            state.isPlaying, state.positionMs, state.durationMs, state.playbackSpeed, state.lastUpdateTimeMs
        )

        binding.expandedTitle.text = state.title
        binding.expandedArtist.text = if (state.artist.isNotBlank()) state.artist else state.appLabel
        binding.expandedArt.setImageBitmap(state.art)
        binding.btnPlayPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        updateProgressFillNow(state)
        updatePaletteIfNeeded(state.art)
        updateArtBlur(state.art)
        updateLyricsIfNeeded(state)

        if (expanded && state.isPlaying) {
            binding.overlayRoot.removeCallbacks(expandedTick)
            binding.overlayRoot.postOnAnimation(expandedTick)
        }
        binding.expandedAurora.setRunning(expanded && state.isPlaying)
    }

    /** Capa borrada atras do cartao expandido — o efeito "vidro liquido" (so Android 12+). */
    private fun updateArtBlur(art: Bitmap?) {
        if (art === lastBlurArtBitmap) return
        lastBlurArtBitmap = art
        if (art == null) {
            binding.expandedBlurArt.visibility = View.GONE
            return
        }
        binding.expandedBlurArt.setImageBitmap(art)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val radiusPx = BLUR_RADIUS_DP * resources.displayMetrics.density
                binding.expandedBlurArt.setRenderEffect(
                    RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                )
                binding.expandedBlurArt.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Alguns fabricantes restringem efeitos de GPU em modo de economia de
                // energia. Sem blur, o cartao ainda fica bonito (aurora + scrim por
                // cima do fundo escuro normal) — so nao perde funcionalidade nenhuma.
                Log.w(TAG, "RenderEffect de blur falhou, seguindo sem capa borrada", e)
                binding.expandedBlurArt.visibility = View.GONE
            }
        } else {
            // Abaixo do Android 12 (API 31) o sistema nao tem RenderEffect — a aurora
            // e o scrim sozinhos ja dao um visual "vivo" sem precisar de blur.
            binding.expandedBlurArt.visibility = View.GONE
        }
    }

    // --- Letra sincronizada (karaoke de 3 linhas: anterior / atual / proxima) ---

    private fun lyricsKey(title: String, artist: String) = "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun updateLyricsIfNeeded(state: NowPlayingState) {
        val key = lyricsKey(state.title, state.artist)
        if (key == lyricsTrackKey) return
        lyricsTrackKey = key
        lyricsResult = null
        lastLyricsLineIndex = -1
        showLyricsStatus(getString(R.string.lyrics_searching))

        lyricsJob?.cancel()
        lyricsJob = lifecycleScope.launch {
            val result = try {
                LyricsRepository.fetch(state.title, state.artist, state.album, state.durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao buscar letra de '${state.title}'", e)
                null
            }
            // A musica pode ter trocado de novo enquanto a busca rodava.
            if (lyricsTrackKey != key) return@launch

            val hasSynced = !result?.synced.isNullOrEmpty()
            val hasPlain = !result?.plain.isNullOrBlank()
            when {
                result == null || (!hasSynced && !hasPlain) -> {
                    lyricsResult = null
                    showLyricsStatus(getString(R.string.lyrics_not_found))
                }
                hasSynced -> {
                    lyricsResult = result
                    lastLyricsLineIndex = -1
                    // a linha certa aparece sozinha no proximo expandedTick, comparando
                    // o horario de cada linha com a posicao atual da musica
                }
                else -> {
                    lyricsResult = null
                    showLyricsStatus(result!!.plain.orEmpty())
                }
            }
        }
    }

    private fun showLyricsStatus(text: String) {
        binding.lyricsPrev.text = ""
        binding.lyricsCurrent.text = text
        binding.lyricsNext.text = ""
    }

    private fun updateLyricsLine(state: NowPlayingState) {
        val synced = lyricsResult?.synced ?: return
        if (synced.isEmpty()) return
        val posMs = ProgressMath.positionMs(
            state.positionMs, state.durationMs, state.isPlaying, state.playbackSpeed, state.lastUpdateTimeMs
        )
        var idx = synced.indexOfLast { it.timeMs <= posMs }
        if (idx < 0) idx = 0
        if (idx == lastLyricsLineIndex) return
        lastLyricsLineIndex = idx
        binding.lyricsPrev.text = synced.getOrNull(idx - 1)?.text.orEmpty()
        binding.lyricsCurrent.text = synced.getOrNull(idx)?.text.orEmpty()
        binding.lyricsNext.text = synced.getOrNull(idx + 1)?.text.orEmpty()
    }

    private fun updateProgressFillNow(state: NowPlayingState) {
        val trackWidth = binding.progressTrack.width
        if (trackWidth <= 0) return
        val fraction = ProgressMath.fraction(
            state.positionMs, state.durationMs, state.isPlaying, state.playbackSpeed, state.lastUpdateTimeMs
        )
        val params = binding.progressFill.layoutParams
        params.width = (trackWidth * fraction).toInt().coerceAtLeast(0)
        binding.progressFill.layoutParams = params
    }

    private fun updatePaletteIfNeeded(art: Bitmap?) {
        if (art == null) {
            if (lastArtBitmap != null) {
                lastArtBitmap = null
                val fallback = Color.parseColor(DEFAULT_ACCENT)
                applyAccentColor(fallback)
                binding.expandedAurora.setColors(fallback, Color.parseColor(SHIMMER_COLOR))
            }
            return
        }
        if (art === lastArtBitmap) return
        lastArtBitmap = art
        try {
            Palette.from(art).generate { palette ->
                val fallback = Color.parseColor(DEFAULT_ACCENT)
                val vibrant = palette?.let { it.getVibrantColor(it.getDominantColor(fallback)) } ?: fallback
                val muted = palette?.let { it.getMutedColor(it.getDarkMutedColor(fallback)) } ?: fallback
                applyAccentColor(vibrant)
                binding.expandedAurora.setColors(vibrant, muted)
            }
        } catch (_: Exception) {
            applyAccentColor(Color.parseColor(DEFAULT_ACCENT))
        }
    }

    private fun applyAccentColor(color: Int) {
        binding.bubbleView.setAccentColor(color)
        (binding.progressFill.background?.mutate() as? GradientDrawable)?.setColor(color)
    }

    // --- Toque: arrastar, soltar com mola, tocar pra abrir/fechar ---

    private fun setupTouchHandling() {
        binding.overlayRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    springX?.cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = layoutParams.x
                    downParamY = layoutParams.y
                    downTime = SystemClock.elapsedRealtime()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > TAP_MAX_MOVEMENT_PX || abs(dy) > TAP_MAX_MOVEMENT_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = downParamX + dx.toInt()
                        layoutParams.y = downParamY + dy.toInt()
                        safeUpdateViewLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val elapsed = SystemClock.elapsedRealtime() - downTime
                    if (!dragging && elapsed < TAP_MAX_DURATION_MS) {
                        setExpanded(!expanded)
                    } else if (dragging && !expanded) {
                        snapToNearestEdge()
                    }
                    resetAutoCollapseTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val viewWidth = binding.overlayRoot.width.takeIf { it > 0 } ?: 64
        val margin = 16
        val targetX = if (layoutParams.x + viewWidth / 2 < screenWidth / 2) {
            margin
        } else {
            screenWidth - viewWidth - margin
        }
        val spring = SpringAnimation(binding.overlayRoot, windowXProperty, targetX.toFloat())
        spring.spring.stiffness = SpringForce.STIFFNESS_LOW
        spring.spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        springX = spring
        spring.start()
    }

    private fun safeUpdateViewLayout() {
        if (::windowManager.isInitialized && ::binding.isInitialized) {
            try {
                windowManager.updateViewLayout(binding.overlayRoot, layoutParams)
            } catch (_: Exception) {
            }
        }
    }

    // --- Expandir / colapsar ---

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (value) {
            binding.bubbleView.visibility = View.GONE
            binding.expandedCard.visibility = View.VISIBLE
            binding.expandedCard.alpha = 0f
            binding.expandedCard.animate().alpha(1f).setDuration(180).start()
            binding.overlayRoot.removeCallbacks(expandedTick)
            binding.overlayRoot.postOnAnimation(expandedTick)
            binding.expandedAurora.setRunning(lastState?.isPlaying == true)
            resetAutoCollapseTimer()
        } else {
            binding.expandedCard.visibility = View.GONE
            binding.bubbleView.visibility = View.VISIBLE
            binding.bubbleView.alpha = 0f
            binding.bubbleView.animate().alpha(1f).setDuration(180).start()
            binding.expandedAurora.setRunning(false)
            mainHandler.removeCallbacks(autoCollapseRunnable)
        }
        binding.overlayRoot.post { safeUpdateViewLayout() }
    }

    private fun resetAutoCollapseTimer() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        if (expanded) {
            mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
        }
    }

    private fun setupControls() {
        binding.btnPlayPauseContainer.setOnClickListener {
            resetAutoCollapseTimer()
            val state = lastState ?: return@setOnClickListener
            if (state.isPlaying) state.controller.transportControls.pause()
            else state.controller.transportControls.play()
        }
        binding.btnPrev.setOnClickListener {
            resetAutoCollapseTimer()
            lastState?.controller?.transportControls?.skipToPrevious()
        }
        binding.btnNext.setOnClickListener {
            resetAutoCollapseTimer()
            lastState?.controller?.transportControls?.skipToNext()
        }
        binding.btnCollapse.setOnClickListener {
            setExpanded(false)
        }
    }

    // --- Notificacao obrigatoria do foreground service ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notification_stop_action), stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
