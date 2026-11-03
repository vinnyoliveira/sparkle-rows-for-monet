package com.ptvin.sparklerows.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import com.ptvin.sparklerows.R
import com.ptvin.sparklerows.data.Channel
import com.ptvin.sparklerows.data.EpgProgram
import com.ptvin.sparklerows.data.Prefs
import com.ptvin.sparklerows.data.Sparkle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date

/**
 * Cartao 16:9: imagem do programa ao fundo, nome do programa, a seguir, e o
 * logo do canal num selo com sombreado no canto inferior direito.
 */
object Art {
    private const val W = 640
    private const val H = 360
    private const val VERSION = "c2"

    fun uri(context: Context, channel: Channel, program: EpgProgram?, next: List<EpgProgram>): Uri =
        Uri.parse("content://com.ptvin.sparklerows.art/${fileName(context, channel, program, next)}")

    fun ensure(
        context: Context,
        channel: Channel,
        sparkleId: Long?,
        program: EpgProgram?,
        next: List<EpgProgram>,
        epgChannelIcon: String?,
    ): Uri {
        val dir = File(context.cacheDir, "cards").apply { mkdirs() }
        val file = File(dir, fileName(context, channel, program, next))
        if (file.exists() && file.length() > 0) return uri(context, channel, program, next)
        val bitmap = draw(context, channel, sparkleId, program, next, epgChannelIcon)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        return uri(context, channel, program, next)
    }

    /** Cartoes antigos (de outros programas) saem do cache. */
    fun cleanup(context: Context, maxAgeDays: Int = 3) {
        val dir = File(context.cacheDir, "cards")
        val limit = System.currentTimeMillis() - maxAgeDays * 24L * 60 * 60 * 1000
        runCatching {
            dir.listFiles()?.forEach { file -> if (file.lastModified() < limit) file.delete() }
        }
    }

    private fun fileName(
        context: Context,
        channel: Channel,
        program: EpgProgram?,
        next: List<EpgProgram>,
    ): String {
        val lines = next.take(Prefs.nextCount(context)).joinToString("|") { "${it.start}:${it.title}" }
        return sha1("${channel.url}|${channel.name}|${program?.title}|${program?.icon}|$lines")
            .take(16) + "-$VERSION.jpg"
    }

    private fun draw(
        context: Context,
        channel: Channel,
        sparkleId: Long?,
        program: EpgProgram?,
        next: List<EpgProgram>,
        epgChannelIcon: String?,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val channelLogo = (if (sparkleId != null) Sparkle.channelLogo(context, sparkleId) else null)
            ?: download(epgChannelIcon)
            ?: download(channel.logo)
        val backdrop = download(program?.icon)

        if (backdrop != null) {
            drawCover(canvas, backdrop)
            backdrop.recycle()
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { color = 0x77000000.toInt() })
        } else {
            canvas.drawRect(
                0f,
                0f,
                W.toFloat(),
                H.toFloat(),
                Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, W.toFloat(), H.toFloat(),
                        Color.parseColor("#10131A"),
                        Color.parseColor("#1E2430"),
                        Shader.TileMode.CLAMP,
                    )
                },
            )
        }

        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, H * 0.4f, 0f, H.toFloat(),
                Color.TRANSPARENT, 0xF2000000.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, H * 0.4f, W.toFloat(), H.toFloat(), scrim)

        val count = Prefs.nextCount(context)
        val nextLines: List<String> = when {
            count == 0 -> emptyList()
            next.isEmpty() -> listOf(context.getString(R.string.card_next_none))
            else -> {
                val time = android.text.format.DateFormat.getTimeFormat(context)
                next.take(count).mapIndexed { index, item ->
                    val label = if (index == 0) R.string.card_next else R.string.card_then
                    context.getString(label, "${time.format(Date(item.start))} · ${item.title}")
                }
            }
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 31f
            isFakeBoldText = true
        }
        val title = program?.title ?: channel.name
        // O bloco de texto cresce para cima: o titulo fica por cima das linhas dos seguintes.
        val firstLine = H - 24f - (nextLines.size - 1) * 26f
        canvas.drawText(
            TextUtils.ellipsize(title, titlePaint, W - 190f, TextUtils.TruncateAt.END).toString(),
            32f,
            firstLine - 34f,
            titlePaint,
        )

        val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B7C4FF")
            textSize = 19f
        }
        nextLines.forEachIndexed { index, line ->
            canvas.drawText(
                TextUtils.ellipsize(line, nextPaint, W - 190f, TextUtils.TruncateAt.END).toString(),
                32f,
                firstLine + index * 26f,
                nextPaint,
            )
        }

        if (channelLogo != null) {
            val box = RectF(W - 148f, H - 96f, W - 20f, H - 20f)
            canvas.drawRoundRect(box, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() })
            fitInside(canvas, channelLogo, RectF(box.left + 8, box.top + 8, box.right - 8, box.bottom - 8))
            channelLogo.recycle()
        }
        return bitmap
    }

    private fun drawCover(canvas: Canvas, image: Bitmap) {
        val scale = maxOf(W.toFloat() / image.width, H.toFloat() / image.height)
        val dw = (image.width * scale).toInt()
        val dh = (image.height * scale).toInt()
        val dst = Rect((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2)
        canvas.drawBitmap(image, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun fitInside(canvas: Canvas, image: Bitmap, box: RectF) {
        val scale = minOf(box.width() / image.width, box.height() / image.height, 4f)
        val dw = image.width * scale
        val dh = image.height * scale
        val cx = box.centerX()
        val cy = box.centerY()
        val dst = RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
        canvas.drawBitmap(image, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun download(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty(
                "User-Agent",
                "SparkleRows/" + com.ptvin.sparklerows.BuildConfig.VERSION_NAME,
            )
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 900 || bounds.outHeight / (sample * 2) >= 900) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
