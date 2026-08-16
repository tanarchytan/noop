package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository

/**
 * One-shot: re-derive every stored session's resting HR under the definition that ships now.
 *
 * Resting HR used to be the lowest 5-minute rolling mean and is now the in-bed median, which reads
 * about ten bpm higher. The value is persisted per session, so a store written across that change
 * holds floors on the old nights and medians on the new, and the personal baseline is folded from
 * the mixture. Charge scores resting HR against that baseline with a spread of two bpm, so the unit
 * change lands as a multi-sigma deviation until the baseline drifts across a fortnight.
 *
 * This re-stages nothing. Stages, efficiency and HRV are untouched; only the one column whose
 * definition moved is recomputed, from the raw heart rate still in the store.
 */
object RestingHrRebase {

    /**
     * Run once per definition change, guarded by the caller's persisted flag — the same shape as
     * `runEffortRescoreIfNeeded`.
     */
    suspend fun runIfNeeded(
        repo: WhoopRepository,
        flagGet: () -> Boolean,
        flagSet: () -> Unit,
    ): Int {
        if (flagGet()) return 0
        val changed = rebase(
            // Raw HR is keyed under the RAW device id while a computed session carries the "-noop"
            // sibling, so read the union rather than pinning one id - the trap that hid the sleep
            // movement line.
            sessions = { repo.sleepSessionsUnion(from = 0L, to = Long.MAX_VALUE) },
            hrFor = { from, to -> repo.hrSamplesUnion(from, to) },
            save = { repo.upsertSleepSessions(it) },
        )
        flagSet()
        return changed
    }

    /**
     * The decision, separated from the store so it can be driven directly. Returns the rows rewritten.
     *
     * A session whose heart rate has left the store keeps whatever it had — it is never overwritten
     * with null, because a missing input is not evidence that the stored value was wrong.
     */
    suspend fun rebase(
        sessions: suspend () -> List<SleepSession>,
        hrFor: suspend (Long, Long) -> List<HrSample>,
        save: suspend (List<SleepSession>) -> Unit,
    ): Int {
        val all = sessions()
        if (all.isEmpty()) return 0
        val updated = ArrayList<SleepSession>()
        for (s in all) {
            val hr = hrFor(s.startTs, s.endTs)
            if (hr.isEmpty()) continue
            val median = RustScores.sessionRestingHr(hr, s.startTs, s.endTs) ?: continue
            if (s.restingHr == median) continue
            updated += s.copy(restingHr = median)
        }
        if (updated.isNotEmpty()) save(updated)
        return updated.size
    }
}
