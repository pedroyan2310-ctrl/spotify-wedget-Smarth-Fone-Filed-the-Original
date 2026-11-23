package com.agoratocando.floatwidget

import android.graphics.Bitmap
import android.media.session.MediaController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Retrato de "o que esta tocando agora", montado a partir de um MediaController
 * do sistema (Spotify, YouTube Music, etc — qualquer app que publique uma
 * sessao de midia). E o equivalente, no Android, ao objeto que o widget do
 * Windows monta a partir do SMTC.
 */
data class NowPlayingState(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val album: String?,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val lastUpdateTimeMs: Long,
    val controller: MediaController
)

/**
 * Canal dentro do mesmo processo entre quem descobre a musica
 * (MediaNotificationListener) e quem desenha a bolha (OverlayService).
 * Os dois rodam no mesmo processo por padrao, entao um StateFlow resolve
 * sem precisar de AIDL/IPC nem do LocalBroadcastManager (depreciado).
 */
object NowPlayingBus {
    private val _state = MutableStateFlow<NowPlayingState?>(null)
    val state: StateFlow<NowPlayingState?> = _state.asStateFlow()

    fun update(newState: NowPlayingState?) {
        _state.value = newState
    }
}
