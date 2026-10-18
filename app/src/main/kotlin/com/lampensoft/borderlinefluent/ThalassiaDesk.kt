package com.lampensoft.borderlinefluent

/** First-career departures only; Settings replay retains the ordinary training desk. */
internal fun usesThalassiaDesk(training: Boolean, firstCareerTraining: Boolean) = training && firstCareerTraining

internal class ThalassiaDesk(private val childTaken: Boolean = false) {
    var fatherAway = false; private set
    private val childQuestion get() = childTaken && turn == 1
    fun finishFatherAway() { if (fatherAway) { fatherAway = false; passed = true } }
    var turn = 0; private set
    var repeated = false; private set
    var passed = false; private set
    val line get() = if (passed) { if (childTaken) CHILD_LAST else LAST } else if (childQuestion) CHILD else LINES[turn]
    val subtitle get() = if (passed) { if (childTaken) R.string.thalassia_child_last else R.string.thalassia_last } else if (childQuestion) R.string.thalassia_child else SUBTITLES[turn]
    val suggestions get() = if (passed || childQuestion || fatherAway) emptyList() else ANSWERS[turn]

    /** Passport speech passes after refusal; taking the child always reaches her question. */
    fun answer(text: String) {
        if (passed || fatherAway) return
        if (childQuestion) { fatherAway = true; return }
        if (text.isBlank()) {
            if (repeated) { if (childTaken) { turn = 1; repeated = false } else passed = true } else repeated = true
        } else {
            repeated = false
            if (childTaken) turn = 1 else passed = true
        }
    }

    companion object {
        const val CHILD = "To paidí, mazí sas?"
        const val CHILD_LAST = "O, lypámai. Kaló taxídi."
        const val FATHER = "Pu to vrikes to isitíri? Óla poliména apo to proí. Parakaló… párte tin kóri mu mazí sas. Kápios tha tin periméni."
        const val LAST = "Kaló taxídi. Epómenos!"
        val LINES = listOf("Kalesta! Dorvani su, parastí?")
        val SUBTITLES = listOf(R.string.thalassia_t1)
        val ANSWERS = listOf(listOf("OK.", "Here.", "Yes."))
    }
}

/** Act-1 training data travels in the durable preparation, through booking and interrupted attempts.
 * Use the extensible JSON fields so the training itinerary remains independent of random encounters.
 */
internal val Preparation.fatherChoice: Int?
    get() = extra["act1FatherChoice"]?.toIntOrNull()?.takeIf { it in 0..1 }
internal fun Preparation.withFatherChoice(choice: Int): Preparation {
    require(choice in 0..1)
    return copy(extra = extra + ("act1FatherChoice" to choice.toString()))
}

/** The departure receipt survives process death after the booking and attempt are cleared. */
internal fun act1PlaneCaption(snapshot: CareerSnapshot?): Int =
    if (snapshot?.receipt?.extra?.get("act1FatherChoice") == "0") R.string.act1_plane_taken else R.string.act1_plane
