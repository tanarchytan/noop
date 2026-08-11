package com.noop.analytics

// Body-clock phase estimate: maps the whoop-rs `circadian` cosinor result onto the type
// [BodyClockCard] renders and picks which [Note] sits beside it. The fit and its constants are
// whoop-rs; what is here is the coverage the fit is offered on. Wellness awareness only, approximate.
object CircadianEngine {

    /** Distinct worn days of rest-activity a cosinor fit needs before anything derived from it is
     *  offered: fewer days fit a spurious rhythm. The Kotlin twin of the whoop-rs `circadian` gate,
     *  read by both the store-side pass and the live Health-hub read so the two agree on what is ready. */
    const val MIN_WORN_DAYS = 7

    /** Distinct LOCAL days the samples cover — what [MIN_WORN_DAYS] is counted against, and what the
     *  cosinor is told it observed. Days, never sample count: a dense burst over one day is one day. */
    fun wornDays(samples: List<uniffi.whoop_ffi.ActivitySample>, tzOffsetSeconds: Long): Int =
        samples.map { (it.unix + tzOffsetSeconds) / CalendarDay.SECONDS_PER_DAY }.distinct().size

    enum class PhaseConfidence(val raw: String) {
        UNREADABLE("unreadable"),
        WIDE("wide"),
        SOLID("solid"),
    }

    /** Which one-line note sits under the body clock. The wording for each lives in the UI. */
    enum class Note { HARD_TO_READ, LEANS_LATER, LEANS_EARLIER, ALIGNED }

    data class PhaseEstimate(
        val tempMinHour: Double,
        val acrophaseHours: Double,
        val offsetVsScheduleMinutes: Double,
        val confidence: PhaseConfidence,
        val note: Note,
    )

    /** Map an FFI phase estimate onto the UI type, choosing the note the card words. */
    fun fromRust(info: uniffi.whoop_ffi.PhaseEstimateInfo): PhaseEstimate {
        val confidence = when (info.confidence) {
            "solid" -> PhaseConfidence.SOLID
            "wide" -> PhaseConfidence.WIDE
            else -> PhaseConfidence.UNREADABLE
        }
        val note = if (confidence == PhaseConfidence.UNREADABLE) {
            Note.HARD_TO_READ
        } else {
            when (info.lean) {
                "later" -> Note.LEANS_LATER
                "earlier" -> Note.LEANS_EARLIER
                else -> Note.ALIGNED
            }
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
