package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-HR arm/disarm decision. Backgrounding the app does not dispose a composition, so a want held
 * by an on-screen HR page survived it and TOGGLE_REALTIME_HR stayed armed overnight, streaming 1 Hz off
 * the strap and pinning the link at CONNECTION_PRIORITY_HIGH. Three independent layers are pinned here:
 * the visibility gate, the screen lease, and the owner-keyed want set. BLE behaviour itself is not
 * testable without a strap; this covers the decision, not the write.
 */
class RealtimeHrArbiterTest {

    private val t0 = 1_000_000L
    private val hours = 60 * 60 * 1_000L

    // ── the visibility gate ─────────────────────────────────────────────────────

    @Test fun aScreenWantArmsTheStream() {
        val a = RealtimeHrArbiter()
        assertTrue(a.request(RealtimeHrOwner.HEALTH, t0))
        assertTrue(a.armed)
    }

    @Test fun backgroundingWithALiveHrScreenPresentDisarms() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        assertFalse(a.setVisible(false, t0))
        assertFalse(a.armed)
        assertEquals(emptySet<RealtimeHrOwner>(), a.holders(t0))
    }

    @Test fun returningToTheForegroundReArmsWhileTheScreenIsStillThere() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        a.setVisible(false, t0)
        assertTrue(a.setVisible(true, t0 + 5_000))
        assertEquals(setOf(RealtimeHrOwner.HEALTH), a.holders(t0 + 5_000))
    }

    @Test fun returningToTheForegroundDoesNotReArmOnceTheScreenHasGone() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        a.setVisible(false, t0)
        a.release(RealtimeHrOwner.HEALTH, t0 + 1_000)
        assertFalse(a.setVisible(true, t0 + 2_000))
    }

    // ── unbalanced calls ────────────────────────────────────────────────────────

    @Test fun anUnbalancedReleaseCannotLeaveAWantBehind() {
        val a = RealtimeHrArbiter()
        a.release(RealtimeHrOwner.HEALTH, t0)          // never requested
        a.release(RealtimeHrOwner.HEALTH, t0)
        assertFalse(a.armed)
        a.request(RealtimeHrOwner.HEALTH, t0)
        assertFalse(a.release(RealtimeHrOwner.HEALTH, t0))
        assertFalse(a.armed)
    }

    @Test fun aStrayReleaseNeverDisturbsAnotherOwnersWant() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HRV_SNAPSHOT, t0)
        repeat(3) { a.release(RealtimeHrOwner.HEALTH, t0) }
        assertTrue(a.armed)
        assertEquals(setOf(RealtimeHrOwner.HRV_SNAPSHOT), a.holders(t0))
    }

    @Test fun aSecondRequestFromOneOwnerNeedsASecondRelease() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        a.request(RealtimeHrOwner.HEALTH, t0)
        assertTrue(a.release(RealtimeHrOwner.HEALTH, t0))
        assertFalse(a.release(RealtimeHrOwner.HEALTH, t0))
    }

    // ── the safety timeout (the screen lease) ───────────────────────────────────

    @Test fun anUnrenewedScreenWantExpires() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        assertTrue(a.evaluate(t0 + RealtimeHrArbiter.SCREEN_LEASE_MS - 1))
        assertFalse(a.evaluate(t0 + RealtimeHrArbiter.SCREEN_LEASE_MS))
    }

    @Test fun aRenewedScreenWantNeverExpires() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        var now = t0
        repeat(40) {                                   // half an hour at the renewal cadence
            now += RealtimeHrArbiter.LEASE_RENEW_MS
            a.renew(RealtimeHrOwner.HEALTH, now)
            assertTrue("expired after ${now - t0} ms", a.armed)
        }
    }

    @Test fun theRenewalCadenceSitsWellInsideTheLease() {
        assertTrue(RealtimeHrArbiter.LEASE_RENEW_MS * 2 < RealtimeHrArbiter.SCREEN_LEASE_MS)
    }

    @Test fun renewNeverArmsAWantThatWasNeverHeld() {
        val a = RealtimeHrArbiter()
        assertFalse(a.renew(RealtimeHrOwner.HEALTH, t0))
        a.request(RealtimeHrOwner.HEALTH, t0)
        a.release(RealtimeHrOwner.HEALTH, t0)
        assertFalse(a.renew(RealtimeHrOwner.HEALTH, t0))
    }

    // ── the feature the fix must not break ──────────────────────────────────────

    @Test fun aLiveSessionKeepsStreamingWhileTheAppIsBackgrounded() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.LIVE_SESSION, t0)
        assertTrue(a.setVisible(false, t0))
        // Nothing renews a session want, so the lease must not reach it.
        assertTrue(a.evaluate(t0 + 8 * hours))
    }

    @Test fun anInExerciseWorkoutKeepsStreamingWithThePhonePocketed() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.LIVE_WORKOUT, t0)
        a.setVisible(false, t0)
        assertTrue(a.evaluate(t0 + 2 * hours))
        assertFalse(a.release(RealtimeHrOwner.LIVE_WORKOUT, t0 + 2 * hours))
    }

    @Test fun aSessionWantHoldsTheStreamWhileAScreenWantIsSuppressed() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        a.request(RealtimeHrOwner.LIVE_WORKOUT, t0)
        a.setVisible(false, t0)
        assertTrue(a.armed)
        assertEquals(setOf(RealtimeHrOwner.LIVE_WORKOUT), a.holders(t0))
    }

    // ── the cleared view-model ──────────────────────────────────────────────────

    @Test fun releaseAllDropsEveryWantIncludingABackgroundEligibleOne() {
        val a = RealtimeHrArbiter()
        a.request(RealtimeHrOwner.HEALTH, t0)
        a.request(RealtimeHrOwner.LIVE_SESSION, t0)
        assertFalse(a.releaseAll(t0))
        assertEquals(emptySet<RealtimeHrOwner>(), a.holders(t0))
    }

    @Test fun everyOwnerDeclaresWhetherItMayStreamInTheBackground() {
        assertEquals(
            setOf(RealtimeHrOwner.LIVE_WORKOUT, RealtimeHrOwner.LIVE_SESSION),
            RealtimeHrOwner.entries.filter { it.backgroundEligible }.toSet(),
        )
    }
}
