package com.noop.analytics

// Body-clock phase estimate: maps the whoop-rs `circadian` cosinor result onto the type
// [BodyClockCard] renders and words the note beside it. The fit, its gates and its constants are
// whoop-rs; nothing here is arithmetic. Wellness awareness only, approximate.
object CircadianEngine {

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
}
