package de.loraki.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

data class RadioParams(
    val freqMhz: Double = 869.618,
    val bwKhz: Double = 62.5,
    val sf: Int = 8,
    val cr: Int = 8,
    val preamble: Int = 16,
    val txPowerDbm: Int? = null,
)

data class SubbandLimit(val duty: Double, val maxErpMw: Int, val known: Boolean)

object Airtime {
    /** Semtech AN1200.13, expliziter Header, CRC an. */
    fun seconds(payloadBytes: Int, r: RadioParams): Double {
        val tsym = 2.0.pow(r.sf) / (r.bwKhz * 1000.0)
        val de = if (tsym > 0.016) 1 else 0
        val num = 8 * payloadBytes - 4 * r.sf + 28 + 16
        val nPl = 8 + max(ceil(num.toDouble() / (4 * (r.sf - 2 * de))).toInt() * r.cr, 0)
        return (r.preamble + 4.25 + nPl) * tsym
    }

    /** Geschätzte Funkpaketgröße einer MeshCore-Direktnachricht. */
    fun meshPacketBytes(textUtf8Len: Int, pathLen: Int = 0): Int {
        val cipher = (5 + textUtf8Len + 1 + 15) / 16 * 16 // +1: Terminator (konservativ)
        return 1 + 1 + pathLen + 2 + 2 + cipher
    }

    /** Funkpaket eines Rohdatenpakets: Kopf(1) + Pfadlänge(1) + Pfad + Nutzdaten. */
    fun rawPacketBytes(frameLen: Int, pathLen: Int = 0) = 2 + pathLen + frameLen

    fun textSeconds(text: String, r: RadioParams): Double =
        seconds(meshPacketBytes(text.toByteArray().size), r)

    // BNetzA Allgemeinzuteilung SRD (868 MHz) – vor Release gegen aktuelle Verfügung prüfen!
    private val subbands = listOf(
        Triple(865.0..868.0, 0.01, 25),
        Triple(868.0..868.6, 0.01, 25),
        Triple(868.7..869.2, 0.001, 25),
        Triple(869.4..869.65, 0.10, 500),
        Triple(869.7..870.0, 0.01, 25),
    )

    fun subband(freqMhz: Double): SubbandLimit {
        for ((range, duty, erp) in subbands) if (freqMhz in range) return SubbandLimit(duty, erp, true)
        return SubbandLimit(0.001, 25, false)
    }

    fun dbmToMw(dbm: Double): Double = 10.0.pow(dbm / 10)

    /** Grobe Dauerabschätzung für ein Objekt (Bytes inkl. Envelope) bei gegebenem Duty-Cycle. */
    fun estimateTransferSeconds(envelopeBytes: Int, r: RadioParams, duty: Double, margin: Double = 0.8): Double {
        val frags = (envelopeBytes + Lkp.FRAGMENT_BYTES - 1) / Lkp.FRAGMENT_BYTES
        val perFrag = seconds(meshPacketBytes(Lkp.TEXT_MAX), r) / (duty * margin)
        val windows = (frags + Lkp.WINDOW - 1) / Lkp.WINDOW
        return frags * perFrag + windows * 10.0
    }
}
