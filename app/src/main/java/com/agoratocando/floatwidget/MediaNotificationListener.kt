package com.agoratocando.floatwidget

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Nao le o conteudo das notificacoes de verdade — so usa o fato de estar
 * habilitado como "acesso a notificacoes" como a chave que o Android exige
 * pra poder chamar MediaSessionManager.getActiveSessions() e enxergar as
 * sessoes de midia de QUALQUER app (Spotify, YouTube Music, etc), sem login
 * e sem precisar de conta Premium. E o equivalente Android do SMTC do Windows.
 */
class MediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Compasso"
    }

    private var sessionManager: MediaSessionManager? = null
    private val controllers = mutableListOf<MediaController>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { newControllers ->
            rebuildControllers(newControllers ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Tudo aqui dentro depende de APIs que variam de fabricante pra fabricante
        // (MIUI/HyperOS em especial costuma se comportar diferente do Android puro),
        // entao e envolvido num try/catch bem largo: na pior das hipoteses a bolha
        // simplesmente nao aparece, em vez do app derrubar o processo inteiro.
        try {
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val manager = getSystemService(MediaSessionManager::class.java)
            sessionManager = manager
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            val active = manager.getActiveSessions(component)
            rebuildControllers(active)
        } catch (e: Exception) {
            // Mais comum: SecurityException (permissao ainda nao concedida de verdade).
            // Mas qualquer outra coisa tambem cai aqui, de proposito.
            Log.e(TAG, "Falha ao conectar ao MediaSessionManager", e)
            NowPlayingBus.update(null)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        clearControllers()
        NowPlayingBus.update(null)
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        sessionManager = null
    }

    override fun onDestroy() {
        clearControllers()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun rebuildControllers(newControllers: List<MediaController>) {
        clearControllers()
        controllers.addAll(newControllers)
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    publishBest()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    publishBest()
                }

                override fun onSessionDestroyed() {
                    controllers.remove(controller)
                    callbacks.remove(controller)
                    publishBest()
                }
            }
            callbacks[controller] = callback
            try {
                controller.registerCallback(callback)
            } catch (_: Exception) {
                // sessao pode ter morrido entre a lista chegar e o registro acontecer
            }
        }
        publishBest()
    }

    private fun clearControllers() {
        for ((controller, callback) in callbacks) {
            try {
                controller.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        callbacks.clear()
        controllers.clear()
    }

    /**
     * Mesma ideia do findBestSession() do worker do Windows: prioriza quem esta
     * tocando de verdade (o mais recente, se tiver mais de um), senao o ultimo
     * pausado, senao qualquer sessao com metadados. Sem nenhuma, esconde a bolha.
     */
    private fun publishBest() {
        // Chamado a partir de varios callbacks do sistema (troca de sessao, mudanca
        // de metadado/estado) — um try/catch aqui cobre todos esses pontos de uma vez.
        try {
            val best = pickBestController()
            NowPlayingBus.update(best?.let { buildState(it) })
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao processar sessao de midia", e)
        }
    }

    private fun pickBestController(): MediaController? {
        val withMetadata = controllers.filter { it.metadata != null }
        if (withMetadata.isEmpty()) return null

        val playing = withMetadata.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing.isNotEmpty()) {
            return playing.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
        }

        val paused = withMetadata.filter { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        if (paused.isNotEmpty()) {
            return paused.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
        }

        return withMetadata.first()
    }

    private fun buildState(controller: MediaController): NowPlayingState {
        val metadata = controller.metadata
        val playback = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Titulo desconhecido"

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: ""

        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)

        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        return NowPlayingState(
            packageName = controller.packageName ?: "",
            appLabel = labelFor(controller.packageName),
            title = title,
            artist = artist,
            album = album,
            art = art,
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionMs = playback?.position ?: 0L,
            durationMs = if (duration > 0L) duration else 0L,
            playbackSpeed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            lastUpdateTimeMs = playback?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            controller = controller
        )
    }

    private fun labelFor(packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
