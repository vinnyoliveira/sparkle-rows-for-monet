package com.ptvin.sparklerows.channel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.tv.TvContract
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.ptvin.sparklerows.MainActivity
import com.ptvin.sparklerows.R
import com.ptvin.sparklerows.data.Channel
import com.ptvin.sparklerows.data.Guide
import com.ptvin.sparklerows.data.Prefs
import com.ptvin.sparklerows.data.Sparkle
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Publica cada grupo da lista como uma linha do Android TV. Cada cartao abre o
 * canal correspondente no Sparkle (deep link com o id interno).
 */
object Publisher {
    const val MAX_ROWS = 10
    private const val MAX_PROGRAMS = 30
    private const val OFFSET_KEY = "sparkleOffset"
    private const val SPARKLE_KEY = "sparkle-recent"

    fun savedOffset(context: Context): Int? =
        Prefs.intOrNull(context, OFFSET_KEY)

    fun saveOffset(context: Context, offset: Int) =
        Prefs.setInt(context, OFFSET_KEY, offset)

    fun publish(
        context: Context,
        channels: List<Channel>,
        groups: Set<String>,
        offset: Int,
        guide: Guide?,
    ): Pair<Int, Int> {
        val helper = PreviewChannelHelper(context)
        var cards = 0
        var rows = 0
        // O Monet mostra uma unica linha por app: o peso tem de decrescer entre linhas.
        var weight = MAX_PROGRAMS * MAX_ROWS
        // O Monet so oferece "conteudo de um app" para quem publica "continuar assistindo".
        val watchNext = ArrayList<ContentValues>()
        Art.cleanup(context)

        channels.withIndex()
            .filter { it.value.group in groups }
            .groupBy { it.value.group }
            .entries
            .sortedByDescending { it.value.size }
            .take(MAX_ROWS)
            .forEach { (group, items) ->
                val channelId = channel(context, helper, group, group.removePrefix("- ").trim())
                runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
                val taken = items.take(MAX_PROGRAMS)
                val arts = generate(context, taken.map { (it.index + 1L + offset) to it.value }, guide)
                android.util.Log.i("SparkleRows", "$group: canal=$channelId itens=${taken.size} artes=${arts.size}")
                if (arts.isEmpty()) return@forEach
                clearPrograms(context, channelId)
                var inserted = 0
                taken.forEachIndexed { index, indexed ->
                    val item = indexed.value
                    val art = arts[item.url] ?: return@forEachIndexed
                    val sparkleId = indexed.index + 1L + offset
                    val title = guide?.program(item)?.title ?: item.name
                    publishCard(context, helper, channelId, weight--, title, item.name, art, sparkleId)
                    inserted++
                    cards++
                }
                val live = taken.firstOrNull { guide?.currentStart(it.value) != null }
                if (live != null) {
                    val item = live.value
                    val art = arts[item.url]
                    val start = guide?.currentStart(item)
                    val end = guide?.currentEnd(item)
                    if (art != null && start != null && end != null) {
                        val title = guide?.program(item)?.title ?: item.name
                        watchNext += watchNextValues(title, art, live.index + 1L + offset, start, end)
                    }
                }
                android.util.Log.i("SparkleRows", "$group: inseridos=$inserted")
                rows++
            }

        publishWatchNext(context, watchNext)
        reconcile(context, groups + SPARKLE_KEY)
        return cards to rows
    }

    /**
     * Sem lista: espelha os canais que o proprio Sparkle publica (recentes).
     * Zero configuracao, mas so cobre o que o Sparkle expoe.
     */
    fun publishFromSparkle(context: Context, recents: List<Sparkle.Recent>): Pair<Int, Int> {
        if (recents.isEmpty()) throw IOException(context.getString(R.string.sync_sparkle_empty))
        val helper = PreviewChannelHelper(context)
        val channelId = channel(context, helper, SPARKLE_KEY, context.getString(R.string.recent_row))
        runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        clearPrograms(context, channelId)

        val taken = recents.take(MAX_PROGRAMS)
        val items = taken.map {
            it.id to Channel(it.title, "sparkle:${it.id}", null, context.getString(R.string.recent_row))
        }
        val arts = generate(context, items, null)
        taken.forEachIndexed { index, recent ->
            val art = arts["sparkle:${recent.id}"] ?: return@forEachIndexed
            publishCard(context, helper, channelId, MAX_PROGRAMS - index, recent.title, context.getString(R.string.recent_row), art, recent.id)
        }
        // Sem guia nao ha janela real; sem watch next o Monet nem lista a app.
        arts.entries.firstOrNull()?.let { (url, art) ->
            val recent = taken.firstOrNull { "sparkle:${it.id}" == url } ?: return@let
            val now = System.currentTimeMillis()
            publishWatchNext(context, listOf(watchNextValues(recent.title, art, recent.id, now, now + 2 * 60 * 60 * 1000)))
        }
        return taken.size to 1
    }

    /**
     * "A decorrer" no TvProvider. O Monet (1.1.x) so mostra uma app em
     * "Conteudo de um app" quando ela publica watch next. Uma por linha chega.
     */
    private fun watchNextValues(
        title: String,
        art: Uri,
        sparkleId: Long,
        start: Long,
        end: Long,
    ): ContentValues {
        val now = System.currentTimeMillis()
        val duration = (end - start).coerceAtLeast(2)
        return ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, "TYPE_TV_EPISODE")
            put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, "WATCH_NEXT_TYPE_CONTINUE")
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, title)
            put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, duration)
            put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (now - start).coerceIn(1, duration - 1))
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, now)
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, art.toString())
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, "ASPECT_RATIO_16_9")
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, Sparkle.deepLink(sparkleId))
            put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, Art.sha1("$title:$sparkleId"))
        }
    }

    private fun publishWatchNext(context: Context, items: List<ContentValues>) {
        runCatching { context.contentResolver.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null) }
        items.forEach {
            runCatching { context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, it) }
                .onFailure { error -> android.util.Log.w("SparkleRows", "watch next: $error") }
        }
    }

    private fun publishCard(
        context: Context,
        helper: PreviewChannelHelper,
        channelId: Long,
        weight: Int,
        title: String,
        subtitle: String,
        art: Uri,
        sparkleId: Long,
    ) {
        val program = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(title)
            .setDescription(subtitle)
            .setPosterArtUri(art)
            .setThumbnailUri(art)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(Uri.parse(Sparkle.deepLink(sparkleId)))
            .setBrowsable(true)
            .setContentId(Art.sha1("$subtitle:$title"))
            .setInternalProviderId("$subtitle:$sparkleId")
            // O Monet ordena do maior peso para o menor.
            .setWeight(weight)
            .build()
        helper.publishPreviewProgram(program)
    }

    private fun channel(
        context: Context,
        helper: PreviewChannelHelper,
        key: String,
        displayName: String = key.removePrefix("- ").trim(),
    ): Long {
        val existing = resolveId(context, helper, key)
        val preview = PreviewChannel.Builder()
            .setDisplayName(displayName)
            .setDescription(context.getString(R.string.channel_description))
            .setAppLinkIntent(Intent(context, MainActivity::class.java))
            .setInternalProviderId(key)
            .setLogo(logo(displayName))
            .build()

        if (existing != null) {
            val updated = runCatching {
                helper.updatePreviewChannel(existing, preview)
                existing
            }.getOrNull()
            if (updated != null) return browsable(context, updated, key)
        }

        val channelId = helper.publishChannel(preview)
        if (channelId == -1L) throw IOException(context.getString(R.string.channel_error, key))
        Prefs.setChannelId(context, key, channelId)
        return browsable(context, channelId, key)
    }

    /**
     * Pede ao launcher que marque o canal como visivel. O TvProvider nao deixa
     * a app escrever essa coluna, por isso e so um pedido.
     */
    private fun browsable(context: Context, id: Long, key: String): Long {
        runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
            .onFailure { android.util.Log.w("SparkleRows", "browsable $key: $it") }
        return id
    }

    private fun resolveId(context: Context, helper: PreviewChannelHelper, key: String): Long? {
        val saved = Prefs.channelId(context, key)
        if (saved != -1L && helper.getPreviewChannel(saved) != null) return saved
        val found = helper.allChannels.firstOrNull { it.internalProviderId == key }?.id
        if (found != null) Prefs.setChannelId(context, key, found)
        return found
    }

    private fun reconcile(context: Context, valid: Set<String>) {
        runCatching {
            PreviewChannelHelper(context).allChannels.forEach { channel ->
                val key = channel.internalProviderId
                if (key != null && key !in valid) {
                    runCatching { clearPrograms(context, channel.id) }
                    runCatching {
                        context.contentResolver.delete(TvContractCompat.buildChannelUri(channel.id), null, null)
                    }
                    Prefs.clearChannelId(context, key)
                }
            }
        }
    }

    private fun clearPrograms(context: Context, channelId: Long) {
        context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(TvContractCompat.PreviewPrograms._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            while (cursor.moveToNext()) {
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(cursor.getLong(idColumn)),
                    null,
                    null,
                )
            }
        }
    }

    private fun generate(
        context: Context,
        items: List<Pair<Long, Channel>>,
        guide: Guide?,
    ): Map<String, Uri> {
        val count = Prefs.nextCount(context)
        val pool = Executors.newFixedThreadPool(6)
        return try {
            pool.invokeAll(
                items.map { (id, channel) ->
                    Callable {
                        val program = guide?.program(channel)
                        val next = if (count == 0) emptyList() else guide?.nextPrograms(channel, count).orEmpty()
                        channel.url to Art.ensure(
                            context,
                            channel,
                            id,
                            program,
                            next,
                            guide?.channelIcon(channel),
                        )
                    }
                },
            )
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .toMap()
        } finally {
            pool.shutdown()
        }
    }

    private fun logo(group: String): Bitmap {
        val size = 320
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#0D0F14"))
        val clean = group.removePrefix("- ").trim()
        val initials = clean.split(' ', '-', '_', '.')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "TV" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B7C4FF")
            textSize = size * 0.38f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, y, paint)
        return bitmap
    }
}
