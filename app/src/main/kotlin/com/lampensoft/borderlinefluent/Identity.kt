package com.lampensoft.borderlinefluent

import kotlin.random.Random

/** How the traveller asked to be addressed. */
enum class Gender { WOMAN, MAN, NONBINARY, UNSAID }
/** Which binary name list ([Names.random]) the dice draw from: fixed for WOMAN/MAN, the player's choice otherwise. */
enum class NameList { FEMALE, MALE }

/**
 * The player's own passport: rolled once on the passport screen and kept in the career ([CareerSnapshot.identity]).
 * [photo]: the sample face behind the passport photo when there is no own photo (null: none chosen).
 */
data class Identity(
    val nationality: Nationality, val gender: Gender, val nameList: NameList, val name: String, val latin: String,
    val born: String, val home: String, val job: String, val photo: Person?, val passportNo: String, val extra: CareerFields = emptyMap(),
) {
    companion object {
        private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        /** WOMAN and MAN fix the list; NONBINARY and UNSAID keep the player's [chosen] one. */
        fun listFor(gender: Gender, chosen: NameList) = when (gender) { Gender.WOMAN -> NameList.FEMALE; Gender.MAN -> NameList.MALE; else -> chosen }
        fun born(r: Random) = "${r.nextInt(1, 29)} ${months.random(r)} ${r.nextInt(1980, 2003)}"
        fun passportNo(r: Random, iso: String) = iso + (1_000_000 + r.nextInt(9_000_000))

        fun roll(r: Random, nationality: Nationality, gender: Gender, nameList: NameList): Identity {
            val list = listFor(gender, nameList)
            val name = NAMES.getValue(nationality.nameLang).random(r, female = list == NameList.FEMALE)
            return Identity(nationality, gender, list, name.native, name.latin, born(r), CITIES.getValue(nationality.id).random(r),
                "", null, passportNo(r, nationality.iso))
        }
    }
}
