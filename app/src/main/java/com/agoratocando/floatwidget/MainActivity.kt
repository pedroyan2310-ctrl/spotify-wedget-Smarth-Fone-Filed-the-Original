package com.agoratocando.floatwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.agoratocando.floatwidget.databinding.ActivityMainBinding

/**
 * Tela unica do app: explica as duas permissoes, deixa o usuario ativa-las nas
 * Configuracoes do sistema (sao permissoes especiais, nao tem dialogo nativo)
 * e liga/desliga a bolha flutuante. Nao mostra nenhum dado de musica —
 * quem faz isso e a bolha, desenhada pelo OverlayService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bolha de exemplo, so de enfeite nesta tela — nenhum dado real.
        binding.previewBubble.setPlaybackInfo(
            isPlaying = true,
            positionMs = 70_000L,
            durationMs = 200_000L,
            speed = 1f,
            lastUpdateTimeMs = SystemClock.elapsedRealtime()
        )

        binding.btnNotifAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnOverlayAction.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        binding.btnStartStop.setOnClickListener {
            toggleWidget()
        }
    }

    override fun onResume() {
        super.onResume()
        // As duas permissoes so tem tela de Configuracoes (sem callback de retorno),
        // entao o jeito certo de saber se foram concedidas e reconferir aqui.
        refreshState()
    }

    private fun refreshState() {
        val notifGranted = isNotificationAccessGranted()
        val overlayGranted = Settings.canDrawOverlays(this)

        binding.btnNotifAction.isEnabled = !notifGranted
        binding.btnNotifAction.text = getString(
            if (notifGranted) R.string.perm_notification_granted else R.string.perm_notification_action
        )

        binding.btnOverlayAction.isEnabled = !overlayGranted
        binding.btnOverlayAction.text = getString(
            if (overlayGranted) R.string.perm_overlay_granted else R.string.perm_overlay_action
        )

        val bothGranted = notifGranted && overlayGranted
        binding.btnStartStop.isEnabled = bothGranted

        if (!bothGranted && Prefs.isWidgetEnabled(this)) {
            // Perdeu alguma permissao no meio do caminho: nao da pra manter a bolha ativa.
            Prefs.setWidgetEnabled(this, false)
        }

        val running = bothGranted && Prefs.isWidgetEnabled(this)
        binding.btnStartStop.text = getString(if (running) R.string.stop_widget else R.string.start_widget)
        binding.tvHint.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun toggleWidget() {
        val running = Prefs.isWidgetEnabled(this)
        if (running) {
            Prefs.setWidgetEnabled(this, false)
            stopService(Intent(this, OverlayService::class.java))
        } else {
            Prefs.setWidgetEnabled(this, true)
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        }
        refreshState()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(packageName)
    }
}
