package com.noop.ingest

import androidx.health.connect.client.records.ExerciseSessionRecord as EX

/**
 * Single source of truth for Health Connect exercise-type <-> label, shared by [HealthConnectImporter]
 * (int -> label) and [com.noop.analytics.WorkoutSport] (the picker + writeback). Reference the
 * library constants, never hardcoded ints — the raw values have changed.
 */
object ExerciseTypes {
    /** Ordered for the picker: common / distance first, then the rest, then Other. */
    val NAMES: Map<Int, String> = linkedMapOf(
        EX.EXERCISE_TYPE_RUNNING to "Running",
        EX.EXERCISE_TYPE_WALKING to "Walking",
        EX.EXERCISE_TYPE_HIKING to "Hiking",
        EX.EXERCISE_TYPE_BIKING to "Cycling",
        EX.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Open-water swim",
        EX.EXERCISE_TYPE_ROWING to "Rowing",
        EX.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill run",
        EX.EXERCISE_TYPE_BIKING_STATIONARY to "Indoor cycle",
        EX.EXERCISE_TYPE_SWIMMING_POOL to "Pool swim",
        EX.EXERCISE_TYPE_ROWING_MACHINE to "Row machine",
        EX.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
        EX.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength",
        EX.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
        EX.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        EX.EXERCISE_TYPE_YOGA to "Yoga",
        EX.EXERCISE_TYPE_PILATES to "Pilates",
        EX.EXERCISE_TYPE_BOXING to "Boxing",
        EX.EXERCISE_TYPE_BASKETBALL to "Basketball",
        EX.EXERCISE_TYPE_SOCCER to "Soccer",
        EX.EXERCISE_TYPE_BASEBALL to "Baseball",
        EX.EXERCISE_TYPE_ICE_HOCKEY to "Ice Hockey",
        EX.EXERCISE_TYPE_BADMINTON to "Badminton",
        EX.EXERCISE_TYPE_TENNIS to "Tennis",
        EX.EXERCISE_TYPE_SQUASH to "Squash",
        EX.EXERCISE_TYPE_RACQUETBALL to "Racquetball",
        EX.EXERCISE_TYPE_TABLE_TENNIS to "Table tennis",
        EX.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
        // Martial arts covers Jiu-Jitsu plus karate/judo/MMA etc.
        EX.EXERCISE_TYPE_MARTIAL_ARTS to "Martial arts",
        EX.EXERCISE_TYPE_DANCING to "Dancing",
        EX.EXERCISE_TYPE_GOLF to "Golf",
        EX.EXERCISE_TYPE_ROCK_CLIMBING to "Climbing",
        EX.EXERCISE_TYPE_STRETCHING to "Stretching",
        // Snow sports have a route, so they default GPS on (see DISTANCE_TYPES).
        EX.EXERCISE_TYPE_SKIING to "Skiing",
        EX.EXERCISE_TYPE_SNOWBOARDING to "Snowboarding",
        EX.EXERCISE_TYPE_OTHER_WORKOUT to "Other",
    )

    /**
     * Sports NOOP can pick that Health Connect has no dedicated type for; they ride a fallback HC
     * type (here "Other") while keeping their own NOOP label. Kept OUT of [NAMES] (int-keyed — entries
     * would collide on one HC int); an inbound record of that type reads back as generic, not original.
     */
    val EXTRA: List<Pair<String, Int>> = listOf(
        "Padel" to EX.EXERCISE_TYPE_OTHER_WORKOUT,
        // Pickleball: a racquet sport HC has no type for → writes as "Other", stays "Pickleball" on
        // our own rows. No route → GPS off.
        "Pickleball" to EX.EXERCISE_TYPE_OTHER_WORKOUT,
        // Bowling: HC has no type for it → writes as "Other", stays "Bowling" on our own rows.
        // No route → GPS off.
        "Bowling" to EX.EXERCISE_TYPE_OTHER_WORKOUT,
        // Indoor treadmill walk: HC has a treadmill-RUN type but no treadmill-WALK type, so this rides
        // on plain WALKING for writeback while keeping its own "Treadmill walk" label. Kept OUT of
        // DISTANCE_TYPES so GPS defaults off (an indoor session has no route).
        "Treadmill walk" to EX.EXERCISE_TYPE_WALKING,
        // Bodybuilding: no dedicated HC type, so it rides on STRENGTH_TRAINING for writeback and keeps
        // "Bodybuilding" on our own rows. No route → GPS off.
        "Bodybuilding" to EX.EXERCISE_TYPE_STRENGTH_TRAINING,
    )

    /** Types where a route makes sense -> GPS defaults on. */
    val DISTANCE_TYPES: Set<Int> = setOf(
        EX.EXERCISE_TYPE_RUNNING,
        EX.EXERCISE_TYPE_WALKING,
        EX.EXERCISE_TYPE_HIKING,
        EX.EXERCISE_TYPE_BIKING,
        EX.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        EX.EXERCISE_TYPE_ROWING,
        // Snow sports cover ground → a route makes sense, GPS defaults on.
        EX.EXERCISE_TYPE_SKIING,
        EX.EXERCISE_TYPE_SNOWBOARDING,
    )

    fun nameFor(type: Int): String = NAMES[type] ?: "Workout"
}
