package com.agoratocando.floatwidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fundo "aurora": duas manchas de luz suaves (cor vibrante + cor muted da capa,
 * extraidas com Palette) que ficam derivando devagar atras do cartao expandido.
 * E o 3o "super efeito" — bem mais sutil que particulas/ripples, pensado pra
 * ficar atras do texto sem atrapalhar a leitura (por isso o scrim semi-transparente
 * por cima, ja no layout).
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var colorA = Color.parseColor("#B388FF")
    private var colorB = Color.parseColor("#00E5C7")
    private var running = false
    private val startTime = SystemClock.elapsedRealtime()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (running) postOnAnimation(this)
        }
    }

    /** Cores vindas da Palette da capa atual (vibrante + muted). Null = mantem a cor de fabrica. */
    fun setColors(vibrant: Int?, muted: Int?) {
        if (vibrant != null) colorA = vibrant
        if (muted != null) colorB = muted
        invalidate()
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        removeCallbacks(tick)
        if (running) postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val tSec = (SystemClock.elapsedRealtime() - startTime) / 1000f
        drawBlob(canvas, w, h, colorA, tSec * 0.12f)
        drawBlob(canvas, w, h, colorB, tSec * 0.09f + 0.5f)
    }

    private fun drawBlob(canvas: Canvas, w: Float, h: Float, color: Int, phaseTurns: Float) {
        val angle = phaseTurns * Math.PI * 2.0
        val cx = w / 2f + (cos(angle) * w * 0.30f).toFloat()
        val cy = h / 2f + (sin(angle * 1.3) * h * 0.26f).toFloat()
        val radius = maxOf(w, h) * 0.6f
        paint.shader = RadialGradient(
            cx, cy, radius,
            Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}

/**
 * 4o "super efeito": um brilho diagonal que varre o titulo da musica de tempos
 * em tempos — a mesma ideia de "shimmer" usada em telas de carregamento premium.
 * Funciona trocando o Shader do Paint do proprio TextView (sem precisar de uma
 * view/subclass nova): o degrade vai de branco -> cor de destaque -> branco, por
 * isso o texto continua sempre legivel, so ganha um brilho que passa por cima.
 */
object ShimmerHelper {
    private const val WIDTH_FACTOR = 0.55f // largura da faixa de brilho, em fracao da largura do texto
    private const val CYCLE_MS = 2600L // tempo pra faixa atravessar o texto uma vez

    fun attach(textView: TextView, highlightColor: Int): Runnable {
        val matrix = Matrix()
        var shader: LinearGradient? = null
        var shaderWidth = 0f
        val startTime = SystemClock.elapsedRealtime()

        val tick = object : Runnable {
            override fun run() {
                val w = textView.width.toFloat()
                if (w > 0f) {
                    if (shader == null || shaderWidth != w) {
                        shaderWidth = w
                        val bandWidth = w * WIDTH_FACTOR
                        shader = LinearGradient(
                            0f, 0f, bandWidth, 0f,
                            intArrayOf(Color.WHITE, highlightColor, Color.WHITE),
                            floatArrayOf(0f, 0.5f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        textView.paint.shader = shader
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startTime
                    val cycleProgress = (elapsedMs % CYCLE_MS).toFloat() / CYCLE_MS.toFloat()
                    // Anda de -largura ate +largura (TileMode.CLAMP segura branco fora da faixa,
                    // entao nao da pra ver "salto" nenhum quando reinicia o ciclo).
                    val travel = -w + cycleProgress * (w * 2f)
                    matrix.setTranslate(travel, 0f)
                    shader?.setLocalMatrix(matrix)
                    textView.invalidate()
                }
                textView.postOnAnimation(this)
            }
        }
        textView.postOnAnimation(tick)
        return tick
    }

    fun detach(textView: TextView, tick: Runnable) {
        textView.removeCallbacks(tick)
        textView.paint.shader = null
        textView.invalidate()
    }
}
