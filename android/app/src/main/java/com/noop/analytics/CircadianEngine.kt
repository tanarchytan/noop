package com.noop.analytics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

// Body-clock phase estimate (consumed via FFI, see [fromRust]) plus a jet-lag / shift-work light and
// sleep-timing plan computed locally. Independent implementation of published methods: cosinor phase
// estimation (rest-activity rhythm, corroborated by the nightly skin-temp minimum) and a
// phase-response-curve direction rule for the shift advisory — ADVANCE (earlier) gets morning light,
// dim evenings, earlier sleep, stepped ~1 h/day; DELAY (later) is the reverse.
//
// Wellness/behavioural awareness only, approximate. Light and sleep timing only — never melatonin
// or a supplement/drug, never a guarantee.
object CircadianEngine {

    // ── Tuning constants ──
    //
    // The cosinor fit and its gates are whoop-rs `circadian`, whose constants are the definitions.
    // What is left is the shift step the local jet-lag plan applies.
    const val maxShiftPerDayHours: Double = 1.0

    // ── Phase estimate ──

    enum class PhaseConfidence(val raw: String) {
        UNREADABLE("unreadable"),
        WIDE("wide"),
        SOLID("solid"),
    }

    data class PhaseEstimate(
        val tempMinHour: Double,
        val acrophaseHours: Double,
        val offsetVsScheduleMinutes: Double,
        val confidence: PhaseConfidence,
        val note: String,
    )

    /** Map an FFI phase estimate onto the UI type, generating the note the card renders. */
    fun fromRust(info: uniffi.whoop_ffi.PhaseEstimateInfo): PhaseEstimate {
        val confidence = when (info.confidence) {
            "solid" -> PhaseConfidence.SOLID
            "wide" -> PhaseConfidence.WIDE
            else -> PhaseConfidence.UNREADABLE
        }
        val note = if (confidence == PhaseConfidence.UNREADABLE) {
            "Your rhythm is hard to read right now - keep wearing it for a clearer picture."
        } else {
            val lean = when (info.lean) {
                "later" -> "later (a night-owl lean)"
                "earlier" -> "earlier (a morning-lark lean)"
                else -> "well-aligned with your schedule"
            }
            "Your body clock looks $lean."
        }
        return PhaseEstimate(
            tempMinHour = info.tempMinHour,
            acrophaseHours = info.acrophaseHours,
            offsetVsScheduleMinutes = info.offsetVsScheduleMinutes,
            confidence = confidence,
            note = note,
        )
    }

    // ── Jet-lag / shift planner ──

    enum class ShiftDirection(val raw: String) {
        ADVANCE("advance"),
        DELAY("delay"),
        NONE("none"),
    }

    data class DayPlan(
        val dayIndex: Int,
        val brightLightStartHour: Double,
        val brightLightEndHour: Double,
        val dimFromHour: Double,
        val targetSleepHour: Double,
        val targetWakeHour: Double,
        val guidance: String,
    )

    data class JetLagPlan(
        val direction: ShiftDirection,
        val totalShiftHours: Double,
        val estimatedDays: Int,
        val days: List<DayPlan>,
        val note: String,
    )

    /**
     * Build a stepped light + sleep-timing plan to absorb a required clock shift. [shiftHours] POSITIVE =
     * ADVANCE (earlier; eastward), NEGATIVE = DELAY (later; westward).
     */
    fun planShift(shiftHours: Double, currentSleepHour: Double, currentWakeHour: Double): JetLagPlan {
        val magnitude = abs(shiftHours)
        if (magnitude < 0.5) {
            return JetLagPlan(ShiftDirection.NONE, 0.0, 0, emptyList(),
                "No meaningful body-clock shift needed - you're about aligned.")
        }

        val advancing = shiftHours > 0
        val direction = if (advancing) ShiftDirection.ADVANCE else ShiftDirection.DELAY
        val days = ceil(magnitude / maxShiftPerDayHours).toInt()

        val plan = mutableListOf<DayPlan>()
        var cumulative = 0.0
        for (i in 1..days) {
            val stepRemaining = magnitude - cumulative
            val step = minOf(maxShiftPerDayHours, stepRemaining)
            cumulative += step
            val signed = if (advancing) -cumulative else cumulative
            val sleep = wrap24(currentSleepHour + signed)
            val wake = wrap24(currentWakeHour + signed)

            val brightStart: Double
            val brightEnd: Double
            val dimFrom: Double
            val guidance: String
            if (advancing) {
                brightStart = wake
                brightEnd = wrap24(wake + 2.0)
                dimFrom = wrap24(sleep - 2.0)
                guidance = "Get bright light early after waking and keep the evening dim - this nudges your " +
                    "clock earlier. Aim for lights-out around ${clock(sleep)}."
            } else {
                brightStart = wrap24(sleep - 3.0)
                brightEnd = wrap24(sleep - 1.0)
                dimFrom = wrap24(wake)
                guidance = "Get bright light in the evening and go easy on bright morning light - this nudges " +
                    "your clock later. Aim for lights-out around ${clock(sleep)}."
            }
            plan.add(DayPlan(i, brightStart, brightEnd, dimFrom, sleep, wake, guidance))
        }

        val dirWord = if (advancing) "earlier" else "later"
        val magStr = formatOneDecimal(magnitude)
        val rate = if (maxShiftPerDayHours == 1.0) "an hour" else "$maxShiftPerDayHours h"
        val note = "Shifting your clock $magStr h $dirWord, about $rate a day. Light and sleep " +
            "timing only."
        return JetLagPlan(direction, magnitude, days, plan, note)
    }

    // ── Helpers ──

    /** Wrap an hour value into [0, 24). */
    internal fun wrap24(h: Double): Double {
        var x = h % 24.0
        if (x < 0) x += 24.0
        return x
    }

    /** Format a clock hour as "HH:MM" (24 h), locale-free. */
    internal fun clock(hour: Double): String {
        val h = wrap24(hour)
        var hh = h.toInt()
        var mm = ((h - hh.toDouble()) * 60.0).roundToInt()
        if (mm == 60) { mm = 0; hh = (hh + 1) % 24 }
        val hp = hh.toString().padStart(2, '0')
        val mpad = mm.toString().padStart(2, '0')
        return "$hp:$mpad"
    }

    /** Locale-free "%.1f"-equivalent format (avoids a comma decimal separator in some locales). */
    internal fun formatOneDecimal(x: Double): String {
        val scaled = (x * 10.0).roundToInt()
        val whole = scaled / 10
        val frac = abs(scaled % 10)
        return "$whole.$frac"
    }
}
