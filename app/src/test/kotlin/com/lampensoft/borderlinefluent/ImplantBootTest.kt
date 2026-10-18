package com.lampensoft.borderlinefluent

import org.junit.Assert.*
import java.io.File
import kotlin.random.Random
import org.junit.Test

class ImplantBootTest {
    @Test fun fatherQueueIsNeverRandomAndNeitherChoiceCanBecomeAnOfficerFactOrReaction() {
        val encounter = Encounters.fatherQueue("en") { "text:$it" }
        assertEquals("father", encounter.id)
        assertEquals("queue_father", encounter.art)
        assertFalse(Encounters.all.any { it.id == encounter.id })
        assertNull(Encounters.find(encounter.id))
        for (i in 0..1) {
            val chosen = encounter.choice(i)
            assertNull(chosen.question)
            assertTrue(chosen.fact.isEmpty())
            assertEquals(0.0, chosen.meter, 0.0)
            assertEquals(0.0, chosen.patience, 0.0)
            assertFalse(QueueScene(encounter, Gender.entries.first()).choose(i, Random(1)).asked)
        }
    }

    @Test fun bootIsInTheQueueAndNeverInTheInterview() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val ui = File(main, "kotlin/com/lampensoft/borderlinefluent/Ui.kt").readText()
        val game = File(main, "kotlin/com/lampensoft/borderlinefluent/Game.kt").readText()
        val interview = ui.substringAfter("private fun InterviewScreen").substringBefore("private fun OfficerEye")
        assertFalse(interview.contains("ImplantBootOverlay("))
        val launch = game.substringAfter("private fun launchInterview").substringBefore("private fun unit()")
        assertFalse(launch.contains("implantBoot"))
        assertFalse(launch.contains("implant_boot_seen"))
        val translation = ui.substringAfter("private fun FatherQueueTranslation").substringBefore("private fun BookingLog")
        assertTrue(translation.contains("ImplantBootOverlay(g, token)"))
        assertTrue(translation.contains("g.speakFather()"))
        assertFalse(translation.contains("scrambled"))
        val handoff = game.substringAfter("fun chooseInQueue").substringBefore("fun leaveQueue")
        assertTrue(handoff.indexOf("Encounters.FATHER") < handoff.indexOf("q.choose("))
    }

    @Test fun shakeWorksInEitherDirectionAndOffAxisIncludingWindowBoundary() {
        for (center in listOf(-7f, 0f, 15f)) for (sign in listOf(-1, 1)) for ((second, expected) in listOf(4000L to true, 4001L to false)) {
            val d = ImplantShakeDetector()
            assertFalse(d.add(0, center))
            assertFalse(d.add(100, center + sign * 10f)) // first leg: sets the direction
            for (t in listOf(400L, 700L)) assertFalse(d.add(t, center + sign * 10f))
            assertFalse(d.add(1000, center)) // first reversal
            for (t in 1250L until second step 250) assertFalse(d.add(t, center)) // still in view, no reversal
            assertEquals("$center $sign $second", expected, d.add(second, center + sign * 10f))
        }
    }

    @Test fun stillSmallAndOneSidedMotionAndLookingAwayAndBackDoNotCalibrate() {
        for (yaws in listOf(listOf(0f, -9.9f, 0f, 9.9f, 0f, -9.9f, 0f), listOf(-7f, 0f, 20f, 30f, 20f, 0f, -5f))) {
            val d = ImplantShakeDetector()
            yaws.forEachIndexed { i, yaw -> assertFalse("$yaws", d.add(i * 100L, yaw)) }
        }
    }

    @Test fun oldReversalExpiresButANewSwingWorks() {
        val d = ImplantShakeDetector()
        assertFalse(d.add(0, 0f)); assertFalse(d.add(100, -20f))
        assertFalse(d.add(200, 0f)) // first reversal
        for (t in 400L..3100 step 300) assertFalse(d.add(t, 5f)) // the face stays in view, no reversal
        assertFalse(d.add(3201, -20f)) // 3001 ms after the first
        assertTrue(d.add(3500, 0f))
    }

    @Test fun briefFacelessAndInvalidFramesAreSkippedButALongerGapStartsOver() {
        for (yaw in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f)) {
            val d = ImplantShakeDetector()
            assertFalse(d.add(0, 0f)); assertFalse(d.add(100, -20f)); assertFalse(d.add(200, 20f))
            assertFalse(d.add(300, yaw, face = yaw != 0f))
            assertTrue(d.add(400, -20f))
        }
        val d = ImplantShakeDetector()
        assertFalse(d.add(0, 0f)); assertFalse(d.add(100, -20f)); assertFalse(d.add(200, 20f))
        assertFalse(d.add(250, -20f, face = false)); assertFalse(d.add(500, -20f, face = false))
        assertFalse(d.add(601, -20f)) // 401 ms since the last face: starts over
    }

    @Test fun flickeringBackgroundFacesDoNotCalibrate() {
        //  review: isolated detections every 300 ms, faceless frames between, each renewing the 400 ms gap.
        val d = ImplantShakeDetector()
        val yaws = mapOf(0L to 0f, 300L to -15f, 600L to 15f, 900L to -15f)
        for (t in 0L..1200 step 75) assertFalse("$t", d.add(t, yaws[t] ?: 0f, face = t in yaws))
    }

    @Test fun aRealShakeWithBlurredFramesStillConfirms() {
        val d = ImplantShakeDetector()
        val blurred = setOf(5, 6, 12) // fast motion: ML Kit misses the face
        assertTrue((0..25).any { i ->
            val t = i * 70L
            d.add(t, (20 * kotlin.math.sin(2 * Math.PI * 2.5 * t / 1000)).toFloat(), face = i !in blurred)
        })
    }

    @Test fun duplicateAndOutOfOrderFramesCannotSupplyTheOtherExtreme() {
        val d = ImplantShakeDetector()
        assertFalse(d.add(0, 0f)); assertFalse(d.add(100, -20f)); assertFalse(d.add(200, 20f))
        assertFalse(d.add(200, -20f))
        assertFalse(d.add(199, -20f))
        assertTrue(d.add(300, -20f))
    }

    /** 20 ms samples (SENSOR_DELAY_GAME): gravity [g] on [gAxis] plus [linear] on [axis]; true once detected. */
    private fun shaken(gAxis: Int, g: Float, axis: Int, ms: Int, linear: (Long) -> Float): Boolean {
        var s = PhoneShakeState()
        for (t in 0L..ms step 20) {
            val v = FloatArray(3); v[gAxis] += g; v[axis] += if (t < 500) 0f else linear(t - 500)
            s = phoneShake(s, t, v[0], v[1], v[2])
            if (s.detected) return true
        }
        return false
    }

    @Test fun oneFirmWristShakeTriggersInAnyOrientationIncludingSidewaysToGravity() {
        // A 4 Hz shake with 1 g peaks, half a second: sideways shakes barely change |a| and used to miss.
        for (gAxis in 0..2) for (g in listOf(-9.80665f, 9.80665f)) for (axis in 0..2)
            assertTrue("g$gAxis$g a$axis", shaken(gAxis, g, axis, 1000) { t ->
                if (t > 500) 0f else (9.8 * kotlin.math.sin(2 * Math.PI * 4 * t / 1000)).toFloat() })
    }

    @Test fun walkingTiltingSustainedAccelerationAndSingleBumpsDoNotTrigger() {
        for (axis in 0..2) {
            // Walking: about 0.4 g at 2 Hz plus noise, for 10 s.
            assertFalse(shaken(2, 9.80665f, axis, 10_000) { t ->
                (4 * kotlin.math.sin(2 * Math.PI * 2 * t / 1000) + (t % 60 - 30) / 30.0).toFloat() })
            assertFalse(shaken(2, 9.80665f, axis, 3000) { 15f }) // a single sustained excursion
            assertFalse(shaken(2, 9.80665f, axis, 3000) { t -> if (t in 100L..120L) 25f else 0f }) // a bump
        }
        // Turning the phone from flat to upright in half a second.
        var s = PhoneShakeState()
        for (t in 0L..2000 step 20) {
            val angle = (t.coerceIn(500, 1000) - 500) / 500.0 * Math.PI / 2
            s = phoneShake(s, t, 0f, (9.80665 * kotlin.math.sin(angle)).toFloat(), (9.80665 * kotlin.math.cos(angle)).toFloat())
            assertFalse(s.detected)
        }
        var still = PhoneShakeState()
        for (t in 0L..2000 step 20) { still = phoneShake(still, t, 25f, 0f, 0f); assertFalse(still.detected) }
    }

    @Test fun phonePeaksExpireAndRejectChatterInvalidAndOutOfOrderSamples() {
        val rest = phoneShake(PhoneShakeState(), 0, 0f, 0f, 9.8f)
        val peak = phoneShake(rest, 100, 0f, 12f, 9.8f)
        val valley = phoneShake(peak, 120, 0f, 0f, 9.8f)
        assertFalse(peak.detected)
        assertFalse(phoneShake(valley, 140, 0f, 12f, 9.8f).detected) // 40 ms after the peak: chatter
        assertFalse(phoneShake(valley, 1101, 0f, 12f, 9.8f).detected) // the first peak expired
        for (gap in listOf(160L, 600L, 1100L)) assertTrue(phoneShake(valley, gap, 0f, 12f, 9.8f).detected)
        assertEquals(valley, phoneShake(valley, 120, 0f, 12f, 9.8f))
        assertEquals(valley, phoneShake(valley, 119, 0f, 12f, 9.8f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val reset = phoneShake(valley, 150, invalid, 0f, 0f)
            assertFalse(phoneShake(phoneShake(reset, 200, 0f, 12f, 9.8f), 300, 0f, 0f, 9.8f).detected)
        }
    }

    @Test fun wakeStringsArePresentInAllThirteenLocales() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        for (locale in listOf("", "ar", "de", "es", "fr", "it", "ja", "pl", "pt", "ru", "tr", "uk", "zh")) {
            val dir = if (locale.isEmpty()) "values" else "values-$locale"
            val nodes = factory.newDocumentBuilder().parse(File(main, "res/$dir/strings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .associate { it.getAttribute("name") to it.textContent }
            for (key in listOf("implant_boot_shake", "implant_boot_calibrated", "implant_boot_done"))
                assertTrue("$locale/$key", strings.getValue(key).isNotBlank())
            assertEquals("$locale: confirmation followed by explanation", 2,
                strings.getValue("implant_boot_calibrated").split("\\n").size)
        }
    }
}
