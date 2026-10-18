package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StoryPacingTest {
    @Test fun prologueReplayLivesInSettingsAndReturnsToMenuWithoutAdvancingCareer() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val ui = File(main, "kotlin/com/lampensoft/borderlinefluent/Ui.kt").readText()
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val start = ui.substringAfter("private fun StartScreen(").substringBefore("private fun DestinationScreen(")
        val settings = ui.substringAfter("private fun SettingsScreen(").substringBefore("private fun LanguagesScreen(")
        assertFalse(start.contains("replayPrologue"))
        assertFalse(start.contains("R.string.prologue"))
        assertTrue(settings.contains("Row(Modifier.fillMaxWidth().clickable { g.replayPrologue() }"))
        assertTrue(settings.contains("Text(stringResource(R.string.prologue)"))
        val replay = game.substringAfter("fun replayPrologue() {").substringBefore("val act1Stage")
        assertTrue(replay.contains("if (!careerReady || screen != Screen.SETTINGS) return"))
        assertTrue(replay.contains("replayingPrologue = true; screen = Screen.INTRO"))
        assertFalse(replay.contains("saveCareer"))
        val finish = game.substringAfter("fun afterIntro() {").substringBefore("if (act1Stage == Act1Stage.PROLOGUE)")
        assertTrue(finish.contains("if (replayingPrologue) { replayingPrologue = false; screen = Screen.START; return }"))
        assertFalse(finish.contains("saveCareer"))
        assertTrue(ui.contains("else if (g.replayingPrologue) replayFrame++"))
    }

    @Test fun valeryKeepsHerIdentityHiddenUntilHerIntroduction() {
        val turns = PROLOGUE_FRAMES.flatten()
        val introduction = turns.indexOfFirst { it.text == R.string.prologue_valery_2 }
        assertTrue(introduction >= 0)
        assertEquals(6, turns.take(introduction).count { it.speaker == PrologueSpeaker.STRANGER })
        assertFalse(turns.take(introduction).any { it.speaker == PrologueSpeaker.VALERY })
        assertEquals(PrologueSpeaker.VALERY, turns[introduction].speaker)
        assertFalse(turns.drop(introduction).any { it.speaker == PrologueSpeaker.STRANGER })
        assertEquals("Une inconnue", PrologueSpeaker.STRANGER.label("Une inconnue", "VOUS"))
        assertEquals("Valery", PrologueSpeaker.VALERY.label("Une inconnue", "VOUS"))
        assertEquals("VOUS", PrologueSpeaker.YOU.label("Une inconnue", "VOUS"))
        assertTrue(PrologueSpeaker.STRANGER.isValery)
        assertTrue(PrologueSpeaker.VALERY.isValery)
        assertFalse(PrologueSpeaker.YOU.isValery)
        assertFalse(PrologueSpeaker.NARRATOR.isValery)
    }

    @Test fun epilogueFrame4OffersTryOrWalkAwayAndOnlyTheSureBranchWalksAway() {
        val s = EPILOGUE_TRY_SCRIPT
        assertEquals(listOf(R.string.epi_try), (s[0] as WhyTurns).turns.map { it.text })
        val first = (s[1] as WhyChoice).options
        assertEquals(listOf(R.string.epi_choice_try, R.string.epi_choice_walk), first.map { it.label })
        assertTrue(first[0].ends && !first[0].walksAway)
        assertFalse(first[1].ends)
        assertEquals(R.string.epi_walk_sure_q, first[1].reply.last().text)
        assertEquals(PrologueSpeaker.MAN_FRIEND, first[1].reply.last().speaker)
        val second = (s[2] as WhyChoice).options
        assertEquals(listOf(R.string.epi_choice_sure, R.string.epi_choice_back), second.map { it.label })
        assertTrue(second[0].ends && second[0].walksAway)
        assertEquals(listOf(R.string.epi_walk_bye, R.string.epi_walk_reach), second[0].reply.takeLast(2).map { it.text })
        assertTrue(second[1].ends && !second[1].walksAway)
    }

    @Test fun epilogueFrame3OffersTheChosenOneQuestionOrGoingOnAndBothEndTheFrame() {
        val s = EPILOGUE_CHOSEN_SCRIPT
        assertEquals(R.string.epi_rollout, epilogueFrames(true)[3].last().text)
        assertEquals(1, s.size)
        val options = (s[0] as WhyChoice).options
        assertEquals(listOf(R.string.epi_chosen, R.string.epi_go_on), options.map { it.label })
        assertTrue(options.all { it.ends && !it.walksAway })
        assertEquals(listOf(R.string.epi_chosen, R.string.epi_chosen_no), options[0].reply.map { it.text })
        assertEquals(listOf(PrologueSpeaker.YOU, PrologueSpeaker.MAN_FRIEND), options[0].reply.map { it.speaker })
        assertEquals(listOf(R.string.epi_go_on), options[1].reply.map { it.text })
    }

    @Test fun epilogueFramesAndTestBoundaryMatchTheScript() {
        val frames = epilogueFrames(true)
        assertEquals(listOf("epi_scanner", "epi_flash", "story_building_empty", "story_building",
            "story_building", "story_building_empty", "epi_after", "epi_after"), EPILOGUE_ART)
        assertEquals(listOf(listOf(R.string.epi_scan), listOf(R.string.epi_burn), listOf(R.string.epi_wake),
            listOf(R.string.epi_intro, R.string.epi_what, R.string.epi_scanner, R.string.epi_valery, R.string.epi_rollout),
            listOf(R.string.epi_try), emptyList(),
            listOf(R.string.epi_pass, R.string.epi_copy, R.string.epi_airports, R.string.epi_who, R.string.epi_free)),
            frames.map { it.map(PrologueTurn::text) })
        assertEquals(R.string.epi_fail, epilogueFrames(false)[6].first().text)
        assertEquals(frames[6].drop(1), epilogueFrames(false)[6].drop(1))
        fun debrief(passed: Boolean, doses: Int?) = epilogueFrames(passed, doses)[6].first().text
        assertEquals(R.string.epi_pass_dosed, debrief(true, 2))
        assertEquals(R.string.epi_pass_plain, debrief(true, 0))
        assertEquals(R.string.epi_fail_dosed, debrief(false, 1))
        assertEquals(R.string.epi_fail, debrief(false, 0))
        assertEquals(R.string.epi_pass, debrief(true, null)) // saves from before doses were kept
        assertEquals(R.string.epi_fail, debrief(false, null))
        assertEquals(listOf(PrologueSpeaker.YOU, PrologueSpeaker.MAN_FRIEND), (EPILOGUE_LEAVE_SCRIPT[0] as WhyTurns).turns.map { it.speaker })
        assertTrue((EPILOGUE_LEAVE_SCRIPT[1] as WhyChoice).options.any { it.walksAway })
        assertEquals(listOf(R.string.epi_choice_sure, R.string.epi_choice_stay), (EPILOGUE_LEAVE_SCRIPT[1] as WhyChoice).options.map { it.label })
        assertEquals(listOf(PrologueSpeaker.MAN_STRANGER, PrologueSpeaker.YOU, PrologueSpeaker.MAN_FRIEND,
            PrologueSpeaker.YOU, PrologueSpeaker.MAN_FRIEND), frames[3].map { it.speaker })
        val intro = frames[3].first()
        assertEquals(PrologueSpeaker.MAN_STRANGER, storyboardSpeaker(intro, complete = false))
        assertEquals(PrologueSpeaker.MAN_FRIEND, storyboardSpeaker(intro, complete = true))
        assertEquals("UN INCONNU", PrologueSpeaker.MAN_STRANGER.label("woman", "you", "UN INCONNU", "AMI"))
        assertEquals("AMI", PrologueSpeaker.MAN_FRIEND.label("woman", "you", "UN INCONNU", "AMI"))
        assertTrue(frames.flatten().filter { it.speaker in setOf(PrologueSpeaker.MAN_STRANGER,
            PrologueSpeaker.MAN_FRIEND) }.all { it.side == PrologueSide.LEFT })
        assertEquals(1, frames.flatten().count { it.text == R.string.epi_free })
    }

    @Test fun storyTestSkipsTheAssociateAndUsesOfficeArtThroughTheChoice() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val room = game.substringAfter("private fun enterRoom(").substringBefore("private suspend fun associate(")
        assertTrue(room.indexOf("if (storyMission) machineView = true") in 0 until room.indexOf("scope.launch"))
        val intro = room.substringAfter("val (line, lineEn) = if (storyMission) {")
        val storyIntro = intro.substringBefore("} else {")
        assertTrue(storyIntro.contains("ROOM.getValue(destination.lang).machine to ROOM.getValue(sub).machine"))
        assertFalse(storyIntro.contains("associate"))
        val airportIntro = intro.substringAfter("} else {").substringBefore("officerText = line")
        assertTrue(airportIntro.contains("associate(myRun)"))
        assertTrue(airportIntro.contains("associate.greet."))
        val ui = File(main, "kotlin/com/lampensoft/borderlinefluent/Ui.kt").readText()
        assertTrue(ui.contains("rememberAsset(if (g.storyMission) \"eye_machine_story\" else \"eye_machine_desk\")"))
        val finale = ui.substringAfter("private fun FinaleScreen(").substringBefore("private val CREDITS")
        assertTrue(finale.contains("rememberAsset(EPILOGUE_ART[7])"))
        for (art in listOf("eye_machine_story", "eye_machine_desk", "epi_after"))
            assertTrue(File(main, "assets/art/$art.webp").isFile)
    }

    @Test fun everyDeliveryExplainsItsOwnPerkAfterInstallationAndBeforeUsaUnlock() {
        val base = listOf(R.string.upgrade_courier, R.string.upgrade_unpack,
            R.string.upgrade_flash, R.string.upgrade_destroy)
        assertEquals(base + R.string.upgrade_recall, upgradeDeliveryLines(1))
        assertEquals(listOf(R.string.upgrade_courier, R.string.upgrade_courier_eye, R.string.upgrade_unpack,
            R.string.upgrade_flash, R.string.upgrade_destroy, R.string.upgrade_accent), upgradeDeliveryLines(2))
        assertEquals(base + listOf(R.string.upgrade_clean, R.string.upgrade_clear_gauge, R.string.upgrade_usa_open, R.string.usa_si_note), upgradeDeliveryLines(3))
    }

    @Test fun eachCourierHasItsOwnPictureAndTheDeviceStepsShareOne() {
        assertEquals("upgrade_courier", upgradeArt(1, 0))
        assertEquals(listOf("upgrade_courier_2", "upgrade_courier_2", "upgrade_box_airport"), listOf(upgradeArt(2, 0), upgradeArt(2, 1), upgradeArt(2, 2)))
        assertEquals(listOf("upgrade_courier_3", "upgrade_box_airport"), listOf(upgradeArt(3, 0), upgradeArt(3, 1)))
        assertEquals("upgrade_box_airport", upgradeArt(1, 1))
    }

    @Test fun prologueVisitsThalassiaThenValeryAndGoesHome() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val ui = File(main, "kotlin/com/lampensoft/borderlinefluent/Ui.kt").readText()
            .substringAfter("private fun IntroScreen").substringBefore("internal val EPILOGUE_ART")
        for ((frame, art) in listOf(1 to "prologue_1_square", 2 to "prologue_2_crowd",
            3 to "prologue_2b_smashed", 4 to "prologue_3_embassy", 14 to "prologue_6_trial", 15 to "prologue_7_phone",
            16 to "prologue_5_airport", 17 to "prologue_5_airport"))
            assertTrue(ui.contains("$frame -> \"$art\""))
        assertTrue(ui.contains("in 5..13 -> \"prologue_4_valery\""))
        assertTrue(ui.contains("0 -> \"prologue_0_arrival\""))
        val expected = listOf(
            "NARRATOR:friend", "NARRATOR:holiday", "NARRATOR:stolen", "NARRATOR:smashed", "NARRATOR:embassy",
            "NARRATOR:valery_1 STRANGER:valery_first YOU:valery_last STRANGER:valery_angry",
            "STRANGER:valery_subscription_1 YOU:valery_subscription_2",
            "STRANGER:valery_uses_1 YOU:valery_uses_2",
            "STRANGER:valery_chip_1 YOU:valery_chip_2 STRANGER:valery_chip_3 NARRATOR:realize",
            "VALERY:valery_2", "YOU:valery_home_1 VALERY:valery_home_2",
            "YOU:valery_free_1 VALERY:valery_free_2 YOU:valery_free_3 VALERY:valery_free_4",
            "YOU:valery_data_1 VALERY:valery_data_2", "YOU:valery_chains_1 VALERY:valery_chains_2",
            "NARRATOR:implant", "VALERY:phone_1", "NARRATOR:sameday", "NARRATOR:home")
        val resources = File(main, "res/values/strings.xml").readText()
        // Map generated resource IDs to names without needing an Android Context.
        val names = Regex("<string name=\"(prologue_[^\"]+)\"").findAll(resources).associate {
            val name = it.groupValues[1]
            R.string::class.java.getField(name).getInt(null) to name.removePrefix("prologue_")
        }
        assertEquals(expected, PROLOGUE_FRAMES.map { turns ->
            turns.joinToString(" ") { "${it.speaker}:${names.getValue(it.text)}" }
        })
        assertTrue(ui.contains("remember(frame) { mutableIntStateOf(0) }"))
        assertTrue(ui.contains("else if (turnIndex < turns.lastIndex) turnIndex++"))
        assertTrue(ui.contains("if (frame == 17) g.afterIntro()"))
        assertTrue(ui.contains(".coerceIn(0, 17)"))
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val advance = game.substringAfter("fun advancePrologue").substringBefore("fun advanceAct1Cutscene")
        assertTrue(advance.contains("frame !in 1..17"))
        assertTrue(ui.contains("else if (g.replayingPrologue) replayFrame++"))
        assertTrue(ui.contains("else g.advancePrologue(frame + 1)"))
        assertFalse(ui.contains("prologue_why"))
        assertFalse(ui.contains("prologue_letter"))
        assertFalse(ui.contains("prologue_4_letter"))
        for (art in listOf("prologue_1_photo", "prologue_2_cafe", "prologue_3_street", "prologue_4_clinic", "prologue_4_letter")) {
            assertFalse(ui.contains(art))
            assertFalse(File(main, "assets/art/$art.webp").exists())
        }
        assertTrue(ui.contains("delay(9)"))
        assertTrue(ui.contains("background(Color.Black)"))
    }
}
