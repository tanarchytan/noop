package com.noop.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live readings held per device, so two links can stream at once without overwriting each other.
 *
 * [LiveState] stays the WHOOP strap's own state and keeps its ~40 strap-only fields (pack charge,
 * offload progress, pairing hints) that no other brand will ever write. Only the readings below
 * genuinely contend between devices, and here each device owns its entry under its registry id.
 */
data class SourceLive(
    val deviceId: String,
    /** Registry brand ("WHOOP", "Oura"), so a reader can label a reading without a registry round-trip. */
    val brand: String,
    val heartRate: Int? = null,
    /** Rolling recent R-R intervals (ms), oldest dropped first. */
    val rrRecent: List<Int> = emptyList(),
    val batteryPct: Double? = null,
    /** Unix millis of the most recent HR or R-R sample, 0 until one lands. Tells a live entry from
     *  one whose device went quiet without dropping its link. */
    val lastSampleAt: Long = 0L,
)

/**
 * Process-wide map of deviceId to its live readings. Every writer touches only its own key, which is
 * what lets a ring and a strap stream at the same time.
 */
class LiveSources(private val now: () -> Long = System::currentTimeMillis) {

    private val _sources = MutableStateFlow<Map<String, SourceLive>>(emptyMap())
    val sources: StateFlow<Map<String, SourceLive>> = _sources.asStateFlow()

    fun of(deviceId: String): SourceLive? = _sources.value[deviceId]

    /**
     * Record an HR and/or R-R sample under [deviceId]. [hr] is range-gated (30..220); anything else,
     * including the 0 a source sends to mean "R-R only", leaves the last good HR standing.
     */
    fun publishSample(
        deviceId: String,
        brand: String,
        hr: Int?,
        rr: List<Int>,
        recentLimit: Int = RECENT_LIMIT,
    ) {
        val usableHr = hr?.takeIf { it in 30..220 }
        if (usableHr == null && rr.isEmpty()) return
        update(deviceId, brand) { prev ->
            val recent = if (rr.isEmpty()) prev.rrRecent else (prev.rrRecent + rr).takeLast(recentLimit)
            prev.copy(
                heartRate = usableHr ?: prev.heartRate,
                rrRecent = recent,
                lastSampleAt = now(),
            )
        }
    }

    /** Record a battery percent under [deviceId]. Out-of-range values are ignored. */
    fun publishBattery(deviceId: String, brand: String, pct: Double) {
        if (pct !in 0.0..100.0) return
        update(deviceId, brand) { it.copy(batteryPct = pct) }
    }

    /** Drop a device's readings, so a torn-down source never leaves a stale one behind. */
    fun forget(deviceId: String) {
        _sources.update { it - deviceId }
    }

    private fun update(deviceId: String, brand: String, block: (SourceLive) -> SourceLive) {
        _sources.update { all ->
            val prev = all[deviceId] ?: SourceLive(deviceId = deviceId, brand = brand)
            all + (deviceId to block(prev).copy(brand = brand))
        }
    }

    companion object {
        /** Matches the Live console's rolling R-R buffer depth. */
        const val RECENT_LIMIT = 60

        /** Registry brand for the WHOOP strap, the value its seeded row carries. */
        const val BRAND_WHOOP = "WHOOP"

        /** Registry brand for an Oura ring row. */
        const val BRAND_OURA = "Oura"
    }
}
