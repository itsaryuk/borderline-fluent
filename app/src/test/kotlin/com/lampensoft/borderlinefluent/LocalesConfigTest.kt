package com.lampensoft.borderlinefluent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalesConfigTest {
    @Test fun systemAppLanguagesPageOffersEveryGameLanguage() {
        val xml = File("src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([a-z-]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(Language.all.map { it.code }.toSet(), listed)
    }
}
