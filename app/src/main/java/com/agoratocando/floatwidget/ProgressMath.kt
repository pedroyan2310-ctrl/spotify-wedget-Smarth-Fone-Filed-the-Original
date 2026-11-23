package com.agoratocando.floatwidget

import android.os.SystemClock

/**
 * Mesmo truque usado no widget do Windows (progressBase): em vez de depender
 * de uma atualizacao de posicao a cada frame (o MediaController so avisa de
 * vez em quando), guarda a ultima posicao conhecida + o horario dela e
 * interpola localmente com base no tempo decorrido. Resultado: o anel/barra
 * de progresso andam suaves a cada frame em vez de "pular" a cada atualizacao.
 */
object ProgressMath {
    /** Posicao atual interpolada, em milissegundos (usada pra achar a linha certa da letra). */
    fun positionMs(positionMs: Long, durationMs: Long, isPlaying: Boolean, speed: Float, lastUpdateTimeMs: Long): Long {
        val safeSpeed = if (speed > 0f) speed else 1f
        val elapsed = if (isPlaying) {
            (SystemClock.elapsedRealtime() - lastUpdateTimeMs) * safeSpeed
        } else {
            0f
        }
        val pos = positionMs + elapsed
        val cap = if (durationMs > 0L) durationMs.toFloat() else Float.MAX_VALUE
        return pos.coerceIn(0f, cap).toLong()
    }

    fun fraction(positionMs: Long, durationMs: Long, isPlaying: Boolean, speed: Float, lastUpdateTimeMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val pos = positionMs(positionMs, durationMs, isPlaying, speed, lastUpdateTimeMs)
        return pos.toFloat() / durationMs.toFloat()
    }
}
