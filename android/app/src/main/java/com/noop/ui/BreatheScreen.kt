package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.BreathPacer
import com.noop.analytics.Hrv
import com.noop.analytics.ResonanceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The pacer ring's geometry: the hero's diameter and the stroke the breath arc sweeps in. */
private val BREATHE_RING_DIAMETER = 280.dp
private val BREATHE_RING_STROKE = 20.dp

// MARK: - Pace presets (ported from BreathingView.Pace)

private enum class Pace(@StringRes val label: Int) {
    Relax(R.string.breathe_pace_relax),
    Coherence(R.string.breathe_pace_coherence),
    Box(R.string.breathe_pace_box),
    Resonance(R.string.breathe_resonance);   // the user's locked pace (br/min) — only offered once a pace is locked

    /** Inhale seconds — for [Resonance] it derives from the locked bpm at a 40:60 inhale:exhale split. */
    fun inhale(lockedBpm: Double? = null): Double = when (this) {
        Relax -> 4.0
        Coherence -> 5.5
        Box -> 4.0
        Resonance -> {
            val cycle = 60.0 / (lockedBpm ?: ResonanceEngine.FALLBACK_BPM)
            cycle * BreathPacer.DEFAULT_INHALE_FRACTION
        }
    }

    fun exhale(lockedBpm: Double? = null): Double = when (this) {
        Relax -> 6.0
        Coherence -> 5.5
        Box -> 4.0
        Resonance -> {
            val cycle = 60.0 / (lockedBpm ?: ResonanceEngine.FALLBACK_BPM)
            cycle * (1 - BreathPacer.DEFAULT_INHALE_FRACTION)
        }
    }

    fun cycle(lockedBpm: Double? = null): Double = inhale(lockedBpm) + exhale(lockedBpm)
    fun bpm(lockedBpm: Double? = null): Double = 60.0 / cycle(lockedBpm)
}

/** One line describing the pace; Resonance names the user's locked breaths-per-minute. */
@Composable
private fun paceTagline(pace: Pace, lockedBpm: Double? = null): String = when (pace) {
    Pace.Relax -> stringResource(R.string.breathe_tagline_relax)
    Pace.Coherence -> stringResource(R.string.breathe_tagline_coherence)
    Pace.Box -> stringResource(R.string.breathe_tagline_box)
    Pace.Resonance -> stringResource(
        R.string.breathe_tagline_resonance,
        String.format(Locale.US, "%.1f", lockedBpm ?: ResonanceEngine.FALLBACK_BPM),
    )
}

private enum class Phase { Inhale, Exhale }

/** The three biofeedback layers as a mode switch (mirrors BreathingView.Mode). */
private enum class BreatheMode(@StringRes val label: Int) {
    Breathe(R.string.breathe_title),
    Resonance(R.string.breathe_resonance),
    Calm(R.string.breathe_calm_me),
}

/**
 * Breathe — HRV haptic breathing biofeedback. The strap both measures HRV (R-R
 * intervals) and buzzes (haptic motor), so we pace the breath with a felt cue and
 * watch HRV respond live. One pulse on the inhale, two on the exhale. Live HR + a
 * rolling RMSSD show the autonomic response building. Ports BreathingView.swift.
 */
@Composable
fun BreatheScreen(viewModel: AppViewModel) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val bpm by viewModel.bpm.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var mode by remember { mutableStateOf(BreatheMode.Breathe) }
    // The user's locked resonance pace (br/min), or null — read fresh; the sweep writes it.
    var lockedBpm by remember { mutableStateOf(BiofeedbackPrefs.lockedPace(context)) }

    var pace by remember { mutableStateOf(Pace.Coherence) }
    var running by remember { mutableStateOf(false) }

    // Opt-in audio pacer — a soft tone at each phase change (a brighter note on the inhale, a lower one
    // on the exhale). Default OFF (manual-first). The tone player honours the ringer mode, so a phone on
    // silent/vibrate stays quiet. SharedPreferences isn't reactive: read once, mirror writes into this
    // state.
    var audioCues by remember {
        mutableStateOf(NoopPrefs.of(context).getBoolean(KEY_BREATHE_AUDIO_CUES, false))
    }
    val tonePlayer = remember { BreathTonePlayer(context) }
    DisposableEffect(Unit) { onDispose { tonePlayer.release() } }
    var phase by remember { mutableStateOf(Phase.Inhale) }
    var sessionSeconds by remember { mutableIntStateOf(0) }
    var breathCount by remember { mutableIntStateOf(0) }

    // Rolling R-R buffer + RMSSD (computed by the shared analytics Hrv).
    val rrBuffer = remember { mutableStateOf<List<Int>>(emptyList()) }
    var rmssd by remember { mutableStateOf<Double?>(null) }
    val rrWindow = 30

    // Pre/post outcome capture: the baseline locks at start (or to the first rolling
    // value inside the session's first ~60s); mean/peak stream while running. The last
    // completed outcome persists via NoopPrefs (display-only — no Room table).
    var baselineRmssd by remember { mutableStateOf<Double?>(null) }
    var sessionRmssdSum by remember { mutableDoubleStateOf(0.0) }
    var sessionRmssdCount by remember { mutableIntStateOf(0) }
    var sessionRmssdPeak by remember { mutableDoubleStateOf(0.0) }
    var endedOutcome by remember { mutableStateOf<String?>(null) }
    // SharedPreferences isn't reactive: read once, mirror writes into this state.
    var lastStoredOutcome by remember {
        mutableStateOf(NoopPrefs.of(context).getString(KEY_BREATHE_LAST_OUTCOME, "").orEmpty())
    }

    // Bank the just-ended session's outcome (mirrors BreathingView.captureOutcome):
    // null below the 2-minute floor; "—" stays display-only, never persisted.
    fun endSession() {
        val core = breatheOutcomeCore(
            baseline = baselineRmssd,
            sum = sessionRmssdSum,
            count = sessionRmssdCount,
            peak = sessionRmssdPeak,
            seconds = sessionSeconds,
        )
        endedOutcome = core
        if (core != null && core != "—") {
            lastStoredOutcome = core
            NoopPrefs.of(context).edit().putString(KEY_BREATHE_LAST_OUTCOME, core).apply()
        }
    }

    // Orb expansion 0..1; driven by an eased animation per breath phase.
    val orbTarget = if (running && phase == Phase.Inhale) 1f else 0f
    val phaseDurationMs = ((if (phase == Phase.Inhale) pace.inhale(lockedBpm) else pace.exhale(lockedBpm)) * 1000).toInt()
    val orbProgress by animateFloatAsState(
        targetValue = orbTarget,
        animationSpec = tween(if (running) phaseDurationMs else 800, easing = Motion.easeInOut),
        label = "orb",
    )

    // Ingest new R-R intervals into the rolling buffer and recompute RMSSD.
    // Collect the BLE state flow directly so updates are observed reactively.
    LaunchedEffect(Unit) {
        viewModel.live
            .map { it.rr }
            .distinctUntilChanged()
            .collect { rr ->
                if (rr.isEmpty()) return@collect
                val merged = (rrBuffer.value + rr).takeLast(rrWindow)
                rrBuffer.value = merged
                val r = if (merged.size >= 2) Hrv.rmssd(merged) else null
                rmssd = r
                // Outcome capture: while running, lock the baseline (first value
                // inside ~60s when none was available at start) and stream the
                // session mean/peak.
                if (running && r != null) {
                    if (baselineRmssd == null && sessionSeconds <= 60) baselineRmssd = r
                    sessionRmssdSum += r
                    sessionRmssdCount += 1
                    if (r > sessionRmssdPeak) sessionRmssdPeak = r
                }
            }
    }

    // Session clock — ticks only while running.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000)
            sessionSeconds += 1
        }
    }

    // The breath engine: alternate phases, firing the haptic cue at the START of
    // each phase (1 pulse on inhale, 2 on exhale) — mirrors BreathingView.armPhase.
    LaunchedEffect(running, pace) {
        if (!running) return@LaunchedEffect
        while (true) {
            // Inhale: cue, then hold for the inhale duration.
            phase = Phase.Inhale
            viewModel.buzz(loops = 1)
            if (audioCues) tonePlayer.play(BreathTone.Inhale)
            delay((pace.inhale(lockedBpm) * 1000).toLong())
            // Exhale: cue, then hold for the exhale duration.
            phase = Phase.Exhale
            viewModel.buzz(loops = 2)
            if (audioCues) tonePlayer.play(BreathTone.Exhale)
            delay((pace.exhale(lockedBpm) * 1000).toLong())
            breathCount += 1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving mid-session still banks the outcome.
            if (running) {
                endSession()
                // also tell the strap to stop haptics on the way out so a leftover pattern can't
                // wedge the strap if the link drops after we navigate away. Best-effort (guarded send).
                viewModel.stopHaptics()
            }
            running = false
        }
    }

    // if the strap drops WHILE a session is live, end the session AND fire the stop-haptics clear.
    // The breath-engine LaunchedEffect already stops scheduling pulses once `running` flips false; this
    // adds the strap-side clear (best-effort) and banks the outcome.
    LaunchedEffect(live.bonded) {
        if (!live.bonded && running) {
            running = false
            endSession()
            viewModel.stopHaptics()
        }
    }

    // No topBackground: the scaffold paints Palette.surfaceBase, the one canvas every screen shares. The
    // decorated backdrop it used to carry painted fixed dark-mode colours in both themes.
    ScreenScaffold(
        title = stringResource(R.string.breathe_title),
        subtitle = stringResource(R.string.breathe_subtitle),
    ) {
        // Mode switch — Breathe / Resonance / Calm me.
        SegmentedPillControl(
            items = BreatheMode.entries.toList(),
            selection = mode,
            label = { context.getString(it.label) },
            onSelect = {
                if (running) { running = false; endSession() }
                mode = it
                lockedBpm = BiofeedbackPrefs.lockedPace(context)
            },
        )

        // L3 passive stress check-in card (surfaces when StressOnsetDetector fires).
        StressCheckInCard(
            onBreatheNow = {
                // Switch to Breathe and start a one-minute session. Coherence (5.5 br/min) is the
                // resonance fallback pace; the felt cue is identical (one buzz in, two out).
                mode = BreatheMode.Breathe
                pace = Pace.Coherence
                sessionSeconds = 0; breathCount = 0; endedOutcome = null
                baselineRmssd = rmssd
                sessionRmssdSum = 0.0; sessionRmssdCount = 0; sessionRmssdPeak = 0.0
                running = true
            },
        )

        when (mode) {
            BreatheMode.Resonance -> {
                ResonanceMode(viewModel = viewModel, live = live, lockedBpm = lockedBpm,
                    onLocked = { lockedBpm = BiofeedbackPrefs.lockedPace(context) })
                return@ScreenScaffold
            }
            BreatheMode.Calm -> {
                CalmMode(viewModel = viewModel, live = live, bpm = bpm)
                return@ScreenScaffold
            }
            BreatheMode.Breathe -> Unit // fall through to the shipped trainer below
        }

        // Status row.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatePill(
                stringResource(if (running) R.string.breathe_session_live else R.string.breathe_ready),
                tone = if (running) StrandTone.Accent else StrandTone.Neutral,
                pulsing = running,
            )
            Spacer(Modifier.width(Metrics.space8))
            if (live.bonded) {
                StatePill(stringResource(R.string.breathe_haptics_on), tone = StrandTone.Positive)
            } else {
                StatePill(stringResource(R.string.breathe_visual_only), tone = StrandTone.Warning)
            }
            Spacer(Modifier.weight(1f))
            Text(timeString(sessionSeconds), style = NoopType.number(15f), color = Palette.textPrimary)
            Spacer(Modifier.width(Metrics.space6))
            Text(
                stringResource(R.string.breathe_breath_count, breathCount),
                style = NoopType.captionNumber, color = Palette.textSecondary,
            )
        }

        // The hero card, on the one shared card surface so it reads in both schemes.
        NoopCard(padding = Metrics.space24) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space18),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Overline(stringResource(pace.label))
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(
                            R.string.breathe_pace_bpm,
                            String.format(Locale.US, "%.1f", pace.bpm(lockedBpm)),
                        ),
                        style = NoopType.captionNumber, color = Palette.textSecondary,
                    )
                }

                // The breathe pacer: the ring's arc sweeps out on the inhale and retracts on the exhale,
                // driven by the SAME eased `orbProgress` the vessel used (0..1, from the phase-duration
                // tween), so the breath timing is untouched. The live BPM sits in the centre; no BPM yet
                // leaves the ring unlabelled and the honest dash takes its place.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BREATHE_RING_DIAMETER),
                    contentAlignment = Alignment.Center,
                ) {
                    GlowRing(
                        fraction = orbProgress,
                        value = bpm?.toDouble() ?: 0.0,
                        color = Palette.restBright,
                        diameter = BREATHE_RING_DIAMETER,
                        lineWidth = BREATHE_RING_STROKE,
                        fillKey = "breathe.pacer",
                        showsLabel = bpm != null,
                        format = { it.roundToInt().toString() },
                        caption = "BPM",
                    )
                    if (bpm == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clearAndSetSemantics {},
                        ) {
                            Text(
                                stringResource(R.string.breathe_no_reading),
                                style = NoopType.headline,
                                color = Palette.textTertiary,
                            )
                            Text(
                                "BPM",
                                style = NoopType.footnote.copy(letterSpacing = 0.8.sp),
                                color = Palette.textTertiary,
                            )
                        }
                    }
                }

                Text(
                    text = if (running) stringResource(phaseWord(phase)) else paceTagline(pace, lockedBpm),
                    style = NoopType.subhead,
                    color = if (running) Palette.restBright else Palette.textSecondary,
                )

                // The locked-resonance pill only appears once a pace has been locked, so a locked pace
                // is selectable here.
                val availablePaces = if (lockedBpm != null) {
                    listOf(Pace.Relax, Pace.Coherence, Pace.Box, Pace.Resonance)
                } else {
                    listOf(Pace.Relax, Pace.Coherence, Pace.Box)
                }
                SegmentedPillControl(
                    items = availablePaces,
                    selection = pace,
                    label = { context.getString(it.label) },
                    onSelect = { pace = it },
                )

                // Opt-in audio pacer toggle — soft tone on each phase, honours the ringer mode.
                AudioCueToggle(
                    checked = audioCues,
                    onChange = {
                        audioCues = it
                        NoopPrefs.of(context).edit().putBoolean(KEY_BREATHE_AUDIO_CUES, it).apply()
                    },
                )
            }
        }

        // Controls.
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (running) {
                        running = false
                        endSession()
                        // clear any pattern the strap is mid-way through so a drop right after stop
                        // can't wedge its haptic manager. Best-effort (no-op when unbonded / on a 5/MG).
                        viewModel.stopHaptics()
                    } else {
                        sessionSeconds = 0
                        breathCount = 0
                        endedOutcome = null
                        // Baseline: prefer the pre-session rolling value; otherwise the
                        // R-R collector locks the first value inside the first ~60s.
                        baselineRmssd = rmssd
                        sessionRmssdSum = 0.0
                        sessionRmssdCount = 0
                        sessionRmssdPeak = 0.0
                        running = true
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Palette.statusCritical else Palette.accent,
                    contentColor = Palette.surfaceBase,
                ),
            ) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    stringResource(if (running) R.string.breathe_stop_session else R.string.breathe_start_session),
                    style = NoopType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = { viewModel.buzz(loops = 1) },
                enabled = live.bonded,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(
                    stringResource(R.string.breathe_test_buzz),
                    style = NoopType.body, maxLines = 1, softWrap = false,
                )
            }
        }

        // Calm one-line outcome — fresh after a finished session, persisted on re-entry.
        // Hidden while running and when there is nothing honest to show.
        val endedText = endedOutcome
        val outcomeLine = when {
            running -> null
            endedText == "—" -> stringResource(R.string.breathe_outcome_no_rr)
            endedText != null -> stringResource(R.string.breathe_outcome_rmssd, endedText)
            lastStoredOutcome.isNotEmpty() ->
                stringResource(R.string.breathe_outcome_last_session, lastStoredOutcome)
            else -> null
        }
        if (outcomeLine != null) {
            // The session's HRV outcome as a frosted Rest-tinted card with a TrendChip for the
            // vs-start RMSSD change. Presentation-only — the same outcome String + chip source.
            val chipSource = endedOutcome ?: lastStoredOutcome.takeIf { it.isNotEmpty() }
            val trend = chipSource?.takeIf { it != "—" }?.let { leadingSignedPercent(it) }
            NoopCard(padding = 14.dp, tint = Palette.restColor) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Air,
                        contentDescription = null,
                        tint = Palette.restBright,
                        modifier = Modifier.size(16.dp).padding(end = 8.dp),
                    )
                    Text(
                        outcomeLine,
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (trend != null) {
                        val sign = if (trend >= 0) "+" else "−"
                        TrendChip(
                            text = stringResource(R.string.breathe_trend_hrv, sign, kotlin.math.abs(trend)),
                            color = if (trend >= 0) Palette.statusPositive else Palette.textTertiary,
                        )
                    }
                }
            }
        }

        // Readout tiles.
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            ReadoutTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.breathe_tile_heart_rate),
                value = bpm?.toString() ?: "—",
                unit = "bpm",
                accent = Palette.metricRose,
                caption = stringResource(
                    if (live.worn) R.string.breathe_live else R.string.breathe_tile_not_worn,
                ),
            )
            ReadoutTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.breathe_tile_hrv),
                value = rmssd?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
                unit = "ms",
                accent = Palette.metricPurple,
                caption = if (rrBuffer.value.isEmpty()) stringResource(R.string.breathe_tile_waiting_rr)
                else stringResource(R.string.breathe_tile_last_beats, rrBuffer.value.size),
            )
            ReadoutTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.breathe_tile_pace),
                value = String.format(Locale.US, "%.1f", pace.bpm(lockedBpm)),
                unit = stringResource(R.string.breathe_unit_br_min),
                accent = Palette.restBright,
                caption = String.format(Locale.US, "%.0f / %.0fs", pace.inhale(lockedBpm), pace.exhale(lockedBpm)),
            )
        }

        // Coherence estimate.
        CoherenceCard(rmssd)

        if (!live.bonded) HapticHint()
    }
}

// MARK: - Audio cue toggle

@Composable
private fun AudioCueToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val audioCuesLabel = stringResource(R.string.breathe_audio_cues)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (checked) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = null,
            tint = if (checked) Palette.restBright else Palette.textTertiary,
            modifier = Modifier.size(16.dp).padding(end = 10.dp),
        )
        // The sentence is longer than the width the switch leaves it, so it wraps rather than being cut
        // mid-word, and the row keeps a gap so the last glyph never touches the switch.
        Column(modifier = Modifier.weight(1f).padding(end = Metrics.space10)) {
            Text(audioCuesLabel, style = NoopType.footnote, color = Palette.textSecondary)
            Text(
                stringResource(R.string.breathe_audio_cues_detail),
                style = NoopType.caption, color = Palette.textTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
            modifier = Modifier.semantics { contentDescription = audioCuesLabel },
        )
    }
}

// MARK: - Readout tile

@Composable
private fun ReadoutTile(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    caption: String,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier.height(Metrics.tileHeight), padding = 14.dp) {
        Column {
            Overline(label)
            Spacer(Modifier.weight(1f))
            // The unit never breaks: "br/min" carries a slash, which Android treats as a break
            // opportunity, so a tight tile split it across two lines.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = NoopType.number(26f), color = accent, maxLines = 1)
                Spacer(Modifier.width(Metrics.space4))
                Text(
                    unit, style = NoopType.caption, color = Palette.textTertiary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                caption, style = NoopType.footnote, color = Palette.textTertiary,
                maxLines = 1, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// MARK: - Coherence card

@Composable
private fun CoherenceCard(rmssd: Double?) {
    val frac = (rmssd?.let { (it / 120.0).coerceIn(0.0, 1.0) } ?: 0.0).toFloat()
    val (labelRes, tone) = coherenceState(rmssd)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(stringResource(R.string.breathe_coherence_estimate))
                Spacer(Modifier.weight(1f))
                StatePill(stringResource(labelRes), tone = tone)
            }
            // Normalized bar — RMSSD 0..120ms → 0..1 — on the theme's inset track so it reads in both
            // schemes. Same fraction as before.
            LinearProgressIndicator(
                progress = { frac },
                color = Palette.restBright,
                trackColor = Palette.surfaceInset,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.breathe_coherence_disclaimer),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

private fun coherenceState(rmssd: Double?): Pair<Int, StrandTone> = when {
    rmssd == null -> R.string.breathe_coherence_no_data to StrandTone.Neutral
    rmssd < 20 -> R.string.breathe_coherence_building to StrandTone.Warning
    rmssd < 45 -> R.string.breathe_coherence_settling to StrandTone.Neutral
    rmssd < 80 -> R.string.breathe_coherence_coherent to StrandTone.Positive
    else -> R.string.breathe_coherence_deep_calm to StrandTone.Positive
}

// MARK: - Session outcome

/** NoopPrefs key for the last completed session's outcome core. Display-only persistence — no Room
 *  table. */
private const val KEY_BREATHE_LAST_OUTCOME = "breathe.lastOutcome"

/**
 * End-of-session outcome core: "+18% vs start · peak 64 ms" — the session MEAN
 * rolling RMSSD vs the start baseline. Null below the 2-minute floor (abandoned —
 * show nothing); "—" when the session ran long enough but there was no usable
 * baseline or no R-R data (never invent a number). Mirrors
 * BreathingView.captureOutcome case-for-case.
 */
internal fun breatheOutcomeCore(
    baseline: Double?,
    sum: Double,
    count: Int,
    peak: Double,
    seconds: Int,
): String? {
    if (seconds < 120) return null
    if (baseline == null || baseline <= 0 || count == 0) return "—"
    val mean = sum / count
    val pct = ((mean - baseline) / baseline * 100).roundToInt()
    return String.format(Locale.US, "%+d%% vs start · peak %.0f ms", pct, peak)
}

/**
 * Parse a leading "+18%"/"-7%" from an outcome core, returning the integer percent — the signed
 * RMSSD-vs-start change shown as a TrendChip. Null when no signed % leads (abandoned / "—" line).
 * Display-only: it reads the same String the outcome line already shows, never new data.
 */
internal fun leadingSignedPercent(s: String): Int? {
    val pct = s.indexOf('%')
    if (pct <= 0) return null
    return s.substring(0, pct).replace("+", "").trim().toIntOrNull()
}

// MARK: - Haptic hint

@Composable
private fun HapticHint() {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.statusWarning.copy(alpha = 0.08f), shape)
            .border(Metrics.divider, Palette.statusWarning.copy(alpha = 0.25f), shape)
            .padding(Metrics.space14),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Palette.statusWarning)
        Text(
            stringResource(R.string.breathe_haptic_hint),
            style = NoopType.footnote, color = Palette.textSecondary,
        )
    }
}

@StringRes
private fun phaseWord(phase: Phase): Int = when (phase) {
    Phase.Inhale -> R.string.breathe_phase_inhale
    Phase.Exhale -> R.string.breathe_phase_exhale
}

private fun timeString(total: Int): String =
    String.format(Locale.US, "%02d:%02d", total / 60, total % 60)

// ════════════════════════════════════════════════════════════════════════════
// L3 — Passive stress check-in card
// ════════════════════════════════════════════════════════════════════════════

/**
 * The L3 closed-loop JITAI surface — Kotlin twin of StressCheckInCard.swift. Observes
 * [StressNudgeCenter.pending]; when the shipped [com.noop.analytics.StressOnsetDetector] fires (a fresh,
 * non-metabolic HRV dip while still), the central hook (Wave 3) calls [StressNudgeCenter.present] and this
 * dismissible card appears. NEVER an alarm, NEVER a push, NEVER a diagnosis — "HRV dipped while you were
 * still", with Breathe now / Not now / Turn off.
 */
@Composable
private fun StressCheckInCard(onBreatheNow: () -> Unit) {
    val context = LocalContext.current
    val nudge by StressNudgeCenter.pending.collectAsStateWithLifecycle()
    val n = nudge ?: return

    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Air, contentDescription = null, tint = Palette.restBright,
                    modifier = Modifier.size(16.dp).padding(end = 8.dp))
                Overline(stringResource(R.string.breathe_checkin_overline))
                Spacer(Modifier.weight(1f))
                StatePill(stringResource(R.string.breathe_checkin_passive), tone = StrandTone.Neutral)
            }
            Text(
                stringResource(R.string.breathe_checkin_headline),
                style = NoopType.subhead, color = Palette.textPrimary,
            )
            honestNudgeLine(n)?.let {
                Text(it, style = NoopType.footnote, color = Palette.textTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space10), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { StressNudgeCenter.dismiss(); onBreatheNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.accent, contentColor = Palette.surfaceBase),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.breathe_checkin_breathe_now), style = NoopType.headline) }
                OutlinedButton(
                    onClick = { StressNudgeCenter.dismiss() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.textSecondary),
                ) { Text(stringResource(R.string.breathe_checkin_not_now), style = NoopType.body) }
                TextButton(onClick = {
                    BiofeedbackPrefs.setCheckInEnabled(context, false)
                    StressNudgeCenter.dismiss()
                }) {
                    Text(
                        stringResource(R.string.breathe_checkin_turn_off),
                        style = NoopType.body, color = Palette.textSecondary,
                    )
                }
            }
            Text(
                stringResource(R.string.breathe_checkin_disclaimer),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun honestNudgeLine(n: StressNudgeCenter.Nudge): String? {
    val fast = n.fastRMSSD ?: return null
    val base = n.baselineRMSSD ?: return null
    if (base <= 0.0) return null
    return stringResource(
        R.string.breathe_checkin_nudge,
        String.format(Locale.US, "%.0f", fast),
        String.format(Locale.US, "%.0f", base),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// L1 — Resonance mode (the "find my pace" sweep + result)
// ════════════════════════════════════════════════════════════════════════════

/**
 * The L1 surface — Kotlin twin of ResonanceModeView. Explainer → full/quick sweep → live
 * "Testing 5.5 br/min…" + RSA progress → dated result card (locked pace + RSA-by-pace curve, or the
 * honest "couldn't lock today" fallback). Self-contained: the sweep is driven by a coroutine
 * [LaunchedEffect] walking [BreathPacer] cue lists and firing [AppViewModel.buzz], collecting clean R-R
 * per pace, then scoring with [ResonanceEngine.sweep].
 */
@Composable
private fun ResonanceMode(
    viewModel: AppViewModel,
    live: com.noop.ble.LiveState,
    lockedBpm: Double?,
    onLocked: () -> Unit,
) {
    val context = LocalContext.current
    var sweeping by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }
    var sweepLabel by remember { mutableStateOf<String?>(null) }
    var sweepProgress by remember { mutableDoubleStateOf(0.0) }
    var result by remember { mutableStateOf<ResonanceEngine.SweepResult?>(null) }
    val secondsPerPace = 120

    // The sweep coroutine: pace each candidate, collect its clean R-R, score the whole thing.
    LaunchedEffect(sweeping, quick) {
        if (!sweeping) return@LaunchedEffect
        val paces = if (quick) ResonanceEngine.QUICK_SWEEP_PACES else ResonanceEngine.FULL_SWEEP_PACES
        val samples = ArrayList<ResonanceEngine.PaceSample>()
        for ((index, bpm) in paces.withIndex()) {
            sweepLabel = context.getString(
                R.string.breathe_resonance_testing,
                String.format(Locale.US, "%.1f", bpm),
            )
            val startTs = (System.currentTimeMillis() / 1000).toInt()
            val bucket = ArrayList<ResonanceEngine.RrBeat>()
            // Collect this pace's R-R while we pace it; fire the cue list (1 inhale / 2 exhale) on tempo.
            val cues = BreathPacer.schedule(bpm = bpm,
                cycles = maxOf(1, (secondsPerPace * bpm / 60.0).roundToInt()))
            var elapsedMs = 0
            for (cue in cues) {
                delay((cue.offsetMs - elapsedMs).toLong().coerceAtLeast(0))
                elapsedMs = cue.offsetMs
                // Read the LATEST live state off the flow (the captured `live` param is a snapshot that
                // only refreshes on recomposition; the standard profile is the reliable R-R source).
                val liveNow = viewModel.live.value
                if (liveNow.encryptedBond) viewModel.buzz(loops = cue.loops)
                val now = (System.currentTimeMillis() / 1000).toInt()
                for (ms in liveNow.rr) if (ms in 301..1999) bucket.add(ResonanceEngine.RrBeat(now, ms))
            }
            delay(4000) // let the last exhale finish before closing the window
            val endTs = (System.currentTimeMillis() / 1000).toInt()
            samples.add(ResonanceEngine.PaceSample(bpm, bucket, startTs, endTs))
            sweepProgress = (index + 1).toDouble() / paces.size
        }
        val swept = ResonanceEngine.sweep(samples)
        result = swept
        if (swept.didLock) {
            BiofeedbackPrefs.saveLockedPace(context, swept.lockedBpm, System.currentTimeMillis())
            onLocked()
        }
        sweepLabel = null
        sweepProgress = 0.0
        sweeping = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Explainer.
        NoopCard(tint = Palette.restColor) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Overline(stringResource(R.string.breathe_resonance_find_pace))
                    Spacer(Modifier.weight(1f))
                    StatePill(
                        stringResource(
                            if (live.bonded) R.string.breathe_haptics_on else R.string.breathe_visual_only,
                        ),
                        tone = if (live.bonded) StrandTone.Positive else StrandTone.Warning,
                    )
                }
                Text(
                    stringResource(R.string.breathe_resonance_explainer),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
                Text(
                    stringResource(R.string.breathe_resonance_disclaimer),
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }
        }

        if (sweeping) {
            // Live sweep progress.
            NoopCard(tint = Palette.restColor) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sweepLabel ?: stringResource(R.string.breathe_resonance_sweeping),
                            style = NoopType.headline, color = Palette.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        StatePill(stringResource(R.string.breathe_live), tone = StrandTone.Accent, pulsing = true)
                    }
                    ProgressBar(sweepProgress.toFloat())
                    OutlinedButton(
                        onClick = { sweeping = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.statusCritical),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.breathe_resonance_stop_sweep), style = NoopType.body)
                    }
                }
            }
        } else {
            // Start controls.
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                    Button(
                        onClick = { quick = false; result = null; sweeping = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent, contentColor = Palette.surfaceBase),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.breathe_resonance_full_sweep), style = NoopType.headline)
                    }
                    OutlinedButton(
                        onClick = { quick = true; result = null; sweeping = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.breathe_resonance_quick_sweep), style = NoopType.body)
                    }
                    Text(
                        stringResource(R.string.breathe_resonance_sit_still),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }

        val shown = result
        if (shown != null) {
            ResonanceResultCard(shown, context)
        } else if (lockedBpm != null) {
            LockedPaceCard(lockedBpm, context)
        }

        if (!live.bonded) HapticHint()
    }
}

@Composable
private fun ResonanceResultCard(result: ResonanceEngine.SweepResult, context: android.content.Context) {
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(
                    stringResource(
                        if (result.didLock) R.string.breathe_resonance_your_pace
                        else R.string.breathe_resonance_no_lock,
                    ),
                )
                Spacer(Modifier.weight(1f))
                StatePill(
                    stringResource(
                        if (result.didLock) R.string.breathe_resonance_locked
                        else R.string.breathe_resonance_fallback,
                    ),
                    tone = if (result.didLock) StrandTone.Positive else StrandTone.Neutral,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.US, "%.1f", result.lockedBpm),
                    style = NoopType.number(40f), color = Palette.restBright)
                Spacer(Modifier.width(Metrics.space6))
                Text(stringResource(R.string.breathe_unit_br_min), style = NoopType.subhead,
                    color = Palette.textTertiary, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (!result.didLock) {
                Text(
                    stringResource(R.string.breathe_resonance_no_lock_body),
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }
            RsaCurve(result.scores)
            val dateMs = BiofeedbackPrefs.lockedPaceDateMs(context)
            if (result.didLock && dateMs > 0) {
                Text(stringResource(R.string.breathe_resonance_locked_on, formatDay(dateMs)),
                    style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
    }
}

@Composable
private fun LockedPaceCard(bpm: Double, context: android.content.Context) {
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(stringResource(R.string.breathe_locked_pace_title))
                Spacer(Modifier.weight(1f))
                StatePill(stringResource(R.string.breathe_resonance_locked), tone = StrandTone.Positive)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.US, "%.1f", bpm), style = NoopType.number(34f), color = Palette.restBright)
                Spacer(Modifier.width(Metrics.space6))
                Text(stringResource(R.string.breathe_unit_br_min), style = NoopType.subhead,
                    color = Palette.textTertiary, modifier = Modifier.padding(bottom = 4.dp))
            }
            val dateMs = BiofeedbackPrefs.lockedPaceDateMs(context)
            if (dateMs > 0) {
                Text(stringResource(R.string.breathe_locked_pace_on, formatDay(dateMs)),
                    style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
    }
}

/** A compact RSA-amplitude-by-pace summary (the resonance curve). Unscored paces read "—". */
@Composable
private fun RsaCurve(scores: List<ResonanceEngine.PaceScore>) {
    val maxRsa = scores.mapNotNull { it.rsaAmplitude }.maxOrNull() ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Overline(stringResource(R.string.breathe_rsa_by_pace))
        scores.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Text(String.format(Locale.US, "%.1f", s.bpm), style = NoopType.captionNumber,
                    color = Palette.textSecondary, modifier = Modifier.width(34.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Metrics.space8)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.surfaceInset),
                ) {
                    val frac = ((s.rsaAmplitude ?: 0.0) / maxOf(maxRsa, 0.0001)).toFloat()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac.coerceIn(0.04f, 1f))
                            .height(Metrics.space8)
                            .clip(RoundedCornerShape(50))
                            .background(Palette.restBright.copy(alpha = if (s.scored) 0.9f else 0.25f)),
                    )
                }
                Text(s.rsaAmplitude?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    style = NoopType.captionNumber,
                    color = if (s.scored) Palette.textSecondary else Palette.textTertiary,
                    modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// L2 — "Calm me" mode (below-HR relaxation metronome)
// ════════════════════════════════════════════════════════════════════════════

/**
 * The L2 surface — Kotlin twin of CalmModeView. A "Calm me · 3 min" button runs [HrDownPacer] (one light
 * pulse per target beat at a bounded Δ below live HR, recomputed each step so the cue trails the heart
 * down), a minimal live "HR 78 → settling" readout, a stop control, and an honest outcome (settled vs
 * held steady — no fabricated win). Haptic-first → disabled when the encrypted channel isn't up.
 */
@Composable
private fun CalmMode(viewModel: AppViewModel, live: com.noop.ble.LiveState, bpm: Int?) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var startHr by remember { mutableStateOf<Int?>(null) }
    var targetBpm by remember { mutableStateOf<Double?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var didNotFall by remember { mutableStateOf(false) }

    val canBuzz = live.bonded && live.encryptedBond
    val canRun = canBuzz && (bpm?.let { it in 55..120 } ?: false)

    // The metronome coroutine: ask HrDownPacer.next each step, fire a light pulse, schedule the next.
    // Reads the LATEST smoothed HR off the viewModel flow each step (StateFlow.value is always current),
    // so the cue trails the live heart rather than a snapshot — mirrors BiofeedbackController reading
    // model.bpm fresh each tick.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val config = com.noop.analytics.HrDownPacer.Config.DEFAULT
        elapsed = 0
        while (running) {
            val liveHr = viewModel.bpm.value
            val step = com.noop.analytics.HrDownPacer.next((liveHr ?: 0).toDouble(), elapsed.toDouble(), config)
            if (step.stop) {
                outcome = calmOutcomeLine(context, step.stopReason, startHr, liveHr, elapsed)
                didNotFall = calmDidNotFall(step.stopReason, startHr, liveHr)
                running = false
                break
            }
            targetBpm = step.targetBpm
            if (canBuzz) viewModel.buzz(loops = 1)
            val interval = step.intervalMs ?: 1000
            delay(interval.toLong())
            elapsed += interval / 1000
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Explainer.
        NoopCard(tint = Palette.restColor) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Overline(stringResource(R.string.breathe_calm_me))
                    Spacer(Modifier.weight(1f))
                    StatePill(
                        stringResource(
                            if (canRun) R.string.breathe_ready else R.string.breathe_calm_strap_needed,
                        ),
                        tone = if (canRun) StrandTone.Neutral else StrandTone.Warning,
                    )
                }
                Text(
                    stringResource(R.string.breathe_calm_explainer),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
                Text(
                    stringResource(R.string.breathe_calm_disclaimer),
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            }
        }

        if (running) {
            NoopCard(tint = Palette.restColor) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Overline(stringResource(R.string.breathe_calm_settling))
                        Spacer(Modifier.weight(1f))
                        StatePill(stringResource(R.string.breathe_live), tone = StrandTone.Accent, pulsing = true)
                    }
                    Row(verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                        Text(bpm?.toString() ?: "—", style = NoopType.number(48f), color = Palette.metricRose)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            tint = Palette.textTertiary, modifier = Modifier.padding(bottom = 8.dp))
                        Column {
                            Text(
                                stringResource(R.string.breathe_calm_target),
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                            Text(targetBpm?.let { String.format(Locale.US, "%.0f", it) } ?: "—",
                                style = NoopType.number(22f), color = Palette.restBright)
                        }
                    }
                    startHr?.let {
                        Text(stringResource(R.string.breathe_calm_started_at, it),
                            style = NoopType.footnote, color = Palette.textTertiary)
                    }
                    Button(
                        onClick = { running = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.statusCritical, contentColor = Palette.surfaceBase),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.breathe_calm_stop), style = NoopType.headline)
                    }
                }
            }
        } else {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                    Button(
                        onClick = {
                            startHr = bpm; outcome = null; didNotFall = false; targetBpm = null
                            running = true
                        },
                        enabled = canRun,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent, contentColor = Palette.surfaceBase),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.breathe_calm_start), style = NoopType.headline)
                    }
                    when {
                        !canBuzz -> Text(
                            stringResource(R.string.breathe_calm_needs_strap),
                            style = NoopType.footnote, color = Palette.textTertiary)
                        !canRun -> Text(
                            stringResource(R.string.breathe_calm_needs_hr),
                            style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
        }

        val o = outcome
        if (o != null && !running) {
            NoopCard(tint = Palette.restColor) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                        Icon(
                            if (didNotFall) Icons.Filled.Remove else Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (didNotFall) Palette.textTertiary else Palette.statusPositive,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(o, style = NoopType.subhead, color = Palette.textPrimary,
                            modifier = Modifier.weight(1f))
                    }
                    if (didNotFall) {
                        Text(
                            stringResource(R.string.breathe_calm_normal),
                            style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
            }
        }
    }
}

// MARK: - Calm-me outcome helpers (mirror BiofeedbackController.finishCalm)

private fun calmOutcomeLine(
    context: Context,
    reason: com.noop.analytics.HrDownPacer.StopReason?,
    startHr: Int?, endHr: Int?, elapsed: Int,
): String {
    val mmss = String.format(Locale.US, "%d:%02d", elapsed / 60, elapsed % 60)
    return when (reason) {
        com.noop.analytics.HrDownPacer.StopReason.SETTLED ->
            if (startHr != null && endHr != null)
                context.getString(R.string.breathe_calm_settled_range, startHr, endHr, mmss)
            else context.getString(R.string.breathe_calm_settled, mmss)
        else ->
            if (startHr != null && endHr != null && endHr < startHr)
                context.getString(R.string.breathe_calm_eased, startHr, endHr, mmss)
            else if (startHr != null && endHr != null)
                context.getString(R.string.breathe_calm_held, startHr, endHr)
            else context.getString(R.string.breathe_calm_ended)
    }
}

private fun calmDidNotFall(
    reason: com.noop.analytics.HrDownPacer.StopReason?,
    startHr: Int?, endHr: Int?,
): Boolean {
    if (reason == com.noop.analytics.HrDownPacer.StopReason.SETTLED) return false
    if (startHr != null && endHr != null && endHr < startHr) return false
    return true
}

// MARK: - Small shared bits

@Composable
private fun ProgressBar(frac: Float) {
    // The sweep progress on the theme's inset track, so it reads in both schemes. No end marker: with
    // nothing filled it reads as a stray pixel rather than the end of the bar.
    LinearProgressIndicator(
        progress = { frac },
        color = Palette.restBright,
        trackColor = Palette.surfaceInset,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatDay(epochMs: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMs))

// ════════════════════════════════════════════════════════════════════════════
// Audio pacer (opt-in soft phase tones)
// ════════════════════════════════════════════════════════════════════════════

/** SharedPreferences key for the opt-in audio pacer toggle. */
private const val KEY_BREATHE_AUDIO_CUES = "breathe.audioCues"

enum class BreathTone(val frequencyHz: Double) {
    Inhale(440.0),   // A4, brighter for "in"
    Exhale(330.0),   // E4, lower for "out"
}

/**
 * A tiny on-device tone player for the opt-in audio pacer.
 * It synthesises a short, soft sine "ding" per phase (a higher note on the inhale, a lower one on the
 * exhale) into an [AudioTrack].
 *
 * Android has no silent switch, so the honest equivalent is to honour the **ringer mode** — when the
 * phone is on silent or vibrate we simply don't play, keeping the "quiet means quiet" promise. The
 * track is tagged as a sonification assistance cue
 * (not media), so it ducks politely and won't hijack the music stream. Buffers are generated once and
 * reused; [release] frees the track when the screen goes away or the pacer is switched off.
 */
class BreathTonePlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val sampleRate = 44_100
    private val toneSeconds = 0.45
    private val tracks = HashMap<BreathTone, AudioTrack?>()

    /** Play the phase tone, unless the phone is on silent/vibrate (the "honours silent mode" promise). */
    fun play(tone: BreathTone) {
        val am = audioManager ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val track = tracks.getOrPut(tone) { buildTrack(tone) } ?: return
        try {
            // Restart from the top each phase so a fresh tone fires even if the last one is still tailing.
            track.pause()
            track.flush()
            writeTone(track, tone)
            track.play()
        } catch (_: IllegalStateException) {
            // Audio is a nicety, never load-bearing — if the track is in a bad state we just stay silent.
        }
    }

    /** Release the underlying tracks. Idempotent. */
    fun release() {
        tracks.values.forEach { runCatching { it?.release() } }
        tracks.clear()
    }

    private fun buildTrack(tone: BreathTone): AudioTrack? {
        val samples = sampleData(tone)
        val sizeBytes = samples.size * 2  // 16-bit PCM
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(sizeBytes, 1))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            writeTone(track, tone)
            track
        } catch (_: Exception) {
            null
        }
    }

    private fun writeTone(track: AudioTrack, tone: BreathTone) {
        val samples = sampleData(tone)
        track.write(samples, 0, samples.size)
    }

    /**
     * Build a single soft sine tone with a short attack and a longer release envelope, so it fades in and
     * out rather than clicking. Cached per tone so we synthesise it once.
     */
    private val cache = HashMap<BreathTone, ShortArray>()
    private fun sampleData(tone: BreathTone): ShortArray = cache.getOrPut(tone) {
        val total = (toneSeconds * sampleRate).toInt()
        val attack = (0.02 * sampleRate).toInt()
        val release = (0.18 * sampleRate).toInt()
        val peak = 0.28  // kept quiet — a gentle cue, not a beep
        ShortArray(total) { i ->
            val t = i.toDouble() / sampleRate
            val s = sin(2.0 * PI * tone.frequencyHz * t)
            val env = when {
                i < attack -> i.toDouble() / maxOf(attack, 1)
                i > total - release -> (total - i).toDouble() / maxOf(release, 1)
                else -> 1.0
            }
            (s * env * peak * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
