package com.noop.data

/**
 * What a "remove data" action may delete, grouped the way a person thinks about it rather than the
 * way the schema stores it.
 *
 * Presentation policy, not calculation: this decides WHAT to delete and how to name it, never what
 * any number is. Every deviceId-keyed table belongs to exactly one category, so "remove everything"
 * and "remove every category" delete the same rows — [DataRemovalCoverageTest] pins that.
 */
enum class DataCategory(val label: String, val detail: String) {
    HEART(
        "Heart rate and beats",
        "Every heart-rate sample, the beat-to-beat intervals HRV is computed from, and the optical readings behind them.",
    ),
    SLEEP(
        "Sleep",
        "Nights, their stages, and any night you dismissed.",
    ),
    ACTIVITY(
        "Steps and workouts",
        "Step counts, workouts, live sessions and the motion they were scored from.",
    ),
    BODY(
        "Temperature, SpO2 and breathing",
        "Skin temperature, blood-oxygen readings and respiratory rate.",
    ),
    RHYTHM(
        "ECG and rhythm",
        "ECG sessions, rhythm screens and their morphology.",
    ),
    SCORES(
        "Daily scores and trends",
        "Charge, Effort, Rest and every stored daily figure. Removing these keeps the raw samples they were computed from.",
    ),
    DEVICE(
        "Device events and battery",
        "Wear events and battery history. Not the device itself.",
    ),
    NOTES(
        "Journal and lab markers",
        "Anything you typed in yourself.",
    ),
    RAW(
        "Raw sensor records",
        "The undecoded records straight off the band. Everything above was decoded from these, so removing them frees the most space and keeps what was already read out of them.",
    ),
    ;

    companion object {
        /** Ordered for display: the raw measurements first, the things derived from them after. */
        val ordered: List<DataCategory> = listOf(HEART, SLEEP, ACTIVITY, BODY, RHYTHM, SCORES, DEVICE, NOTES, RAW)
    }
}
