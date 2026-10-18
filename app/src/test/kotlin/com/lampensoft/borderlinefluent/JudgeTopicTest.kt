package com.lampensoft.borderlinefluent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JudgeTopicTest {
    private val country = Destination.forLanguage("en")
    private val story = Backstory.BENCH.copy(encounter = QueueEncounter("lost_child", 0, asked = true))
    private val answer = "No, I didn't see any child."
    private class Fake(vararg replies: String) {
        private val replies = ArrayDeque(replies.toList())
        val users = mutableListOf<String>()
        val ask: OneShot = { _, user, _ -> users += user; this.replies.removeFirst() }
    }
    private fun classify(fact: Int, matches: Boolean = true) =
        "FACT=$fact MATCHES=${if (matches) "yes" else "no"} ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no"
    private fun judge(fake: Fake, cover: Backstory = story) = Judge(country, cover, fake.ask, LangId { listOf("en") })

    @Test fun missingOrDifferentFactFallsBackThroughCompareAndConfirm() = runBlocking {
        for (fact in listOf(0, 1)) {
            val fake = Fake(classify(fact), "yes", "no")
            val v = judge(fake).judge("Did you see a child?", answer, FactId.ENCOUNTER)
            assertTrue(v.check.contradiction)
            assertEquals(FactId.ENCOUNTER, v.fact)
            assertEquals(listOf(compareQuestion(story.fact(FactId.ENCOUNTER, "en"), null, answer),
                confirmQuestion(story.fact(FactId.ENCOUNTER, "en"), null, answer)), fake.users.drop(1))
            assertEquals("yes", v.step2)
            assertEquals("no", v.step3)
        }
    }

    @Test fun encounterMismatchGoesStraightToConfirmation() = runBlocking {
        for (encounter in listOf("lost_child", "old_form")) for (fact in listOf(0, 1)) {
            val cover = story.copy(encounter = QueueEncounter(encounter, 0, asked = true))
            val reply = if (encounter == "lost_child") answer else "No, nobody asked me anything."
            for (confirmation in listOf("no", "yes", "garbage")) {
                val fake = Fake(classify(fact, false), confirmation)
                val v = judge(fake, cover).judge("What happened in the queue?", reply, FactId.ENCOUNTER)
                assertEquals(confirmation == "no", v.check.contradiction)
                assertEquals(FactId.ENCOUNTER, v.fact)
                assertEquals(listOf(confirmQuestion(cover.fact(FactId.ENCOUNTER, "en"), null, reply)), fake.users.drop(1))
                assertEquals("", v.step2)
                assertEquals(confirmation, v.step3)
            }
        }
    }

    @Test fun encounterTruthAndUnansweredKeepGeneralFallback() = runBlocking {
        for (fact in listOf(0, 1)) {
            val truth = Fake(classify(fact), "no")
            assertFalse(judge(truth).judge("Did you see a child?", "Yes, I helped a child.", FactId.ENCOUNTER).check.contradiction)
            assertEquals(compareQuestion(story.fact(FactId.ENCOUNTER, "en"), null, "Yes, I helped a child."), truth.users[1])
        }
        for (answered in listOf("no", "garbage")) {
            val fake = Fake(classify(0, false).replace("ANSWERED=yes", "ANSWERED=$answered"), "no")
            assertFalse(judge(fake).judge("Q?", answer, FactId.ENCOUNTER).check.contradiction)
            assertEquals(compareQuestion(story.fact(FactId.ENCOUNTER, "en"), null, answer), fake.users[1])
        }
    }

    @Test fun implantOverridesDeskTrickOnlyForPassingOrDetailed() = runBlocking {
        for ((field, implant) in listOf("passing" to Implant.PASSING, "detailed" to Implant.DETAILED,
                "none" to Implant.NONE, "garbage" to Implant.NONE, "" to Implant.NONE)) {
            for (trick in listOf("yes", "no")) {
                val raw = classify(0).replace("TRICK=no", "TRICK=$trick") +
                    if (field.isEmpty()) "" else " IMPLANT=$field"
                val fake = Fake(raw)
                val v = judge(fake).judge("How do you speak English?", "My implant helps me.")
                assertEquals(implant, v.check.implant)
                assertEquals(trick == "yes" && implant == Implant.NONE, v.check.trick)
                assertEquals(raw, v.raw)
                assertEquals(1, fake.users.size)
            }
        }
    }

    @Test fun implantYesIsPassingNotTrick() = runBlocking {
        val v = judge(Fake(classify(0) + " IMPLANT=yes")).judge("How do you speak English?", "My implant helps me.")
        assertEquals(Implant.PASSING, v.check.implant)
        assertFalse(v.check.trick)
    }

    private val days = story.factIds().indexOf(FactId.DAYS) + 1

    @Test fun implantRemarkOnTrueAnswerIsJudgedWithoutTheRemark() = runBlocking {
        val fake = Fake(classify(days, false).replace("TRICK=no", "TRICK=yes") + " IMPLANT=yes", classify(days) + " IMPLANT=none")
        val v = judge(fake).judge("How many days?", "Five days. By the way, my implant sits behind my ear.", FactId.DAYS)
        assertFalse(v.check.contradiction)
        assertFalse(v.check.trick)
        assertEquals(Implant.PASSING, v.check.implant)
        assertEquals(2, fake.users.size) // one extra classify, no compare / confirm
        assertTrue(fake.users[1].endsWith("Traveler answered: \"Five days.\""))
    }

    @Test fun truthfulAnswerToThePreviousQuestionIsALateReplyNotATopicMiss() = runBlocking {
        val purpose = story.factIds().indexOf(FactId.PURPOSE) + 1
        val fake = Fake(classify(purpose), "yes", "no")
        val j = judge(fake)
        val v = j.judge("What is your name?", "A conference.", FactId.NAME_BORN, previous = "Why are you here?" to FactId.PURPOSE)
        assertFalse(v.check.contradiction)
        assertEquals(1, fake.users.size) // no topic compare/confirm
    }

    @Test fun implantRemarkOnFalseAnswerStaysAContradiction() = runBlocking {
        val fake = Fake(classify(days, false) + " IMPLANT=passing", classify(days, false) + " IMPLANT=none", "no")
        val v = judge(fake).judge("How many days?", "Twenty days. My implant helps me.", FactId.DAYS)
        assertTrue(v.check.contradiction)
        assertEquals(Implant.PASSING, v.check.implant)
        assertTrue(fake.users[1].endsWith("Traveler answered: \"Twenty days.\""))
    }

    @Test fun implantOnlyAnswerIsNoContradiction() = runBlocking {
        val fake = Fake(classify(days, false) + " IMPLANT=detailed")
        assertFalse(judge(fake).judge("Why are you here?", "My implant translates you right now.", FactId.PURPOSE).check.contradiction)
        assertEquals(1, fake.users.size)
    }

    @Test fun withoutImplantTalkDropsOnlyThoseSentences() {
        assertEquals("Четырнадцать дней.", withoutImplantTalk("Четырнадцать дней. Кстати, мой имплант сидит за ухом, слушает вас."))
        assertEquals("我住在西雅图。", withoutImplantTalk("我住在西雅图。没有芯片我听不懂。"))
        assertEquals("Five days. Fine.", withoutImplantTalk("Five days. Fine."))
        assertEquals("", withoutImplantTalk("My implant helps."))
        for ((l, r) in JudgeCases.implantRemark) assertEquals(l, "", withoutImplantTalk(r))
        assertEquals(Language.all.map { it.code }.toSet(), JudgeCases.implantRemark.keys)
    }

    @Test fun roomImplantOverridesTrickOnlyForYes() = runBlocking {
        for (implant in listOf("yes", "no", "garbage", "")) for (trick in listOf("yes", "no")) {
            val raw = "FEELING=no EVASIVE=no ADMIT=no LANGUAGE=yes CLARITY=2 TRICK=$trick" +
                if (implant.isEmpty()) "" else " IMPLANT=$implant"
            val fake = Fake(raw)
            val check = RoomCheck.parse(fake.ask(roomPrompt(country), "My implant helps me.", Game.ROOM_TOKENS))
            assertEquals(implant == "yes", check.implant)
            assertEquals(trick == "yes" && implant != "yes", check.trick)
            assertEquals(1, fake.users.size)
        }
    }

    @Test fun visitorHomeOverridesWrongStayFactOnlyAfterConfirmation() = runBlocking {
        val fake = Fake(classify(4, false), "yes", "no")
        val v = judge(fake, Backstory.BENCH_ARRIVAL).judge("Where do you live?", "I live here, in Berlin.", FactId.HOME)
        assertEquals(FactId.HOME, v.fact)
        assertTrue(v.check.contradiction)
        assertEquals(3, fake.users.size) // original confirmation, then the topic confirmation (no compare)
        assertEquals(confirmQuestion(Backstory.BENCH_ARRIVAL.fact(FactId.HOME, "en"), null, "I live here, in Berlin."), fake.users[2])
    }

    @Test fun flaggedMismatchOnOtherFactSkipsCompareAndConfirmsTopic() = runBlocking {
        // "Where do you live? -> In Canada.": the classifier pins the lie on FROM (2), step 3 says both can be true.
        val fake = Fake(classify(days, false), "yes", "no")
        val v = judge(fake, Backstory.BENCH_ARRIVAL).judge("Where do you live?", "In Canada.", FactId.HOME)
        assertTrue(v.check.contradiction)
        assertEquals(FactId.HOME, v.fact)
        assertEquals(3, fake.users.size)
        assertEquals(confirmQuestion(Backstory.BENCH_ARRIVAL.fact(FactId.HOME, "en"), null, "In Canada."), fake.users[2])
        assertEquals("", v.step2)
        // The device reply had ANSWERED=no: the pin on FROM is dropped unconfirmed, the topic's confirm still applies.
        val unanswered = Fake(classify(days, false).replace("ANSWERED=yes", "ANSWERED=no"), "no")
        val u = judge(unanswered, Backstory.BENCH_ARRIVAL).judge("Where do you live?", "In Canada.", FactId.HOME)
        assertTrue(u.check.contradiction)
        assertEquals(FactId.HOME, u.fact)
        assertEquals(2, unanswered.users.size)
        assertEquals("no", v.step3)
    }

    @Test fun unansweredPinOnAnotherFactIsNoiseJudgedAgainstTheTopic() = runBlocking {
        // Device: "Путешествую." to "What brings you here?" came back FACT=1 (NAME_BORN) MATCHES=no ANSWERED=no.
        val ru = Destination.forLanguage("ru")
        val raw = "FACT=1 MATCHES=no ANSWERED=no LANGUAGE=yes CLARITY=1 TRICK=no ODD=no IMPLANT=none"
        val fake = Fake(raw, "yes") // both can be true: travelling and a conference
        val v = Judge(ru, Backstory.BENCH_ARRIVAL, fake.ask, LangId { listOf("ru") })
            .judge("Принято. Что привело Вас к нам?", "Путешествую.", FactId.PURPOSE)
        assertFalse(v.check.contradiction)
        assertNull(v.fact)
        assertEquals(listOf(confirmQuestion(Backstory.BENCH_ARRIVAL.fact(FactId.PURPOSE, "ru"), null, "Путешествую.")), fake.users.drop(1))
        // The same noisy pin with a real lie ("In Canada." for a Denver home): the topic's confirm still catches it.
        val canada = Fake(raw, "no")
        val c = Judge(ru, Backstory.BENCH_ARRIVAL, canada.ask, LangId { listOf("ru") }).judge("Где Вы живёте?", "В Канаде.", FactId.HOME)
        assertTrue(c.check.contradiction)
        assertEquals(FactId.HOME, c.fact)
        // An off-topic number keeps its pin: the DAYS count rule catches it whatever the topic's confirm says.
        val away = Fake(raw.replace("FACT=1", "FACT=${Backstory.BENCH_ARRIVAL.factIds().indexOf(FactId.DAYS) + 1}"), "yes", "yes")
        val d = judge(away, Backstory.BENCH_ARRIVAL).judge("Why did you travel?", "I was away for 14 days.", FactId.PURPOSE)
        assertTrue(d.check.contradiction)
        assertEquals(FactId.DAYS, d.fact)
        // An answered lie on the asked fact keeps the usual confirm step.
        val lie = Fake("FACT=2 MATCHES=no ANSWERED=yes LANGUAGE=yes CLARITY=2 TRICK=no", "no")
        val l = judge(lie, Backstory.BENCH_ARRIVAL).judge("Why are you here?", "I'm here on holiday.", FactId.PURPOSE)
        assertTrue(l.check.contradiction)
        assertEquals(FactId.PURPOSE, l.fact)
    }

    // Step 1 pinned on the asked fact with ANSWERED=no: a second look without the question reads FACT from the answer.
    private val arrival = Backstory.BENCH_ARRIVAL
    private fun pinned(fact: FactId, matches: Boolean = false, answered: Boolean = false) =
        "FACT=${arrival.factIds().indexOf(fact) + 1} MATCHES=${if (matches) "yes" else "no"} ANSWERED=${if (answered) "yes" else "no"} LANGUAGE=yes CLARITY=2 TRICK=no"

    private val noFact = "FACT=0 MATCHES=yes ANSWERED=no LANGUAGE=yes CLARITY=2 TRICK=no"

    @Test fun secondLookFindingNoFactIsADodgeOnlyAfterTheTopicConfirm() = runBlocking {
        val a = "It is a test version."
        val fake = Fake(pinned(FactId.FROM), noFact, "yes")
        val v = judge(fake, arrival).judge("Where are you flying in from?", a, FactId.FROM)
        assertFalse(v.check.contradiction)
        assertFalse(v.check.answered)
        assertNull(v.fact)
        assertEquals(listOf("Traveler said: \"$a\"", confirmQuestion(arrival.fact(FactId.FROM, "en"), null, a)), fake.users.drop(1))
        // A lie on the asked fact (Fable P1): "on holiday" for a wedding stays a lie when the second look finds no fact.
        val h = "I'm here on holiday."
        val lie = Fake(pinned(FactId.PURPOSE), noFact, "no")
        val l = judge(lie, arrival).judge("What is the purpose of your visit?", h, FactId.PURPOSE)
        assertTrue(l.check.contradiction)
        assertEquals(FactId.PURPOSE, l.fact)
        assertEquals(listOf("Traveler said: \"$h\"", confirmQuestion(arrival.fact(FactId.PURPOSE, "en"), null, h)), lie.users.drop(1))
    }

    @Test fun aWrongValueRepinnedOnAMatchingFactIsStillJudgedOnTheTopic() = runBlocking {
        // Fable P1: a Denver home flying in from Canada; "In Canada." to "Where do you live?" re-pins on FROM, MATCHES=yes.
        val cover = arrival.copy(home = "Denver", from = "Canada")
        val a = "In Canada."
        val fake = Fake(pinned(FactId.HOME), pinned(FactId.FROM, matches = true), "yes", "no")
        val v = judge(fake, cover).judge("Where do you live?", a, FactId.HOME)
        assertTrue(v.check.contradiction)
        assertEquals(FactId.HOME, v.fact)
        val home = cover.fact(FactId.HOME, "en")
        assertEquals(listOf(compareQuestion(home, null, a), confirmQuestion(home, null, a)), fake.users.drop(2)) // compare first, no shortcut
        // The compare finding no conflict ends it: no confirm.
        val clear = Fake(pinned(FactId.HOME), pinned(FactId.FROM, matches = true), "no")
        assertFalse(judge(clear, cover).judge("Where do you live?", a, FactId.HOME).check.contradiction)
        assertEquals(3, clear.users.size)
    }

    @Test fun secondLookFindingAnotherFactJudgesThatFact() = runBlocking {
        // "At a hotel near the wedding venue." to "How long will you stay?": a true STAY answer, no topic confirm.
        val hotel = "At a hotel near the wedding venue."
        // A true STAY answer, then the DAYS compare: a hotel says nothing about a length.
        val fine = Fake(pinned(FactId.DAYS), pinned(FactId.STAY, matches = true), "no")
        val f = judge(fine, arrival).judge("How long will you stay?", hotel, FactId.DAYS)
        assertFalse(f.check.contradiction)
        assertEquals(FactId.STAY, f.fact)
        assertEquals(listOf(compareQuestion(arrival.fact(FactId.DAYS, "en"), null, hotel)), fine.users.drop(2))
        // A compare that flags it still needs the confirm's "no".
        val both = Fake(pinned(FactId.DAYS), pinned(FactId.STAY, matches = true), "yes", "yes")
        assertFalse(judge(both, arrival).judge("How long will you stay?", hotel, FactId.DAYS).check.contradiction)
        assertEquals(4, both.users.size)
        // "Seven days." to "Who invited you?": a true DAYS answer.
        val seven = Fake(pinned(FactId.HOST), pinned(FactId.DAYS, matches = true), "no")
        val s = judge(seven, arrival).judge("Who invited you?", "Seven days.", FactId.HOST)
        assertFalse(s.check.contradiction)
        assertEquals(FactId.DAYS, s.fact)
        assertEquals(3, seven.users.size)
        // A wrong stay the look calls answered is still a lie, of the stay: step 3 confirms against STAY, never the asked DAYS.
        val wrong = Fake(pinned(FactId.DAYS), pinned(FactId.STAY, answered = true), "no")
        val w = judge(wrong, arrival).judge("How long will you stay?", "With a friend.", FactId.DAYS)
        assertTrue(w.check.contradiction)
        assertEquals(FactId.STAY, w.fact)
        assertEquals(listOf(confirmQuestion(arrival.fact(FactId.STAY, "en"), null, "With a friend.")), wrong.users.drop(2))
        // With ANSWERED=no (the look has no question) the STAY compare must flag it before the STAY confirm makes it a lie.
        val stay = arrival.fact(FactId.STAY, "en")
        val noisy = Fake(pinned(FactId.DAYS), pinned(FactId.STAY), "yes", "no")
        val n = judge(noisy, arrival).judge("How long will you stay?", "With a friend.", FactId.DAYS)
        assertTrue(n.check.contradiction)
        assertEquals(FactId.STAY, n.fact)
        assertEquals(listOf(compareQuestion(stay, null, "With a friend."), confirmQuestion(stay, null, "With a friend.")), noisy.users.drop(2))
        // A STAY compare "no" drops the pin like no fact at all; the asked DAYS goes straight to its confirm (here "yes":
        // a friend says nothing against a length).
        val cleared = Fake(pinned(FactId.DAYS), pinned(FactId.STAY), "no", "yes")
        val c = judge(cleared, arrival).judge("How long will you stay?", "With a friend.", FactId.DAYS)
        assertFalse(c.check.contradiction)
        assertNull(c.fact)
        assertEquals(listOf(compareQuestion(stay, null, "With a friend."), confirmQuestion(arrival.fact(FactId.DAYS, "en"), null, "With a friend.")), cleared.users.drop(2))
    }

    @Test fun secondLookMismatchOnAnotherFactWithoutAnAnswerNeedsTheCompare() = runBlocking {
        // Device, pl desk: "Według znajomo." (a broken "at a friend's") to a STAY question, cover a friend's flat in Gdańsk:
        // FACT=4 (STAY) MATCHES=no ANSWERED=no, alone FACT=1 (NAME_BORN) MATCHES=no ANSWERED=no, step 3 "no" → a NAME_BORN lie.
        // Now the NAME_BORN compare says no: the pin drops like no fact at all and STAY goes straight to its confirm.
        val cover = arrival.copy(stayed = "a friend's flat, in Gdańsk")
        val pl = Destination.forLanguage("pl")
        val a = "Według znajomo."
        val q = "Jakie jest Pana zakwaterowanie podczas wizyty?"
        val stayQ = "Gdzie Pan będzie mieszkał?" to FactId.STAY // the officer re-asked the stay
        val alone = pinned(FactId.NAME_BORN).replace("CLARITY=2", "CLARITY=1")
        val name = compareQuestion(cover.fact(FactId.NAME_BORN, "pl"), null, a)
        val stay = cover.fact(FactId.STAY, "pl")
        for (confirm in listOf("yes", "no")) {
            val fake = Fake(pinned(FactId.STAY), alone, "no", confirm)
            val v = Judge(pl, cover, fake.ask, LangId { listOf("pl") }).judge(q, a, FactId.STAY, previous = stayQ)
            assertEquals(confirm == "no", v.check.contradiction) // only ever a STAY lie
            assertEquals(if (confirm == "no") FactId.STAY else null, v.fact)
            assertEquals(listOf(name, confirmQuestion(stay, null, a)), fake.users.drop(2)) // no STAY compare
        }
        // A mismatch re-pin on the previous question's fact is no noise: a late reply that lies is judged on that fact.
        val late = Fake(pinned(FactId.STAY), pinned(FactId.PURPOSE), pinned(FactId.PURPOSE), "no") // step 1, the late-reply check, the look
        val l = judge(late, arrival).judge("Where will you stay?", "A business conference.", FactId.STAY, previous = "Why are you here?" to FactId.PURPOSE)
        assertTrue(l.check.contradiction)
        assertEquals(FactId.PURPOSE, l.fact)
        assertEquals(4, late.users.size) // no compare on this path
    }

    @Test fun aLieOnTopicWhoseLookRepinsOnNoiseStaysALieOfTheTopic() = runBlocking {
        // Bench "lie on topic, short", 8 of 13 languages after 25784ef: "Je suis ici en vacances." for a wedding came back
        // FACT=2 (PURPOSE) MATCHES=no ANSWERED=no, alone FACT=1 (NAME_BORN) MATCHES=no ANSWERED=no; the NAME_BORN compare
        // said no, and the PURPOSE compare (Nano leans "no") cleared the lie. A dropped re-pin goes straight to the confirm.
        val fr = Destination.forLanguage("fr")
        val a = "Je suis ici en vacances."
        val fake = Fake(pinned(FactId.PURPOSE), pinned(FactId.NAME_BORN), "no", "no")
        val v = Judge(fr, arrival, fake.ask, LangId { listOf("fr") }).judge("Quel est le but de votre visite ?", a, FactId.PURPOSE)
        assertTrue(v.check.contradiction)
        assertEquals(FactId.PURPOSE, v.fact)
        assertEquals(listOf(compareQuestion(arrival.fact(FactId.NAME_BORN, "fr"), null, a), confirmQuestion(arrival.fact(FactId.PURPOSE, "fr"), null, a)),
            fake.users.drop(2))
    }

    @Test fun aLieOnTopicWhoseLookRepinsOnDaysWithoutADigitNeedsTheDaysCompare() = runBlocking {
        // Bench "lie on topic, short" (de, es, pt, ru, uk, ar): "Ich mache hier Urlaub." for a wedding came back FACT=2
        // (PURPOSE) MATCHES=no ANSWERED=no, alone FACT=3 (DAYS) MATCHES=no ANSWERED=no; the DAYS re-pin was kept unchecked,
        // its confirm said "yes" and the PURPOSE compare cleared the lie. Now the DAYS compare drops it; PURPOSE confirms.
        val de = Destination.forLanguage("de")
        val q = "Was ist der Zweck Ihres Besuchs?"
        val a = "Ich mache hier Urlaub."
        val fake = Fake(pinned(FactId.PURPOSE), pinned(FactId.DAYS), "no", "no")
        val v = Judge(de, arrival, fake.ask, LangId { listOf("de") }).judge(q, a, FactId.PURPOSE)
        assertTrue(v.check.contradiction)
        assertEquals(FactId.PURPOSE, v.fact)
        assertEquals(listOf(compareQuestion(arrival.fact(FactId.DAYS, "de"), null, a), confirmQuestion(arrival.fact(FactId.PURPOSE, "de"), null, a)),
            fake.users.drop(2))
        // A number-word length to the purpose question passes the DAYS compare and stays a DAYS lie.
        val d = "Drei Tage."
        val days = Fake(pinned(FactId.PURPOSE), pinned(FactId.DAYS), "yes", "no")
        val l = Judge(de, arrival, days.ask, LangId { listOf("de") }).judge(q, d, FactId.PURPOSE)
        assertTrue(l.check.contradiction)
        assertEquals(FactId.DAYS, l.fact)
        val text = arrival.fact(FactId.DAYS, "de")
        assertEquals(listOf(compareQuestion(text, null, d), confirmQuestion(text, null, d)), days.users.drop(2))
    }

    @Test fun secondLookKeepingTheTopicGoesToStepThreeAndCountsAsAnswered() = runBlocking {
        val hiking = arrival.copy(purpose = "a hiking trip")
        val a = "Ich bin hier für eine Wanderreise."
        val de = Destination.forLanguage("de")
        for (confirm in listOf("yes", "no")) {
            val fake = Fake(pinned(FactId.PURPOSE), pinned(FactId.PURPOSE), confirm)
            val v = Judge(de, hiking, fake.ask, LangId { listOf("de") }).judge("Was ist der Zweck Ihres Besuchs?", a, FactId.PURPOSE)
            assertEquals(confirm == "no", v.check.contradiction)
            assertEquals(confirm == "yes", v.check.answered) // cleared by step 3: it did answer
            assertEquals(FactId.PURPOSE, v.fact)
            assertEquals(3, fake.users.size)
        }
        // A malformed second look keeps today's path.
        val garbled = Fake(pinned(FactId.PURPOSE), "garbage", "no")
        val g = Judge(de, hiking, garbled.ask, LangId { listOf("de") }).judge("Was ist der Zweck Ihres Besuchs?", a, FactId.PURPOSE)
        assertTrue(g.check.contradiction)
        assertFalse(g.check.answered)
        assertEquals(3, garbled.users.size)
        // Never on an answered pin or a late reply's turf: one call, then step 3.
        val answered = Fake(pinned(FactId.PURPOSE, answered = true), "no")
        assertTrue(judge(answered, arrival).judge("Why are you here?", "On holiday.", FactId.PURPOSE).check.contradiction)
        assertEquals(2, answered.users.size)
    }

    @Test fun theCoversOwnDayCountIsNoLie() = runBlocking {
        val exact = Fake(pinned(FactId.DAYS, answered = true))
        val e = judge(exact, arrival).judge("How long will you stay?", "7 days.", FactId.DAYS)
        assertFalse(e.check.contradiction)
        assertEquals(1, exact.users.size) // no step 3
        // A digit on the DAYS question is evidence: no second look, the count judges it.
        val unanswered = Fake(pinned(FactId.DAYS))
        val u = judge(unanswered, arrival).judge("How long will you stay?", "I stay 7 days.", FactId.DAYS)
        assertFalse(u.check.contradiction)
        assertTrue(u.check.answered)
        assertEquals(1, unanswered.users.size)
        val wrong = Fake(pinned(FactId.DAYS))
        assertTrue(judge(wrong, arrival).judge("How long will you stay?", "I stay 3 days.", FactId.DAYS).check.contradiction)
        assertEquals(1, wrong.users.size)
        val other = Fake(pinned(FactId.DAYS, answered = true))
        assertTrue(judge(other, arrival).judge("How long will you stay?", "3 days.", FactId.DAYS).check.contradiction)
        assertEquals(1, other.users.size)
        // The cover's number in another unit is not the cover's count: step 3 decides.
        for (a in listOf("I'll stay 7 weeks.", "Until June 7.")) {
            val unit = Fake(pinned(FactId.DAYS, answered = true), "no")
            assertTrue(a, judge(unit, arrival).judge("How long will you stay?", a, FactId.DAYS).check.contradiction)
            assertEquals(a, 2, unit.users.size)
        }
        // The day word is per language.
        val ru = Fake(pinned(FactId.DAYS, answered = true))
        val r = Judge(Destination.forLanguage("ru"), arrival, ru.ask, LangId { listOf("ru") }).judge("Сколько вы пробудете?", "7 дней.", FactId.DAYS)
        assertFalse(r.check.contradiction)
        assertEquals(1, ru.users.size)
    }

    @Test fun unconfirmedFallbackPreservesOriginalVerdict() = runBlocking {
        for (replies in listOf(arrayOf("no"), arrayOf("yes", "yes"), arrayOf("yes", "garbage"))) {
            val fake = Fake(classify(1), *replies)
            val v = judge(fake).judge("Q?", answer, FactId.ENCOUNTER)
            assertFalse(v.check.contradiction)
            assertEquals(FactId.NAME_BORN, v.fact)
            assertEquals("", v.step2)
            assertEquals("", v.step3)
            assertEquals(1 + replies.size, fake.users.size)
        }
        val fake = Fake(classify(1, false), "no", "no")
        val v = judge(fake).judge("Q?", answer, FactId.DAYS)
        assertTrue(v.check.contradiction)
        assertEquals(FactId.NAME_BORN, v.fact)
        assertEquals("no", v.step3)
    }

    @Test fun sameFactRetainsExistingCompareAndConfirmPath() = runBlocking {
        val fake = Fake(classify(4), classify(4), "yes", "no")
        val judge = judge(fake)
        assertFalse(judge.judge("How long?", "Five days.", FactId.DAYS).check.contradiction)
        val v = judge.judge("How long?", "Ten days.", FactId.DAYS)
        assertTrue(v.check.contradiction)
        assertEquals(FactId.DAYS, v.fact)
        assertEquals(compareQuestion(story.fact(FactId.DAYS, "en"), "Five days.", "Ten days."), fake.users[2])
        assertEquals(confirmQuestion(story.fact(FactId.DAYS, "en"), "Five days.", "Ten days."), fake.users[3])
    }

    @Test fun nullOrAbsentTopicAddsNoCalls() = runBlocking {
        for (topic in listOf(null, FactId.HOST)) {
            val fake = Fake(classify(0, false))
            val v = judge(fake).judge("Q?", answer, topic)
            assertFalse(v.check.contradiction)
            assertNull(v.fact)
            assertEquals(1, fake.users.size)
        }
    }

    @Test fun arrivalAndEncounterBenchTopicsMatchTheirQuestions() {
        for (cases in JudgeCases.arrival.values) assertEquals(
            listOf(FactId.PURPOSE, FactId.PURPOSE, FactId.DAYS, FactId.HOST, FactId.HOME, FactId.HOME), cases.map { it.topic })
        for (cases in JudgeCases.encounter.values) assertTrue(cases.all { it.topic == FactId.ENCOUNTER })
        assertEquals(JudgeCases.arrival.keys, JudgeCases.offFact.keys)
        for ((lang, cases) in JudgeCases.offFact) assertEquals(
            listOf(FactId.DAYS, FactId.HOST, FactId.FROM, FactId.HOST, FactId.DAYS, FactId.PURPOSE, FactId.HOST, FactId.PURPOSE, FactId.HOME, FactId.STAY, FactId.DAYS) +
                (if (lang == "pl") listOf(FactId.STAY) else emptyList()),
            cases.map { it.topic })
        // Every desk's swear is a dodge in code (no model call on the bench), and in the desk's language.
        for ((lang, cases) in JudgeCases.offFact) assertTrue(lang, isNonAnswer(cases.single { it.label == "swear for stay" }.a, lang))
    }

    @Test fun roomNoAndEvasiveMeansUnclearWithExplicitAdmissionRequired() = runBlocking {
        for ((fields, expected) in listOf(
            "ADMIT=no EVASIVE=yes" to Admit.UNCLEAR,
            "ADMIT=no EVASIVE=no" to Admit.NO,
            "ADMIT=yes EVASIVE=yes" to Admit.YES,
            "ADMIT=unclear EVASIVE=no" to Admit.UNCLEAR,
            "EVASIVE=yes" to Admit.NO,
            "ADMIT=garbage EVASIVE=yes" to Admit.NO,
            "admit: NO evasive: YES" to Admit.UNCLEAR,
        )) {
            val fake = Fake("FEELING=no IMPLANT=no LANGUAGE=yes CLARITY=2 TRICK=no $fields")
            val raw = fake.ask(roomPrompt(country), "Officer asked: \"Do you carry an implant?\"\nTraveler answered: \"Maybe, why?\"", Game.ROOM_TOKENS)
            assertEquals(fields, expected, RoomCheck.parse(raw).admit)
        }
        assertEquals(Admit.UNCLEAR, JudgeCases.ADMIT_EXPECTED[4])
    }
}

class OffCoverAndHintsTest {
    private fun fakeId(vararg c: String) = LangId { c.toList() }

    @Test fun offCoverQuestionNeverContradicts() = runBlocking {
        val replies = ArrayDeque(listOf("FACT=1 MATCHES=no ANSWERED=no LANGUAGE=yes CLARITY=2 TRICK=no", "no"))
        val ask: OneShot = { _, _, _ -> replies.removeFirst() }
        val j = Judge(Destination.forLanguage("ru"), Backstory.BENCH, ask, LangId { listOf("ru") })
        val v = j.judge("Как Вы его выучили?", "Я учил его в университете.", null, noFact = true)
        assertFalse(v.check.contradiction)
        assertNull(v.fact)
        assertEquals(1, replies.size) // no confirm step ran
        assertTrue(v.check.answered) // ANSWERED=no refers to the cover list, not a dodge
    }

    @Test fun numberedMarkersAndLayouts() = runBlocking {
        val raw = "1. Дождь не идёт. | Dozhd ne idyot. (It is not raining.)\n2) Нет, солнечно. | Net, solnechno. | No, it is sunny."
        val h = Models.option("1. Дождь не идёт. | Dozhd ne idyot. (It is not raining.)")
        assertEquals(Hint("Дождь не идёт.", "Dozhd ne idyot.", "It is not raining."), h)
        assertEquals(Hint("Нет, солнечно.", "Net, solnechno.", "No, it is sunny."), Models.option("2) Нет, солнечно. | Net, solnechno. | No, it is sunny."))
        assertEquals(2, suggestionLines(raw, "ru", "en", LangId { t -> if (t.contains("sunny") || t.contains("raining")) listOf("en") else listOf("ru") }).size)
    }

    @Test fun shortCyrillicNeedsOnlyCandidate() = runBlocking {
        assertTrue(generatedTextOk("Нет, солнечно.", "ru", fakeId("bg", "ru")))
        assertFalse(generatedTextOk("Нет, солнечно.", "ru", fakeId("bg", "mk")))
        assertFalse(generatedTextOk("Нет, сегодня очень солнечно.", "ru", fakeId("bg", "ru")))
    }
}
