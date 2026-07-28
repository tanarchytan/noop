package com.noop.analytics

import com.noop.ingest.ExerciseTypes

/** One selectable activity. [exerciseType] is a Health Connect EXERCISE_TYPE_* constant. */
data class Sport(val exerciseType: Int, val name: String, val isDistanceSport: Boolean)

object WorkoutSport {
    // Health-Connect-typed sports plus EXTRA sports HC has no type for (e.g. Padel), riding a
    // fallback HC type but keeping their own NOOP label. Extras insert just before "Other" so the
    // picker still ends on the generic catch-all. Shared by the picker and the HC writeback.
    val all: List<Sport> = buildList {
        ExerciseTypes.NAMES.forEach { (type, name) ->
            if (name == "Other") return@forEach // re-appended last, after the extras
            add(Sport(type, name, isDistanceSport = type in ExerciseTypes.DISTANCE_TYPES))
        }
        ExerciseTypes.EXTRA.forEach { (name, fallbackType) ->
            add(Sport(fallbackType, name, isDistanceSport = false))
        }
        ExerciseTypes.NAMES.entries.firstOrNull { it.value == "Other" }?.let { (type, name) ->
            add(Sport(type, name, isDistanceSport = type in ExerciseTypes.DISTANCE_TYPES))
        }
    }
    fun nameFor(type: Int) = ExerciseTypes.nameFor(type)

    /** The default when none is chosen ("Other"). */
    val default: Sport get() = all.first { it.name == "Other" }

    /** Sports where a step count is meaningful (feet on the ground), so the workout summary can show
     *  steps. Deliberately narrow: outdoor/treadmill run/walk and hiking, not cycling/rowing/swimming
     *  (no footfalls) or gym/court sports (a step tally would be noise). */
    val ON_FOOT_SPORTS: Set<String> = setOf("Running", "Walking", "Hiking", "Treadmill run", "Treadmill walk")

    /** Whether [sportName] (a catalogue name, possibly free-typed) is an on-foot sport that should show
     *  a step count. Case-insensitive, whitespace-trimmed. */
    fun isOnFoot(sportName: String): Boolean {
        val q = sportName.trim()
        return ON_FOOT_SPORTS.any { it.equals(q, ignoreCase = true) }
    }
}
