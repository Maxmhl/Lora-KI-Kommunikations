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

    var imageMaxDim: Int get() = p.getInt("imgDim", 320); set(v) = p.edit { putInt("imgDim", v) }
    var imageQuality: Int get() = p.getInt("imgQ", 50); set(v) = p.edit { putInt("imgQ", v) }
    var maxAttachKb: Int get() = p.getInt("maxKb", 60); set(v) = p.edit { putInt("maxKb", v) }

    var currentConv: Int get() = p.getInt("conv", 0); set(v) = p.edit { putInt("conv", v) }
    var limiterJson: String? get() = p.getString("limiter", null); set(v) = p.edit { putString("limiter", v) }
    /** Zuletzt vom Server geladene Modellliste (Zeilen "name\tflags"). */
    var modelsRaw: String get() = p.getString("models", "") ?: ""; set(v) = p.edit { putString("models", v) }
    // --- Nomad (Gateway der Bridge) ---
    /** Basis-URL des Gateways, z. B. http://192.168.1.10:8765 (WLAN/VPN). */
    var nomadUrl: String get() = p.getString("nomadUrl", "") ?: ""; set(v) = p.edit { putString("nomadUrl", v.trim().trimEnd('/')) }
    var nomadToken: String get() = p.getString("nomadToken", "") ?: ""; set(v) = p.edit { putString("nomadToken", v.trim()) }
    /** Update-Intervall in Tagen (0 = nie automatisch prüfen). */
    var nomadIntervalDays: Int get() = p.getInt("nomadInterval", 7); set(v) = p.edit { putInt("nomadInterval", v) }
    var nomadWifiOnly: Boolean get() = p.getBoolean("nomadWifi", true); set(v) = p.edit { putBoolean("nomadWifi", v) }
    /** true: Updates automatisch laden; false: nur benachrichtigen. */
    var nomadAutoDownload: Boolean get() = p.getBoolean("nomadAuto", false); set(v) = p.edit { putBoolean("nomadAuto", v) }
    var nomadLastCheck: Long get() = p.getLong("nomadLast", 0); set(v) = p.edit { putLong("nomadLast", v) }
    var nomadManifestJson: String? get() = p.getString("nomadManifest", null); set(v) = p.edit { putString("nomadManifest", v) }
    var nomadInstalledJson: String? get() = p.getString("nomadInstalled", null); set(v) = p.edit { putString("nomadInstalled", v) }
    var nomadWanted: Set<String> get() = p.getStringSet("nomadWanted", emptySet()) ?: emptySet(); set(v) = p.edit { putStringSet("nomadWanted", v) }
    var nomadChatModel: String? get() = p.getString("nomadChatModel", null); set(v) = p.edit { putString("nomadChatModel", v) }
    var nomadChatSession: String? get() = p.getString("nomadChatSession", null); set(v) = p.edit { putString("nomadChatSession", v) }
    /** Privatmodus (Standard): nichts von Chats, Anhängen, Karten oder Bibliotheken wird auf dem Handy gespeichert. */
    var privacyMode: Boolean get() = p.getBoolean("privacy", true); set(v) = p.edit { putBoolean("privacy", v) }
    /** Kompaktmodus (experimentell): öffentliche Antworten als unverschlüsselte, beglaubigte Rohdatenpakete – ca. 20 % schneller. */
    var compactText: Boolean get() = p.getBoolean("compactText", false); set(v) = p.edit { putBoolean("compactText", v) }
    /** Auch Kartenausschnitte kompakt: Mithörende sehen dann, welche Gegend du abrufst. */
    var compactMap: Boolean get() = p.getBoolean("compactMap", false); set(v) = p.edit { putBoolean("compactMap", v) }
    var wantConnected: Boolean get() = p.getBoolean("want", false); set(v) = p.edit { putBoolean("want", v) }
}
