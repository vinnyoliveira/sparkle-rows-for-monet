package com.ptvin.sparklerows.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class EpgProgram(val title: String, val icon: String?, val start: Long, val end: Long)

/** Guia do momento (XMLTV): programa atual e os seguintes, por canal. */
class Guide(
    val channelIcons: Map<String, String>,
    private val nameIndex: Map<String, String>,
    private val current: Map<String, EpgProgram>,
    private val upcoming: Map<String, List<EpgProgram>>,
) {
    fun id(channel: Channel): String? {
        val tvgId = channel.tvgId
        if (tvgId != null && (current.containsKey(tvgId) || channelIcons.containsKey(tvgId))) return tvgId
        return nameIndex[normalize(channel.name)]
    }

    fun program(channel: Channel): EpgProgram? = id(channel)?.let { current[it] }

    /** Os proximos programas, ja ordenados pelo inicio. */
    fun nextPrograms(channel: Channel, count: Int): List<EpgProgram> =
        id(channel)?.let { upcoming[it] }?.take(count).orEmpty()

    fun channelIcon(channel: Channel): String? = id(channel)?.let { channelIcons[it] }

    /** Quando acaba o programa atual deste canal. */
    fun currentEnd(channel: Channel): Long? = program(channel)?.end

    /** Quando comecou o programa atual deste canal. */
    fun currentStart(channel: Channel): Long? = program(channel)?.start

    val currentCount: Int get() = current.size

    companion object {
        private val QUALITY = Regex("\\b(fhdr|fhd|uhd|hd|sd|h265|h264|hevc|4k|8k|plus)\\b")

        fun normalize(value: String): String {
            var text = value.lowercase().replace(Regex("[Â¹Â²Â³]"), " ")
            text = text.replace(QUALITY, " ")
            text = text.replace(Regex("[^a-z0-9]"), "")
            return text
        }
    }
}

object Epg {
    private val TIME = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private const val MAX_NEXT = 3
    private data class Slot(val start: Long, val stop: Long, val title: String, val icon: String?)

    fun load(bytes: ByteArray, now: Long = System.currentTimeMillis()): Guide {
        val icons = HashMap<String, String>()
        val names = HashMap<String, String>()
        val slots = HashMap<String, ArrayList<Slot>>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)

        var channelId: String? = null
        var programChannel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var icon: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")
                    "display-name" -> {
                        val id = channelId
                        if (id != null) names[Guide.normalize(parser.nextText())] = id
                    }
                    "programme" -> {
                        programChannel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        icon = null
                    }
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src") ?: continue
                        if (programChannel != null) icon = src else channelId?.let { icons[it] = src }
                    }
                    "title" -> if (programChannel != null && title == null) title = parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        val id = programChannel
                        val name = title
                        if (id != null && name != null && stop > start && start > 0 && stop > now) {
                            slots.getOrPut(id) { ArrayList() }.add(Slot(start, stop, name, icon))
                        }
                        programChannel = null
                    }
                }
            }
            event = parser.next()
        }

        val current = HashMap<String, EpgProgram>()
        val upcoming = HashMap<String, List<EpgProgram>>()
        slots.forEach { (id, list) ->
            list.sortBy { it.start }
            list.firstOrNull { now in it.start until it.stop }?.let {
                current[id] = EpgProgram(it.title, it.icon, it.start, it.stop)
            }
            val after = list.filter { it.start > now }
                .take(MAX_NEXT)
                .map { EpgProgram(it.title, it.icon, it.start, it.stop) }
            if (after.isNotEmpty()) upcoming[id] = after
        }
        return Guide(icons, names, current, upcoming)
    }

    private fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { TIME.parse(value.trim())?.time ?: 0L }.getOrDefault(0L)
    }
}
