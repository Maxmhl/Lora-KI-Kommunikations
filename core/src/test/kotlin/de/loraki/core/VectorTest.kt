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

    @Test fun base91Vectors() {
        for (o in v.getJSONArray("base91").objs()) {
            assertEquals(o.getString("text"), Base91.encode(hex(o.getString("data"))))
            assertContentEquals(hex(o.getString("data")), Base91.decode(o.getString("text")))
        }
    }

    @Test fun dictionaryIsIdenticalToPython() {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(Lkp.DICTIONARY).joinToString("") { "%02x".format(it) }
        assertEquals(v.getString("dictionary_sha256"), sha)
    }

    @Test fun everyFrameFitsOneAesBudget() {
        val rnd = java.util.Random(3)
        val pats = listOf(0x00, 0xff, 0x58, 0x57, 0x5a).map { b -> ByteArray(Lkp.FRAGMENT_BYTES) { b.toByte() } } +
            List(2000) { ByteArray(Lkp.FRAGMENT_BYTES).also { rnd.nextBytes(it) } }
        for (p in pats) assert(Lkp.formatData(4095, 4095, 4095, p, true).length <= Lkp.TEXT_MAX)
    }

    @Test fun kotlinDictionaryCompressionRoundtripsAndBeatsPlain() {
        val t = "Die Hauptstadt von Frankreich ist Paris. Das ist eine gute Frage.".toByteArray()
        val c = Lkp.buildEnvelope(Lkp.KIND_REPLY, 0, t)
        assert(c.size < Lkp.buildEnvelope(Lkp.KIND_REPLY, 0, t, compress = false).size - 15)
        assertContentEquals(t, Lkp.parseEnvelope(c).payload)
    }

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
        assertEquals(c.getInt("text_max"), Lkp.TEXT_MAX)
        assertEquals(c.getInt("window"), Lkp.WINDOW)
        val k = c.getJSONObject("kinds")
        assertEquals(k.getInt("text"), Lkp.KIND_TEXT)
        assertEquals(k.getInt("image"), Lkp.KIND_IMAGE)
        assertEquals(k.getInt("file"), Lkp.KIND_FILE)
        assertEquals(k.getInt("reply"), Lkp.KIND_REPLY)
        assertEquals(k.getInt("status"), Lkp.KIND_STATUS)
        assertEquals(k.getInt("reset"), Lkp.KIND_RESET)
        assertEquals(k.getInt("model_list_req"), Lkp.KIND_MODEL_LIST_REQ)
        assertEquals(k.getInt("model_list"), Lkp.KIND_MODEL_LIST)
        assertEquals(k.getInt("set_model"), Lkp.KIND_SET_MODEL)
        assertEquals(k.getInt("set_opts"), Lkp.KIND_SET_OPTS)
        assertEquals(k.getInt("nomad_req"), Lkp.KIND_NOMAD_REQ)
        assertEquals(k.getInt("nomad_resp"), Lkp.KIND_NOMAD_RESP)
    }
}
