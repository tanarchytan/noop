package com.noop.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which locale the app renders in, given a stored choice and the phone's own language.
 *
 * The date formatters read `Locale.getDefault()`, which [NoopLocale.wrap] sets from this. A phone whose
 * language we do not translate must therefore resolve to English, not to itself: it gets English
 * strings either way, and a Dutch phone drew an English UI over `di, 11 aug`.
 */
class NoopLocaleResolveTest {

    @Test
    fun aChosenLanguageWins() {
        assertEquals(Locale.forLanguageTag("es"), NoopLocale.resolve("es", Locale.forLanguageTag("nl-NL")))
        assertEquals(Locale.forLanguageTag("en"), NoopLocale.resolve("en", Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun anUntranslatedSystemLanguageFallsBackToEnglish() {
        assertEquals(Locale.ENGLISH, NoopLocale.resolve(null, Locale.forLanguageTag("nl-NL")))
        assertEquals(Locale.ENGLISH, NoopLocale.resolve(null, Locale.forLanguageTag("fr-FR")))
    }

    @Test
    fun aTranslatedSystemLanguageKeepsItsRegion() {
        val gb = Locale.forLanguageTag("en-GB")
        assertEquals(gb, NoopLocale.resolve(null, gb))
        val mx = Locale.forLanguageTag("es-MX")
        assertEquals(mx, NoopLocale.resolve(null, mx))
    }

    @Test
    fun germanIsOfferedAndAlsoFollowsTheSystem() {
        val de = Locale.forLanguageTag("de-DE")
        assertEquals(de, NoopLocale.resolve(null, de))
        assertEquals(Locale.forLanguageTag("de"), NoopLocale.resolve("de", Locale.forLanguageTag("nl-NL")))
        assertEquals(3, NoopLocale.SUPPORTED.count { it.tag != null })
    }

    @Test
    fun aStoredTagThePickerNoLongerOffersResolvesAsIfUnset() {
        assertEquals(Locale.ENGLISH, NoopLocale.resolve("fr", Locale.forLanguageTag("nl-NL")))
        val gb = Locale.forLanguageTag("en-GB")
        assertEquals(gb, NoopLocale.resolve("fr", gb))
    }
}
