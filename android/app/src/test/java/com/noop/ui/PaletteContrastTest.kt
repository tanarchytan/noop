package com.noop.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Test

/**
 * The light scheme's colour floors, as arithmetic rather than as an eye.
 *
 * Three rounds of the UI walk raised the same two families and neither had a gate:
 *
 *  * **contrast.** The light chrome accent measured 3.55:1 against the page it draws text on. It was
 *    picked to look right, and looking right is not a measurement — the palette's own comment already
 *    says textTertiary was set this way after the shade that "looked right" came out at 2.98:1.
 *  * **separation.** metricRose (heart rate's identity) had been spelled as the critical red exactly.
 *    A healthy 55 bpm and a critical band swatch were dE 7.4 apart while the band colours the scheme
 *    NEEDS told apart sit 40+ apart, so on one screen "this is RHR" and "this is bad" were one colour.
 *
 * Both replicas here are written from the published formulas (WCAG 2.x relative luminance, CIE L*a*b*
 * with a D65 white), not from anything the app computes, so this file gates the palette rather than
 * agreeing with it.
 *
 * The floors differ by WHERE a token draws text, which is the honest rule and not a convenience:
 * chrome (the accent) carries labels on the page background, so it is measured against `surfaceBase`,
 * the darkest light surface. The state tokens carry a StatePill's label, and a pill only ever sits on
 * a card, so they are measured against `surfaceRaised`. Identity hues (metricCyan, metricAmber) are
 * NOT gated: they draw strokes and fills, where 3:1 applies, and moving all of them to a text floor
 * would drain the scheme of the hue separation the second half of this file demands.
 */
class PaletteContrastTest {

    private fun channel(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** WCAG 2.x relative luminance. */
    private fun luminance(c: Color): Double {
        val r = channel(c.red.toDouble())
        val g = channel(c.green.toDouble())
        val b = channel(c.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 2.x contrast ratio, order-independent. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun lab(c: Color): Triple<Double, Double, Double> {
        val r = channel(c.red.toDouble())
        val g = channel(c.green.toDouble())
        val b = channel(c.blue.toDouble())
        val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047
        val y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750
        val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** CIE76 dE. Blunt next to dE2000, but the distances this gates are 7 against 40 — no subtlety
     *  in the metric is going to decide that, and a formula anyone can check by hand is worth more. */
    private fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private val t = LightTokens

    private fun assertAtLeast(actual: Double, floor: Double, what: String) {
        assertTrue(
            "$what measured %.2f, floor %.2f".format(actual, floor),
            actual >= floor,
        )
    }

    @Test
    fun theReplicaAgreesWithTwoValuesAnyoneCanCheckByHand() {
        // Black on white is 21:1 and white on white is 1:1 in every implementation of the formula.
        // Without this the two helpers could be uniformly wrong and every floor below would pass.
        assertEquals21(contrast(Color(0xFF000000), Color(0xFFFFFFFF)))
        assertTrue("white against itself must be 1.0", contrast(Color.White, Color.White) < 1.001)
        // A colour is zero distance from itself, and the L* of white is 100.
        assertTrue("dE from a colour to itself must be 0", deltaE(t.accent, t.accent) < 1e-9)
        assertTrue("L* of white must be 100", lab(Color(0xFFFFFFFF)).first > 99.9)
    }

    private fun assertEquals21(actual: Double) =
        assertTrue("black on white must be 21:1, measured %.3f".format(actual), actual > 20.99 && actual < 21.01)

    @Test
    fun lightChromeCarriesTextOnThePageAtAA() {
        // CUSTOMISE, "Show fewer" and the selected bottom-nav label are all accent-on-surfaceBase.
        assertAtLeast(contrast(t.accent, t.surfaceBase), 4.5, "light accent on surfaceBase")
        assertAtLeast(contrast(t.accentHover, t.surfaceBase), 4.5, "light accentHover on surfaceBase")
    }

    @Test
    fun lightTextTokensClearAAOnTheDarkestLightSurface() {
        assertAtLeast(contrast(t.textPrimary, t.surfaceBase), 4.5, "light textPrimary on surfaceBase")
        assertAtLeast(contrast(t.textSecondary, t.surfaceBase), 4.5, "light textSecondary on surfaceBase")
        assertAtLeast(contrast(t.textTertiary, t.surfaceBase), 4.5, "light textTertiary on surfaceBase")
    }

    @Test
    fun lightStateTokensCarryAPillLabelOnACardAtAA() {
        assertAtLeast(contrast(t.statusPositive, t.surfaceRaised), 4.5, "light statusPositive on a card")
        assertAtLeast(contrast(t.statusWarning, t.surfaceRaised), 4.5, "light statusWarning on a card")
        assertAtLeast(contrast(t.statusCritical, t.surfaceRaised), 4.5, "light statusCritical on a card")
    }

    @Test
    fun aMetricIdentityIsNeverSpelledAsAStateColour() {
        // The four identity hues answer "which metric is this", the state colours answer "is this
        // bad". A reader who cannot tell them apart is being told the wrong thing, so the floor is a
        // real perceptual distance rather than mere inequality — metricRose USED to be statusCritical
        // to the byte, and byte-inequality alone would have passed a one-digit nudge.
        val identities = mapOf(
            "metricCyan" to t.metricCyan,
            "metricPurple" to t.metricPurple,
            "metricAmber" to t.metricAmber,
            "metricRose" to t.metricRose,
        )
        val states = mapOf(
            "statusCritical" to t.statusCritical,
            "recovery000" to t.recovery000,
        )
        for ((iName, i) in identities) {
            for ((sName, s) in states) {
                assertAtLeast(deltaE(i, s), 25.0, "dE $iName to $sName in light")
            }
        }
    }

    @Test
    fun theDarkSchemeStillHoldsTheSameSeparation() {
        // The rose/critical collision only ever existed in light; this pins that the dark twin was
        // never the thing being fixed and does not drift into the same shape later.
        assertAtLeast(deltaE(DarkTokens.metricRose, DarkTokens.statusCritical), 25.0, "dE metricRose to statusCritical in dark")
    }
}
