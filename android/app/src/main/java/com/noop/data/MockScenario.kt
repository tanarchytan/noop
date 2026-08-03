package com.noop.data

/**
 * The named datasets the mock flavour can hold. One dataset cannot tell a display bug from a data
 * bug, so each of these puts the screens under a different shape of data and states, in [claims],
 * the values that must be on screen for it.
 *
 * Selected at launch without a rebuild, after stopping any running instance so the extra reaches
 * `onCreate`: `adb shell am force-stop com.noop.tan.mock.debug` then `adb shell am start -n
 * com.noop.tan.mock.debug/com.noop.ui.MainActivity --es mock_scenario gaps`. The choice persists, so
 * a later plain launch keeps the same dataset until another extra changes it.
 */
enum class MockScenario(val id: String, val label: String, val summary: String) {
    TYPICAL(
        "typical", "Typical",
        "120 correlated days ending today, one of them unslept. The default.",
    ),
    GAPS(
        "gaps", "Gaps",
        "Typical with a week and a day the strap was off, a second unslept day, and a 95-minute night.",
    ),
    EMPTY(
        "empty", "Empty",
        "No rows at all: what a fresh install shows before a strap or an import.",
    ),
    EXTREMES(
        "extremes", "Extremes",
        "Both ends of every scale: 0 and 100 scores, a 14-hour night and a 2-minute one.",
    ),
    BOUNDARIES(
        "boundaries", "Boundaries",
        "A day exactly on each recovery-state edge and one step below it.",
    ),
    TWO_STRAPS(
        "two-straps", "Two straps",
        "Typical split across a strap swap three days ago, so the window has two owners.",
    );

    /** False only for [EMPTY], which seeds neither rows nor the companion ring. */
    val seedsData: Boolean get() = this != EMPTY

    companion object {
        /** Launch-intent extra carrying an [id], so a dataset is chosen without a rebuild. */
        const val EXTRA = "mock_scenario"

        /** The scenario [id] names, or null when nothing matches — an unknown id is never guessed at. */
        fun forId(id: String?): MockScenario? {
            val wanted = id?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == wanted }
        }
    }
}

/** The `device` provenance row a dataset's rows are written under. */
data class MockDeviceRow(val id: String, val name: String)

/** Every row one scenario seeds, in memory, so the dataset can be asserted without a database. */
data class MockDataset(
    val devices: List<MockDeviceRow> = emptyList(),
    val pairedDevices: List<PairedDeviceRow> = emptyList(),
    val daily: List<DailyMetric> = emptyList(),
    val sleeps: List<SleepSession> = emptyList(),
    val series: List<MetricSeriesRow> = emptyList(),
    val apple: List<AppleDaily> = emptyList(),
    val workouts: List<WorkoutRow> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val hr: List<HrSample> = emptyList(),
) {
    val isEmpty: Boolean
        get() = devices.isEmpty() && pairedDevices.isEmpty() && daily.isEmpty() && sleeps.isEmpty() &&
            series.isEmpty() && apple.isEmpty() && workouts.isEmpty() && journal.isEmpty() && hr.isEmpty()
}

/**
 * One checkable statement a scenario makes about the data it seeds. [expected] is what the scenario
 * promises and [actual] is what it built, so a test asserts [holds] and a walk reads [name] off the
 * screen instead of eyeballing whether a number looks plausible.
 */
data class MockClaim(val name: String, val expected: String, val actual: String) {
    val holds: Boolean get() = expected == actual

    override fun toString(): String =
        if (holds) "$name = $expected" else "$name: expected $expected, built $actual"
}
