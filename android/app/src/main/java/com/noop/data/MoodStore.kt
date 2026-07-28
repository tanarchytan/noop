package com.noop.data

import java.time.LocalDate

/**
 * MoodStore — the Mind lane's storage layer. Rows live in the generic `metricSeries` store
 * (PK (deviceId, day, key), @Upsert); source id (deviceId) is ALWAYS [MOOD_DEVICE_ID]
 * ("noop-mood"), key ALWAYS [MOOD_KEY], value the 5-face scale 1.0-5.0 (clamped on write).
 * ONE row per local day ("YYYY-MM-DD"); the natural key makes the upsert an overwrite in
 * place, so a day can never duplicate. `metricSeries` has no source column beyond deviceId,
 * so native check-ins are isolated under "noop-mood" — a CSV or Apple Health re-import can
 * never overwrite or delete them. Constructor takes the two storage functions (not the
 * repository class) so the contract is unit-testable with a plain in-memory map.
 */
class MoodStore(
    private val upsertRows: suspend (List<MetricSeriesRow>) -> Unit,
    private val queryRows: suspend (
        deviceId: String,
        key: String,
        from: String,
        to: String,
    ) -> List<MetricSeriesRow>,
) {
    constructor(repo: WhoopRepository) : this(
        { rows -> repo.upsertMetricSeries(rows) },
        { deviceId, key, from, to -> repo.metricSeries(deviceId, key, from, to) },
    )

    /** Save (or overwrite) the mood for [day] ("YYYY-MM-DD"). Value clamps to 1.0–5.0. */
    suspend fun setMood(day: String, value: Double) {
        upsertRows(
            listOf(
                MetricSeriesRow(
                    deviceId = MOOD_DEVICE_ID,
                    day = day,
                    key = MOOD_KEY,
                    value = value.coerceIn(MOOD_MIN, MOOD_MAX),
                ),
            ),
        )
    }

    /** The mood logged for [day], or null when none was checked in. */
    suspend fun mood(day: String): Double? =
        queryRows(MOOD_DEVICE_ID, MOOD_KEY, day, day).firstOrNull()?.value

    /** All check-ins in [from, to] (inclusive day keys), oldest first, as (day, value).
     *  Defaults to the full history. One pair per day by the storage contract. */
    suspend fun moodSeries(from: String = DAY_MIN, to: String = DAY_MAX): List<Pair<String, Double>> =
        queryRows(MOOD_DEVICE_ID, MOOD_KEY, from, to)
            .sortedBy { it.day }
            .map { it.day to it.value }

    companion object {
        /** Dedicated source id (deviceId column) for native mood rows. */
        const val MOOD_DEVICE_ID = "noop-mood"

        /** The metricSeries key under which moods are stored. */
        const val MOOD_KEY = "mood"

        /** 5-face scale bounds (😞=1 … 😄=5). */
        const val MOOD_MIN = 1.0
        const val MOOD_MAX = 5.0

        private const val DAY_MIN = "0000-01-01"
        private const val DAY_MAX = "9999-12-31"

        /** Day key for a check-in: the LOCAL calendar day ("YYYY-MM-DD"). Unlike the
         *  journal's wake-day attribution, "today's mood" is literally today. */
        fun todayKey(today: LocalDate = LocalDate.now()): String = today.toString()
    }
}
