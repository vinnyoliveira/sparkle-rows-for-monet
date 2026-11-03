package com.ptvin.sparklerows

import com.ptvin.sparklerows.data.M3u
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {

    @Test
    fun parsesAttributesAndUrls() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-name="SIC" tvg-logo="http://x/logo.png" group-title="Portugal",SIC
            http://stream/sic.m3u8
            #EXTINF:-1 group-title="Portugal",TVI
            http://stream/tvi.m3u8
        """.trimIndent()

        val channels = M3u.parse(text).channels

        assertEquals(2, channels.size)
        assertEquals("SIC", channels[0].name)
        assertEquals("http://x/logo.png", channels[0].logo)
        assertEquals("Portugal", channels[0].group)
        assertEquals("http://stream/tvi.m3u8", channels[1].url)
        assertEquals("TVI", channels[1].name)
    }

    @Test
    fun keepsGroupsAndFallsBackWhenMissing() {
        val text = """
            #EXTM3U
            #EXTGRP:Filmes
            #EXTINF:-1,Matrix
            http://s/matrix.mkv
            #EXTINF:-1,Sem Grupo
            http://s/outro.mkv
        """.trimIndent()

        val channels = M3u.parse(text).channels

        assertEquals("Filmes", channels[0].group)
        assertTrue(channels.all { it.group == "Filmes" })
    }

    @Test
    fun skipsCommentsAndNamesBareUrls() {
        val text = """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=foo
            http://host/live/canal1.m3u8
        """.trimIndent()

        val channels = M3u.parse(text).channels

        assertEquals(1, channels.size)
        assertEquals("canal1.m3u8", channels[0].name)
        assertEquals(M3u.DEFAULT_GROUP, channels[0].group)
    }
}
