package com.noop.data

import com.noop.oura.OuraEvent
import com.noop.protocol.SkinTempSample
import com.noop.protocol.Spo2Sample
import com.noop.protocol.Streams
import com.noop.protocol.WhoopEvent

/**
 * Pure, JVM-testable mapping from the Oura ring's decoded [OuraEvent]s onto the protocol
 * [Streams] shape, so live samples flow through the same [WhoopRepository.insert] path (via
 * [StreamPersistence.toBatch]) the WHOOP pipeline uses.
 *
 * HONEST-DATA INVARIANT: surfaces only the ring's raw signals and its own open event tags, never
 * Oura's encrypted readiness/sleep scores. IBI becomes [Streams.rr] (NOOP's own RMSSD source); HR
 * feeds resting-HR + strain; the open 0x5D HRV tag is stored as `OURA_HRV` with its raw
 * time_ms/b1/b2 fields only (not Tier-A, never a fabricated rmssd_ms); sleep-phase tags become
 * `OURA_SLEEP_PHASE` events.
 *
 * Each event's ring-clock `ringTimestamp` is resolved by the caller's [anchor] (driven by the
 * ring's 0x42/0x85 time-sync events) to a wall-clock unix second; a null anchor drops the sample
 * rather than stamp a guessed time, since a ts-less row is unstorable anyway.
 */
object OuraStreamMapping {

    /** The event `kind` recorded for the ring's own open HRV (0x5D) tag. */
    const val EVENT_HRV = "OURA_HRV"

    /** The event `kind` recorded for the ring's own open sleep-phase (0x49.../0x58) tags. */
    const val EVENT_SLEEP_PHASE = "OURA_SLEEP_PHASE"

    /**
     * Folds a batch of decoded [events] into a protocol [Streams] for one flush. [anchor] maps a
     * ring-clock timestamp to wall-clock unix seconds (null drops the sample). Tier-B events (only
     * emitted when the driver's allowTierB is set) are ignored here so they can't fabricate a stream value.
     */
    fun streams(events: List<OuraEvent>, anchor: (Long) -> Int?): Streams {
        val out = Streams()
        for (ev in events) {
            when (ev) {
                is OuraEvent.Hr -> {
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.hr.add(com.noop.protocol.HrSample(ts, ev.value.bpm))
                }

                is OuraEvent.Ibi -> {
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.rr.add(com.noop.protocol.RrInterval(ts, ev.value.ibiMs))
                }

                is OuraEvent.Hrv -> {
                    // The ring's own open HRV tag, recorded raw for diagnostics. Not Oura's readiness
                    // score, and not used as NOOP's RMSSD (that comes from `rr`).
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.events.add(
                        WhoopEvent(
                            ts = ts,
                            kind = EVENT_HRV,
                            payload = linkedMapOf(
                                "time_ms" to ev.value.timeMs,
                                "b1" to ev.value.b1,
                                "b2" to ev.value.b2,
                            ),
                        ),
                    )
                }

                is OuraEvent.Spo2 -> {
                    // The ring exposes one combined SpO2 reading (not separate red/ir channels): its raw
                    // value goes in `red`; `ir` stays 0 (unread channel, never a fabricated second
                    // reading). `unit` carries the decoder's own scale tag so downstream never assumes a percentage.
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.spo2.add(Spo2Sample(ts = ts, red = ev.value.value, ir = 0, unit = ev.value.unit))
                }

                is OuraEvent.Temp -> {
                    // The ring exposes skin temperature in degrees C; the store's raw integer uses the
                    // codebase-wide CENTI-degree-C convention (°C = raw / 100, the scale the analytics
                    // reader divides by), so persist celsius * 100 and tag the unit.
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.skinTemp.add(
                        SkinTempSample(
                            ts = ts,
                            raw = Math.round(ev.value.celsius * 100.0).toInt(),
                            unit = "centi_c",
                        ),
                    )
                }

                is OuraEvent.SleepPhaseEvent -> {
                    val ts = anchor(ev.value.ringTimestamp) ?: continue
                    out.events.add(
                        WhoopEvent(
                            ts = ts,
                            kind = EVENT_SLEEP_PHASE,
                            payload = linkedMapOf<String, Any?>(
                                "phase" to ev.value.stage.raw,
                                "index" to ev.value.index,
                            ),
                        ),
                    )
                }

                is OuraEvent.Battery -> {
                    // Live battery percent. No ring timestamp on a battery reading (it is a command
                    // response), so it is stamped by the live source's `onBattery` path, not persisted
                    // as a tied-to-ts row here. Leave the batch's battery list empty (honest: no faked ts).
                }

                // Motion / state / time-sync / rtc / debug / TierB / ActivityInfo never map onto a
                // scored stream. The 0x50 activity/MET decode never mints a `steps` row: the formula
                // is third-party and unvalidated, and MET is not a step count — fabricating one would
                // break the honest-data invariant and the per-source day-owner rules.
                else -> Unit
            }
        }
        return out
    }
}
