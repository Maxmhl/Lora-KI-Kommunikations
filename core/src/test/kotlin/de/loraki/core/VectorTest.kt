package de.loraki.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

/** Prüft die Kotlin-Implementierung gegen Testvektoren der Python-Referenz (protocol/test-vectors.json). */
class VectorTest {
    private val v = JSONObject(File(System.getProperty("vectors")).readText())

    @Test fun nums() {
        val a = v.getJSONArray("nums")
        for (i in 0 until a.length()) {
            val p = a.getJSONArray(i)
            assertEquals(p.getString(1), Lkp.encNum(p.getInt(0)))
            assertEquals(p.getInt(0), Lkp.decNum(p.getString(1)))
        }
    }

    @Test fun dataFrames() {
        for (o in v.getJSONArray("data_frames").objs()) {
            val chunk = hex(o.getString("chunk"))
            assertEquals(o.getString("text"), Lkp.formatData(o.getInt("id"), o.getInt("idx"), o.getInt("total"), chunk, o.getBoolean("poll")))
            val f = assertIs<Lkp.Frame.Data>(Lkp.parseFrame(o.getString("text")))
            assertEquals(o.getInt("idx"), f.idx)
            assertEquals(o.getBoolean("poll"), f.poll)
            assertContentEquals(chunk, f.body)
        }
    }

    @Test fun acksAndAborts() {
        for (o in v.getJSONArray("acks").objs()) {
            val missing = o.getJSONArray("missing").let { m -> (0 until m.length()).map { m.getInt(it) } }
            assertEquals(o.getString("text"), Lkp.formatAck(o.getInt("id"), o.getInt("hi"), missing))
            assertEquals(Lkp.Frame.Ack(o.getInt("id"), o.getInt("hi"), missing), Lkp.parseFrame(o.getString("text")))
        }
        for (o in v.getJSONArray("aborts").objs()) {
            assertEquals(o.getString("text"), Lkp.formatAbort(o.getInt("id")))
            assertEquals(Lkp.Frame.Abort(o.getInt("id")), Lkp.parseFrame(o.getString("text")))
        }
        val n = v.getJSONArray("non_frames")
        for (i in 0 until n.length()) assertNull(Lkp.parseFrame(n.getString(i)))
    }

    @Test fun envelopesFromPythonParse() {
        for (o in v.getJSONArray("envelopes").objs()) {
            val e = Lkp.parseEnvelope(hex(o.getString("raw")))
            assertEquals(o.getInt("kind"), e.kind)
            assertEquals(o.getInt("conv"), e.conv)
            assertContentEquals(hex(o.getString("payload")), e.payload)
        }
    }

    @Test fun envelopeRoundtripKotlin() {
        val big = ("Wiederholung ".repeat(80)).toByteArray()
        val raw = Lkp.buildEnvelope(Lkp.KIND_REPLY, 2, big)
        assert(raw.size < big.size)
        assertContentEquals(big, Lkp.parseEnvelope(raw).payload)
    }

    @Test fun airtime() {
        for (o in v.getJSONArray("airtime").objs()) {
            val r = RadioParams(o.getDouble("freq"), o.getDouble("bw"), o.getInt("sf"), o.getInt("cr"), o.getInt("preamble"))
            assertEquals(o.getDouble("seconds"), Airtime.seconds(o.getInt("bytes"), r), 1e-9)
        }
        val pb = v.getJSONArray("packet_bytes")
        for (i in 0 until pb.length()) assertEquals(pb.getJSONArray(i).getInt(1), Airtime.meshPacketBytes(pb.getJSONArray(i).getInt(0)))
    }

    @Test fun subbands() {
        val a = v.getJSONArray("subbands")
        for (i in 0 until a.length()) {
            val r = a.getJSONArray(i)
            val s = Airtime.subband(r.getDouble(0))
            assertEquals(r.getDouble(1), s.duty, 1e-12)
            assertEquals(r.getInt(2), s.maxErpMw)
            assertEquals(r.getBoolean(3), s.known)
        }
    }

    @Test fun constants() {
        val c = v.getJSONObject("constants")
        assertEquals(c.getInt("fragment_bytes"), Lkp.FRAGMENT_BYTES)
        assertEquals(c.getInt("body_chars"), Lkp.BODY_CHARS)
        assertEquals(c.getInt("window"), Lkp.WINDOW)
    }
}
