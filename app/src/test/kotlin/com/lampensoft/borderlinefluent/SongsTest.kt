package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongsTest {
    @Test fun catalogHasUniqueSongsAndNoStingsOrAmbience() {
        assertEquals(20, SONGS.size)
        val raw = SONGS.filter { it.lang == null }
        assertEquals(7, raw.size)
        assertEquals(raw.size, raw.map { it.res }.toSet().size)
        assertTrue(SONGS.filter { it.lang != null }.all { it.res == 0 && it.key.startsWith("taxi_") })
        assertEquals(SONGS.size, SONGS.map { it.key }.toSet().size)
        val notSongs = setOf(R.raw.music_win, R.raw.music_over, R.raw.music_broken, R.raw.ambience_airport)
        assertTrue(SONGS.none { it.res in notSongs })
    }

    @Test fun keysAreTheRawFileNames() {
        val raw = listOf("src/main/res/raw", "app/src/main/res/raw").map(::File).first { it.isDirectory }
        for (s in SONGS.filter { it.lang == null }) assertTrue(s.key, File(raw, "${s.key}.ogg").isFile)
    }

    @Test fun packSongsAreInTheirLanguagePack() {
        val packs = listOf("packs", "../packs").map(::File).first { it.isDirectory }
        val pack = SONGS.filter { it.lang != null }
        assertEquals(13, pack.size)
        for (s in pack) assertTrue(s.key, File(packs, "lang_${s.lang}/src/main/assets/music/${s.key}.ogg").isFile)
    }

    @Test fun aPackSongNeedsItsPackAndHintsAtIt() {
        val taxi = SONGS.single { it.key == "taxi_de" }
        assertTrue(songPlayable(taxi, setOf("taxi_de"), setOf("de")))
        assertFalse("pack removed", songPlayable(taxi, setOf("taxi_de"), setOf("fr")))
        assertFalse("not heard", songPlayable(taxi, emptySet(), setOf("de")))
        assertTrue(songPlayable(SONGS[0], setOf(SONGS[0].key), emptySet()))
        assertEquals("de", songPackHint(taxi, setOf("fr")))
        assertNull("installed: no hint, heard or not", songPackHint(taxi, setOf("de")))
        assertNull(songPackHint(SONGS[0], emptySet()))
        assertEquals("taxi_de", decodeHeard("taxi_de,gone").single())
    }

    @Test fun heardSetRoundTripsAndDropsUnknownNames() {
        val heard = setOf("music_bar", "music_menu")
        assertEquals(heard, decodeHeard(encodeHeard(heard)))
        assertEquals(emptySet<String>(), decodeHeard(null))
        assertEquals(emptySet<String>(), decodeHeard(""))
        assertEquals(setOf("music_bar"), decodeHeard("music_bar,gone"))
    }

    @Test fun menuEntryNeedsTwoHeardSongs() {
        assertFalse(showMusicMenu(emptySet()))
        assertFalse(showMusicMenu(setOf("music_menu")))
        assertTrue(showMusicMenu(setOf("music_menu", "music_bar")))
    }

    @Test fun nextHeardSkipsLockedSongsAndEnds() {
        val heard = setOf("music_menu", "music_bar", "music_thalassia")
        assertEquals("music_bar", nextHeard(heard, SONGS[0], emptySet())!!.key)
        assertEquals("music_thalassia", nextHeard(heard, SONGS[3], emptySet())!!.key)
        assertNull(nextHeard(heard, SONGS[5], emptySet()))
        val withTaxis = heard + setOf("taxi_de", "taxi_fr")
        assertEquals("a removed pack's song is skipped", "taxi_fr", nextHeard(withTaxis, SONGS[5], setOf("fr"))!!.key)
    }
}
