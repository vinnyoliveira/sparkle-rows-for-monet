package com.ptvin.sparklerows.data

import android.content.Context
import com.ptvin.sparklerows.R
import com.ptvin.sparklerows.SyncWorker
import com.ptvin.sparklerows.channel.Publisher

/** Carrega lista + EPG, calibra o Sparkle, republica e agenda a proxima troca. */
object Sync {
    /** Um sync de cada vez: a atividade e o worker nao podem escrever ao mesmo tempo. */
    @Synchronized
    fun run(context: Context): String {
        val url = Prefs.url(context).trim()
        if (url.isBlank()) return context.getString(R.string.sync_no_list)

        val playlist = try {
            M3u.parse(M3u.fetchText(url))
        } catch (error: Exception) {
            return context.getString(R.string.sync_list_error, error.message.orEmpty())
        }
        if (playlist.channels.isEmpty()) return context.getString(R.string.sync_empty_list)

        // EPG: o que o utilizador indicou; senao o cabecalho x-tvg-url da propria lista.
        val epg = Prefs.epgUrl(context).trim().ifBlank { playlist.epgUrl.orEmpty() }
        if (epg.isNotBlank() && epg != Prefs.epgUrl(context)) Prefs.setEpgUrl(context, epg)
        val guide = epg.takeIf { it.isNotBlank() }
            ?.let { runCatching { Epg.load(M3u.fetch(it)) }.getOrNull() }

        return publish(context, playlist, guide)
    }

    private fun publish(context: Context, playlist: Playlist, guide: Guide?): String {
        val offset = Sparkle.offset(playlist.channels, Sparkle.recent(context))?.first
            ?: Publisher.savedOffset(context)
            ?: Prefs.DEFAULT_OFFSET

        Publisher.saveOffset(context, offset)
        val groups = Prefs.groups(context).ifEmpty { playlist.channels.map { it.group }.toSet() }
        val (cards, rows) = Publisher.publish(context, playlist.channels, groups, offset, guide)
        Prefs.setPublished(context, true)

        // Proxima atualizacao: quando o primeiro programa atual terminar.
        val now = System.currentTimeMillis()
        val nextEnd = guide?.let { current ->
            playlist.channels
                .filter { it.group in groups }
                .mapNotNull { current.currentEnd(it) }
                .filter { it > now }
                .minOrNull()
        }
        SyncWorker.scheduleAt(context, nextEnd)

        // Sem guia nao ha como agendar pelo fim do programa: o periodico de 6h cobre.
        if (nextEnd == null) SyncWorker.schedule(context)

        return context.getString(
            R.string.sync_done,
            cards,
            rows,
            guide?.currentCount ?: 0,
        )
    }
}
