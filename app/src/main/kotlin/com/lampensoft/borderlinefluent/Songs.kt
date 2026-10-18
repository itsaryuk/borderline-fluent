package com.lampensoft.borderlinefluent

/**
 * A track the player can unlock and replay. [key] is the res/raw name (what is saved, so it survives id changes). Titles are proper names: not translated.
 * [lang]: a pack song, the file music/[key].ogg in that language pack ([res] 0; Packs.musicFile).
 */
data class Song(val res: Int, val title: String, val key: String, val lang: String? = null)

/** In menu order. Stings (music_win/over/broken) and ambience are not songs. */
val SONGS = listOf(
    Song(R.raw.music_menu, "Main Theme", "music_menu"),
    Song(R.raw.music_prologue_soft, "Machines Are Not Welcomed", "music_prologue_soft"),
    Song(R.raw.music_prologue_hard, "She Speaks Languages", "music_prologue_hard"),
    Song(R.raw.music_bar, "Walking on the Borderline", "music_bar"),
    Song(R.raw.music_credits, "Borderline", "music_credits"),
    Song(R.raw.music_thalassia, "Thalassia", "music_thalassia"),
    Song(R.raw.music_true_credits, "Where Are You Going", "music_true_credits"),
    // The taxi radio ([taxiSong]), one per language pack.
    Song(0, "The Knowledge", "taxi_en_gb", "en"),
    Song(0, "Every Light's a Fight", "taxi_en_us", "en"),
    Song(0, "Sure the Train Always Waits", "taxi_en_ie", "en"),
    Song(0, "Front Seat", "taxi_en_au", "en"),
    Song(0, "Feu vert sur le toit", "taxi_fr", "fr"),
    Song(0, "Elfenbein", "taxi_de", "de"),
    Song(0, "Aquí nunca es tarde", "taxi_es", "es"),
    Song(0, "Motorini", "taxi_it", "it"),
    Song(0, "Saudade no banco de trás", "taxi_pt", "pt"),
    Song(0, "Dokąd, szefie?", "taxi_pl", "pl"),
    Song(0, "Шашечки", "taxi_ru", "ru"),
    Song(0, "Місто як село", "taxi_uk", "uk"),
    Song(0, "白い手袋", "taxi_ja", "ja"),
)

fun encodeHeard(keys: Set<String>): String = keys.sorted().joinToString(",")

/** Unknown names (a song dropped later) are ignored. */
fun decodeHeard(saved: String?): Set<String> = saved.orEmpty().split(',').filter { k -> SONGS.any { it.key == k } }.toSet()

/** The menu offers the player once two songs are heard. */
fun showMusicMenu(heard: Set<String>) = heard.size >= 2

/** Heard and on the phone: a pack song needs its language pack ([installed] languages); one heard whose pack is gone shows greyed. */
fun songPlayable(song: Song, heard: Set<String>, installed: Set<String>) = song.key in heard && (song.lang == null || song.lang in installed)

/** The language whose pack a pack song needs while that pack isn't installed (the player's hint, heard or not); else null. */
fun songPackHint(song: Song, installed: Set<String>): String? = song.lang?.takeIf { it !in installed }

/** The next playable heard song after [current] in menu order, or null at the end. */
fun nextHeard(heard: Set<String>, current: Song, installed: Set<String>): Song? =
    SONGS.dropWhile { it.key != current.key }.drop(1).firstOrNull { songPlayable(it, heard, installed) }
