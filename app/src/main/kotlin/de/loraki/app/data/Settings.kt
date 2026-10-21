package de.loraki.app.data

import android.content.Context
import androidx.core.content.edit

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var deviceAddress: String? get() = p.getString("addr", null); set(v) = p.edit { putString("addr", v) }
    var deviceName: String? get() = p.getString("devName", null); set(v) = p.edit { putString("devName", v) }

    /** Public Key (hex, 64 Zeichen) des Server-Nodes. */
    var serverKeyHex: String? get() = p.getString("srvKey", null); set(v) = p.edit { putString("srvKey", v) }
    var serverName: String? get() = p.getString("srvName", null); set(v) = p.edit { putString("srvName", v) }

    /** 0 = automatisch aus Frequenz; sonst Duty-Cycle in Prozent als zusätzliche Obergrenze. */
    var dutyOverridePercent: Float get() = p.getFloat("duty", 0f); set(v) = p.edit { putFloat("duty", v) }
    var margin: Float get() = p.getFloat("margin", 0.8f); set(v) = p.edit { putFloat("margin", v) }

    var imageMaxDim: Int get() = p.getInt("imgDim", 384); set(v) = p.edit { putInt("imgDim", v) }
    var imageQuality: Int get() = p.getInt("imgQ", 55); set(v) = p.edit { putInt("imgQ", v) }
    var maxAttachKb: Int get() = p.getInt("maxKb", 60); set(v) = p.edit { putInt("maxKb", v) }

    var currentConv: Int get() = p.getInt("conv", 0); set(v) = p.edit { putInt("conv", v) }
    var limiterJson: String? get() = p.getString("limiter", null); set(v) = p.edit { putString("limiter", v) }
    /** Zuletzt vom Server geladene Modellliste (Zeilen "name\tflags"). */
    var modelsRaw: String get() = p.getString("models", "") ?: ""; set(v) = p.edit { putString("models", v) }
    var wantConnected: Boolean get() = p.getBoolean("want", false); set(v) = p.edit { putBoolean("want", v) }
}
