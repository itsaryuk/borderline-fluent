package com.lampensoft.borderlinefluent

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslitTest {
    @Test fun russian() {
        assertEquals("Delovaya vstrecha", cyrillicToLatin("Деловая встреча", "ru"))
        assertEquals("Privet!", cyrillicToLatin("Привет!", "ru"))
        assertEquals("Shchi, Yozh", cyrillicToLatin("Щи, Ёж", "ru"))
    }
    @Test fun ukrainian() {
        assertEquals("Kyyiv", cyrillicToLatin("Київ", "uk"))
        assertEquals("Hurt, Gandzh, Dzherelo", cyrillicToLatin("Гурт, Ґандж, Джерело", "uk"))
    }
}
