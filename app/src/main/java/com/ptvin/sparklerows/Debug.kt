package com.ptvin.sparklerows

import android.content.Context
import androidx.tvprovider.media.tv.TvContractCompat
import java.io.File

/** Diagnostico: am start ... --ez dump true -> escreve files/dump.txt */
object Debug {
    fun dump(context: Context) {
        Thread {
            val sb = StringBuilder()
            runCatching {
                context.contentResolver.query(
                    TvContractCompat.Channels.CONTENT_URI,
                    arrayOf("_id", "display_name", "package_name", "internal_provider_id", "type", "browsable"),
                    null, null, null,
                )?.use { channels ->
                    while (channels.moveToNext()) {
                        val pkg = channels.getString(2)
                        val own = pkg == context.packageName
                        val sparkleRecent = pkg == "se.hedekonsult.sparkle" &&
                            channels.getString(3) == "homescreen_recent_channels"
                        val channelId = channels.getLong(0)
                        val programList = ArrayList<String>()
                        context.contentResolver.query(
                            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                            arrayOf("title", "intent_uri", "browsable", "duration_millis", "last_playback_position_millis"),
                            null, null, null,
                        )?.use { programs ->
                            val titleCol = programs.getColumnIndex("title")
                            val intentCol = programs.getColumnIndex("intent_uri")
                            val browsableCol = programs.getColumnIndex("browsable")
                            while (programs.moveToNext()) {
                                programList.add(
                                    "${programs.getString(titleCol)} browsable=" +
                                        (if (programs.isNull(browsableCol)) "null" else programs.getInt(browsableCol).toString()) +
                                        " dur=" + (if (programs.getLong(3) > 0) ">0" else "0") +
                                        " | ${programs.getString(intentCol)}",
                                )
                            }
                        }
                        val visible = programList.count { it.contains("browsable=1") }
                        val timed = programList.count { it.contains("dur>0") }
                        val head = "${channels.getString(1)}' browsable=${channels.getInt(5)} " +
                            "type=${channels.getString(4)} key=${channels.getString(3)} " +
                            "progs=${programList.size} vis=$visible dur=$timed"
                        if (!own && !sparkleRecent) {
                            sb.appendLine("?? $pkg '$head")
                            continue
                        }
                        sb.appendLine("== $pkg '$head")
                        programList.take(4).forEach { sb.appendLine("   * $it") }
                    }
                }
                sb.appendLine("--- watch_next por pacote")
                context.contentResolver.query(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    arrayOf("package_name", "title", "last_playback_position_millis", "duration_millis"),
                    null, null, null,
                )?.use { rows ->
                    val counts = HashMap<String, Int>()
                    while (rows.moveToNext()) {
                        val pkg = rows.getString(0) ?: "?"
                        counts[pkg] = (counts[pkg] ?: 0) + 1
                    }
                    counts.forEach { (pkg, n) -> sb.appendLine("?? $pkg watch_next=$n") }
                }
            }.onFailure { sb.appendLine("erro: $it") }
            File(context.filesDir, "dump.txt").writeText(sb.toString())
        }.start()
    }
}
