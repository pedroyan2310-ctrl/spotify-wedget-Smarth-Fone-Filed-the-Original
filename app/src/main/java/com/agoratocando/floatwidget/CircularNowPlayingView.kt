package com.agoratocando.floatwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * A "bolha" flutuante: capa do album em circulo, um anel de progresso desenhado
 * na mao (sem precisar de nenhuma lib de progress bar) e um brilho suave atras,
 * tingido com a cor dominante da capa. E o visual proprio deste app — nada de
 * card retangular como no widget do Windows.
 */
class CircularNowPlayingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Cores "de fabrica", batendo com colors.xml (duplicadas aqui de proposito:
    // esta view e autossuficiente e nao depende de contexto de tema/recursos).
    private var accentColor = Color.parseColor("#B388FF")
    private val trackColor = Color.parseColor("#363636")

    private var art: Bitmap? = null
    private var artShader: BitmapShader? = null
    private var shaderSourceBitmap: Bitmap? = null

    private var isPlayingState = false
    private var posBaseMs = 0L
    private var durationMsState = 0L
    private var speedState = 1f
    private var baseRealtimeMs = 0L

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fallbackGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 230
    }
    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // --- Particulas orbitando + aneis de pulso ("super efeitos" 1 e 2) ---
    // Tudo calculado a partir do relogio do sistema (sem AnimatorSet/ValueAnimator),
    // seguindo o mesmo espirito do ProgressMath: deterministico, sem estado pra
    // guardar entre frames alem do horario de inicio.
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particleCount = 6
    // Raios/tamanhos levemente diferentes por particula so pra nao ficar um
    // circulo perfeito e artificial — pequenas variacoes "organicas".
    private val particleRadiusFactor = FloatArray(particleCount) { 0.460f + (it % 3) * 0.010f }
    private val particleSizeFactor = FloatArray(particleCount) { 0.014f + (it % 2) * 0.006f }
    private val particleSpeedDegPerSec = FloatArray(particleCount) { 22f + (it % 4) * 9f }
    private val particleStartPhaseDeg = FloatArray(particleCount) { it * (360f / particleCount) }

    private val ripples = mutableListOf<Long>() // horarios (elapsedRealtime) em que cada anel nasceu
    private var lastRippleSpawnMs = 0L
    private val rippleIntervalMs = 1400L
    private val rippleLifetimeMs = 1100L

    private val frameTick = object : Runnable {
        override fun run() {
            invalidate()
            if (isPlayingState) postOnAnimation(this)
        }
    }

    init {
        ringTrackPaint.color = trackColor
        ringPaint.color = accentColor
        fallbackPaint.color = accentColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Forca a view a ser sempre quadrada, nao importa onde for usada.
        val size = minOf(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }

    fun setArt(bitmap: Bitmap?) {
        if (art === bitmap) return
        art = bitmap
        if (bitmap == null) {
            artShader = null
            shaderSourceBitmap = null
        }
        invalidate()
    }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        ringPaint.color = color
        fallbackPaint.color = color
        invalidate()
    }

    fun setPlaybackInfo(isPlaying: Boolean, positionMs: Long, durationMs: Long, speed: Float, lastUpdateTimeMs: Long) {
        isPlayingState = isPlaying
        posBaseMs = positionMs
        durationMsState = durationMs
        speedState = if (speed > 0f) speed else 1f
        baseRealtimeMs = lastUpdateTimeMs
        invalidate()
        removeCallbacks(frameTick)
        if (isPlayingState) postOnAnimation(frameTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    private fun currentFraction(): Float =
        ProgressMath.fraction(posBaseMs, durationMsState, isPlayingState, speedState, baseRealtimeMs)

    private fun ensureArtShader(diameter: Float) {
        val bmp = art ?: return
        if (artShader != null && shaderSourceBitmap === bmp) return
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val minSide = minOf(bmp.width, bmp.height).toFloat()
        if (minSide <= 0f) return
        val scale = diameter / minSide
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale
        val left = width / 2f - diameter / 2f - (scaledW - diameter) / 2f
        val top = height / 2f - diameter / 2f - (scaledH - diameter) / 2f
        matrix.postTranslate(left, top)
        shader.setLocalMatrix(matrix)
        artShader = shader
        shaderSourceBitmap = bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = width.toFloat()
        if (size <= 0f) return
        val cx = size / 2f
        val cy = size / 2f

        // Brilho suave atras de tudo, na cor da capa
        val glowRadius = size * 0.5f
        val glowInner = Color.argb(90, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        glowPaint.shader = RadialGradient(cx, cy, glowRadius, glowInner, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // Capa do album (ou um icone simples se nao tiver capa)
        val artRadius = size * 0.34f
        if (art != null) {
            ensureArtShader(artRadius * 2f)
            if (artShader != null) {
                artPaint.shader = artShader
                canvas.drawCircle(cx, cy, artRadius, artPaint)
            } else {
                canvas.drawCircle(cx, cy, artRadius, fallbackPaint)
                drawFallbackGlyph(canvas, cx, cy, artRadius)
            }
        } else {
            canvas.drawCircle(cx, cy, artRadius, fallbackPaint)
            drawFallbackGlyph(canvas, cx, cy, artRadius)
        }

        // Trilho + arco de progresso
        val ringRadius = size * 0.42f
        val strokeW = size * 0.06f
        ringTrackPaint.strokeWidth = strokeW
        canvas.drawCircle(cx, cy, ringRadius, ringTrackPaint)

        ringPaint.strokeWidth = strokeW
        val sweep = 360f * currentFraction().coerceIn(0f, 1f)
        if (sweep > 0f) {
            val rect = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            canvas.drawArc(rect, -90f, sweep, false, ringPaint)
        }

        // So anima enquanto esta tocando — parado, a bolha fica "quieta" de proposito
        // (reforca que esses efeitos reagem ao estado da reproducao, nao sao so decoracao).
        if (isPlayingState) {
            maybeSpawnRipple()
            drawRipples(canvas, cx, cy, ringRadius, maxRippleRadius = size * 0.49f)
            drawParticles(canvas, cx, cy, size)
        }
    }

    private fun maybeSpawnRipple() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRippleSpawnMs >= rippleIntervalMs) {
            lastRippleSpawnMs = now
            ripples.add(now)
        }
        if (ripples.isNotEmpty()) {
            ripples.removeAll { now - it > rippleLifetimeMs }
        }
    }

    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, maxRippleRadius: Float) {
        if (ripples.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        ripplePaint.strokeWidth = maxRippleRadius * 0.03f
        for (startTime in ripples) {
            val progress = ((now - startTime).toFloat() / rippleLifetimeMs).coerceIn(0f, 1f)
            val radius = baseRadius + (maxRippleRadius - baseRadius) * progress
            val alpha = (140 * (1f - progress)).toInt().coerceIn(0, 140)
            if (alpha <= 0) continue
            ripplePaint.color = accentColor
            ripplePaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, ripplePaint)
        }
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val tSec = SystemClock.elapsedRealtime() / 1000f
        for (i in 0 until particleCount) {
            val angleDeg = particleStartPhaseDeg[i] + tSec * particleSpeedDegPerSec[i]
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val radius = size * particleRadiusFactor[i]
            val px = cx + (cos(angleRad) * radius).toFloat()
            val py = cy + (sin(angleRad) * radius).toFloat()
            // Pisca suavemente (cada particula fora de fase das outras) pra parecer
            // "vivo" em vez de um ponto fixo girando.
            val twinkle = 0.5f + 0.5f * sin(angleRad * 1.7 + i * 1.3).toFloat()
            particlePaint.color = accentColor
            particlePaint.alpha = (70 + 110 * twinkle).toInt().coerceIn(40, 180)
            canvas.drawCircle(px, py, size * particleSizeFactor[i], particlePaint)
        }
    }

    private fun drawFallbackGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val glyphSize = radius * 0.65f
        if (isPlayingState) {
            // duas barrinhas = pausa (ja que esta tocando)
            val barW = glyphSize * 0.28f
            val barH = glyphSize * 1.1f
            val gap = glyphSize * 0.22f
            canvas.drawRect(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f, fallbackGlyphPaint)
            canvas.drawRect(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f, fallbackGlyphPaint)
        } else {
            // triangulo = play
            val path = Path()
            path.moveTo(cx - glyphSize * 0.45f, cy - glyphSize * 0.6f)
            path.lineTo(cx - glyphSize * 0.45f, cy + glyphSize * 0.6f)
            path.lineTo(cx + glyphSize * 0.65f, cy)
            path.close()
            canvas.drawPath(path, fallbackGlyphPaint)
        }
    }
}
