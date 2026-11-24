package com.garitto.exoduspanel

import android.content.Context

/** Ajustes de la app guardados en el teléfono. */
class Config(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("exodus_panel", Context.MODE_PRIVATE)

    var mac: String
        get() = p.getString("mac", "") ?: ""
        set(v) { p.edit().putString("mac", v.trim().uppercase()).apply() }

    var key: String
        get() = p.getString("key", "") ?: ""
        set(v) { p.edit().putString("key", v.trim()).apply() }

    /** Corte automático de salidas al llegar al mínimo. */
    var cutEnabled: Boolean
        get() = p.getBoolean("cutEnabled", false)
        set(v) { p.edit().putBoolean("cutEnabled", v).apply() }

    var minPct: Int
        get() = p.getInt("minPct", 20)
        set(v) { p.edit().putInt("minPct", v).apply() }

    /** Salidas que se apagan en el mínimo (bits: 1 = AC, 2 = DC, 4 = USB). */
    var cutMask: Int
        get() = p.getInt("cutMask", Protocol.OUT_ALL)
        set(v) { p.edit().putInt("cutMask", v).apply() }

    /** Reactivar las salidas apagadas cuando la batería sube sobre el mínimo + histéresis. */
    var resume: Boolean
        get() = p.getBoolean("resume", false)
        set(v) { p.edit().putBoolean("resume", v).apply() }

    /** Aviso (notificación) al llegar a este %; 0 = sin aviso. */
    var maxAlert: Int
        get() = p.getInt("maxAlert", 0)
        set(v) { p.edit().putInt("maxAlert", v).apply() }

    /** Si estaba conectado al cerrar, para poder retomarlo. */
    var wantConnected: Boolean
        get() = p.getBoolean("wantConnected", false)
        set(v) { p.edit().putBoolean("wantConnected", v).apply() }
}
