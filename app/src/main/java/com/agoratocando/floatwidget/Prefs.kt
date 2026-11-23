package com.agoratocando.floatwidget

import android.content.Context

/**
 * Preferencias simples do app: so guarda se o usuario deixou a bolha ligada,
 * pra poder religar sozinha depois de um reboot (ver BootReceiver).
 */
object Prefs {
    private const val FILE_NAME = "agora_tocando_prefs"
    private const val KEY_WIDGET_ENABLED = "widget_enabled"

    fun isWidgetEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WIDGET_ENABLED, false)
    }

    fun setWidgetEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WIDGET_ENABLED, enabled).apply()
    }
}
