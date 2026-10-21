package de.loraki.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

class Prepared(val name: String, val mime: String, val bytes: ByteArray, val isImage: Boolean)

/** Bilder werden stark verkleinert, damit sie über LoRa in vertretbarer Zeit übertragbar sind. */
object Attachments {
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "datei"

    fun prepareImage(ctx: Context, uri: Uri, maxDim: Int, quality: Int, maxBytes: Int): Prepared {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("Bild nicht lesbar")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("Bild nicht lesbar")
        val rot = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        // Erst Qualität senken, dann die Auflösung verkleinern, bis das Bild in das Größenlimit passt.
        var dim = maxDim
        var out: ByteArray? = null
        while (out == null) {
            val scale = dim.toFloat() / max(bmp.width, bmp.height)
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rot != 0f) postRotate(rot) }
            val scaled = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            var q = quality
            while (q >= 15) {
                val bytes = ByteArrayOutputStream().also { scaled.compress(fmt, q, it) }.toByteArray()
                if (bytes.size <= maxBytes) { out = bytes; break }
                q -= 10
            }
            if (out == null) {
                dim = (dim * 0.8f).toInt()
                if (dim < 96) throw IOException("Bild lässt sich nicht auf ${maxBytes / 1024} KB verkleinern – Größenlimit erhöhen")
            }
        }
        val base = displayName(ctx, uri).substringBeforeLast('.')
        return Prepared("$base.webp", "image/webp", out!!, true)
    }

    fun prepareFile(ctx: Context, uri: Uri, maxBytes: Int): Prepared {
        val name = displayName(ctx, uri)
        val data = ctx.contentResolver.openInputStream(uri)?.use { readLimited(it, maxBytes + 1) } ?: throw IOException("Datei nicht lesbar")
        if (data.size > maxBytes) throw IOException("Datei größer als ${maxBytes / 1024} KB (Limit in den Einstellungen)")
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        return Prepared(name, mime, data, false)
    }
}
