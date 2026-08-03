package com.noop.ui

// MARK: - Live-HR hero buffer
//
// The rolling 1 Hz sample buffer behind the Health Monitor's live-HR hero. Pure, no Compose, so the
// guard + cap contract is JVM-testable (LiveHrSamplingTest).

/** One streamed live-HR reading with the wall-clock time it arrived (epoch millis). Carrying the
 * time — not a bare bpm — is what lets the hero render a real time x-axis. */
data class LiveHrSample(val timeMs: Long, val bpm: Double)

/** The live-HR hero's rolling buffer cap: 180 samples at the 1 Hz tick is a strict ~3 minutes. */
internal const val LIVE_HR_BUFFER_CAP = 180

/** One 1 Hz tick of the hero buffer : bank the latest smoothed HR when it is present and
 * physiologically plausible (30..220, the same range guard the old on-change append used), then trim
 * the buffer to the rolling cap. Pure so the guard + cap behaviour is JVM-testable. */
internal fun appendLiveHrSample(
    history: MutableList<LiveHrSample>,
    bpm: Int?,
    timeMs: Long,
    cap: Int = LIVE_HR_BUFFER_CAP,
) {
    val v = bpm ?: return
    if (v !in 30..220) return
    history.add(LiveHrSample(timeMs = timeMs, bpm = v.toDouble()))
    while (history.size > cap) history.removeAt(0)
}
