package com.noop.location

import com.noop.analytics.RouteMath
import com.noop.analytics.RouteMath.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level holder for the in-flight GPS workout's route, owned by [com.noop.NoopApplication]
 * and driven by [com.noop.ble.WhoopConnectionService] — NOT by the Activity-scoped AppViewModel.
 *
 * The track lives at the process level so it survives the UI going away: the foreground service
 * feeds it from the platform LocationManager, the ViewModel only observes [state] and reads the
 * final [State.track] when ending the workout. Distance/pace are derived here via [RouteMath].
 */
object GpsSession {

    /** A GPS workout's accumulated route. [startMs] anchors pace; [active] gates the service collector.
     *  [sportName] lets the UI rehydrate the active-workout card if the ViewModel was cleared mid-ride. */
    data class State(
        val active: Boolean = false,
        val startMs: Long = 0L,
        val sportName: String = "",
        val track: List<LatLng> = emptyList(),
        val distanceM: Double = 0.0,
        val paceSecPerKm: Double? = null,
    )

    private val _state = MutableStateFlow(State())
    /** The live route, observed by the UI (via AppViewModel) and the service's collect-gate. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Workouts & GPS test mode (Test Centre): the tagged sink for the .workouts GPS-fix lines, wired by
     *  [com.noop.ble.WhoopConnectionService] (which holds the BLE client + the gate). Default null (inert) so
     *  the route fold is byte-identical when the mode is off. The service ALWAYS checks the WORKOUTS gate
     *  before setting this, so [append] pays nothing extra when off. Diagnostic only - it never changes the
     *  route. The Android LocationTracker pre-filters UPSTREAM, so every appended fix is already ACCEPTED and
     *  the raw pre-filter count is not available at this seam; the gps line passes rawFixes = null (reads
     *  `n/a`) rather than imply an accept rate the platform never measured (the macOS recorder, which sees
     *  the raw stream, passes a real count). L4. */
    var workoutsLog: ((String) -> Unit)? = null

    /** Begin a route for [sportName]'s workout started at [startMs]. A re-arm just resets the track. */
    fun start(startMs: Long, sportName: String) {
        _state.value = State(active = true, startMs = startMs, sportName = sportName)
    }

    /** Fold one accepted fix into the route, recomputing distance + pace. No-op when not active. */
    fun append(pt: LatLng) {
        val s = _state.value
        if (!s.active) return
        val track = s.track + pt
        val dist = RouteMath.totalMeters(track)
        val secs = (System.currentTimeMillis() - s.startMs) / 1000.0
        _state.value = s.copy(track = track, distanceM = dist, paceSecPerKm = RouteMath.paceSecPerKm(dist, secs))
        // Workouts & GPS test mode: one GPS-fix-progress line per accepted fix, only when the service wired a
        // sink (the WORKOUTS gate was on). The LocationTracker pre-filters UPSTREAM, so the raw pre-filter
        // count is not available at this seam (every fix here is already accepted). Pass rawFixes = null so
        // the line reads `rawFixes=n/a` instead of `rawFixes == accepted`, which would falsely imply a 100%
        // accept rate the platform never actually measured (macOS, which sees the raw stream, passes a real
        // count). L4.
        workoutsLog?.invoke(
            com.noop.analytics.WorkoutsTrace.gpsLine(
                rawFixes = null, acceptedPoints = track.size, distanceM = dist,
            ),
        )
    }

    /** End the route and clear it. Returns the final accumulated track for the saved WorkoutRow. */
    fun stop(): List<LatLng> {
        val track = _state.value.track
        _state.value = State()
        return track
    }
}
