package com.noop.location

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-flight GPS route holder. Nothing else in the suite constructs it, so the distance and pace it
 * publishes to the workout card were unmeasured: scaling the folded distance changed the saved workout's
 * headline figure and failed nothing.
 */
class GpsSessionTest {

    /** Process-level singleton: leave it inactive and empty for whatever runs next. */
    @After fun clear() {
        GpsSession.stop()
    }

    private val a = LatLng(50.8503, 4.3517)
    private val b = LatLng(50.8600, 4.3517)
    private val c = LatLng(50.8700, 4.3600)

    @Test fun distanceIsTheRouteMathTotalOverTheFoldedTrack() {
        GpsSession.start(System.currentTimeMillis(), "Running")
        GpsSession.append(a)
        GpsSession.append(b)
        GpsSession.append(c)
        val s = GpsSession.state.value
        assertEquals(listOf(a, b, c), s.track)
        assertEquals(RouteMath.totalMeters(listOf(a, b, c)), s.distanceM, 1e-9)
        assertTrue("the fixture must cover real ground", s.distanceM > 1_000.0)
    }

    @Test fun paceIsElapsedSecondsPerKilometreOfThatDistance() {
        val tenMinutesAgo = System.currentTimeMillis() - 600_000L
        GpsSession.start(tenMinutesAgo, "Running")
        GpsSession.append(a)
        GpsSession.append(b)
        val s = GpsSession.state.value
        val expected = RouteMath.paceSecPerKm(RouteMath.totalMeters(listOf(a, b)), 600.0)
        assertNotNull(expected)
        assertNotNull(s.paceSecPerKm)
        // Tolerance covers only the milliseconds the test itself spends, not a scaled distance.
        assertEquals(expected!!, s.paceSecPerKm!!, 1.0)
    }

    @Test fun aSingleFixHasNoPaceYet() {
        GpsSession.start(System.currentTimeMillis(), "Cycling")
        GpsSession.append(a)
        assertEquals(0.0, GpsSession.state.value.distanceM, 1e-9)
        assertNull("pace is undefined over zero distance", GpsSession.state.value.paceSecPerKm)
    }

    @Test fun fixesArrivingBeforeStartAreIgnored() {
        GpsSession.append(a)
        assertTrue(GpsSession.state.value.track.isEmpty())
        assertFalse(GpsSession.state.value.active)
    }

    @Test fun stopReturnsTheTrackAndClearsIt() {
        GpsSession.start(System.currentTimeMillis(), "Running")
        GpsSession.append(a)
        GpsSession.append(b)
        assertEquals(listOf(a, b), GpsSession.stop())
        val s = GpsSession.state.value
        assertFalse(s.active)
        assertTrue(s.track.isEmpty())
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals("", s.sportName)
    }

    @Test fun aReArmResetsTheRoute() {
        GpsSession.start(System.currentTimeMillis(), "Running")
        GpsSession.append(a)
        GpsSession.start(System.currentTimeMillis(), "Cycling")
        val s = GpsSession.state.value
        assertEquals("Cycling", s.sportName)
        assertTrue(s.track.isEmpty())
        assertEquals(0.0, s.distanceM, 1e-9)
    }
}
