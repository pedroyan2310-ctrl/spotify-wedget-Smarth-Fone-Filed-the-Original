package com.agoratocando.floatwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Se a bolha estava ligada antes do celular reiniciar, religa sozinha.
 * So tenta se as duas permissoes ainda estiverem concedidas; se o usuario
 * revogou alguma enquanto o aparelho estava desligado, nao faz nada.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!Prefs.isWidgetEnabled(context)) return

        val notifGranted = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        val overlayGranted = Settings.canDrawOverlays(context)

        if (notifGranted && overlayGranted) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        } else {
            Prefs.setWidgetEnabled(context, false)
        }
    }
}
