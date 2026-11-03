package com.ptvin.sparklerows.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Um canal da lista M3U. */
data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String? = null,
)

data class Playlist(val channels: List<Channel>, val epgUrl: String?)

/** Leitor de listas M3U (XTREAM, IPTV-org, qualquer provedor). */
object M3u {
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    const val DEFAULT_GROUP = "Canais"

    fun parse(text: String): Playlist {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var epgUrl: String? = null

        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    epgUrl = ATTR.findAll(line)
                        .firstOrNull { it.groupValues[1].equals("url-tvg", true) }
                        ?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = ATTR.findAll(line.substringBefore(','))
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val title = line.substringAfter(',', "").trim()
                    name = title.ifBlank { attrs["tvg-name"].orEmpty() }
                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() }
                    tvgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }
                    attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() }?.let { group = it }
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }?.let { group = it }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val label = name?.takeIf { it.isNotBlank() } ?: fallbackName(line)
                    out += Channel(label, line, logo, group ?: DEFAULT_GROUP, tvgId)
                    name = null
                    logo = null
                }
            }
        }
        return Playlist(out, epgUrl)
    }

    private fun fallbackName(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { url.take(40) }

    fun fetch(url: String): ByteArray {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "SparkleRows/0.1")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw IOException("HTTP $code")
                    current = URL(URL(current), location).toString()
                } else if (code in 200..299) {
                    val raw = conn.inputStream.use { it.readBytes() }
                    return unzip(raw)
                } else {
                    throw IOException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Demasiados redirecionamentos")
    }

    fun fetchText(url: String): String = fetch(url).toString(Charsets.UTF_8)

    /** O EPG do m3u4u vem em gzip mesmo sem pedirmos. */
    private fun unzip(bytes: ByteArray): ByteArray =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val out = ByteArrayOutputStream(bytes.size * 8)
                input.copyTo(out)
                out.toByteArray()
            }
        } else {
            bytes
        }
}
