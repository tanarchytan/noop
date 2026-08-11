package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.DailyMetric
import com.noop.data.MoodStore
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

// MARK: - Mind (mood check-in + mood ↔ body correlations)
//
// One 5-face check-in per local day, stored via
// MoodStore under a stable contract (source id "noop-mood", key "mood",
// value 1.0–5.0, overwrite on edit). Once ≥7 days are checked in, up to three Pearson
// correlation lines appear — mood vs HRV / recovery / sleep duration — computed over the
// cached DailyMetric rows through the shared [CorrelationEngine]. Deliberately NEUTRAL palette:
// mood is self-knowledge, not a score, so nothing here is tinted good/bad.

/** One face on the check-in scale. `value` is the stored 1.0–5.0 contract value. */
private data class MoodFace(val emoji: String, val value: Double, @StringRes val word: Int)

private val MOOD_FACES = listOf(
    MoodFace("😞", 1.0, R.string.mind_mood_awful),
    MoodFace("😕", 2.0, R.string.mind_mood_low),
    MoodFace("😐", 3.0, R.string.mind_mood_okay),
    MoodFace("🙂", 4.0, R.string.mind_mood_good),
    MoodFace("😄", 5.0, R.string.mind_mood_great),
)

/** Check-ins needed before the correlation lines unlock. */
private const val MIND_GATE_DAYS = 7

// MARK: - Section

/**
 * Mind — the daily mood check-in card plus the gated mood ↔ body correlations.
 * Hosted on the Insights screen between the journal logging card and the
 * behaviour-effects half.
 */
@Composable
fun MindSection(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val store = remember { MoodStore(vm.repo) }

    var moodSeries by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var moodSeq by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }

    val todayKey = remember { MoodStore.todayKey() }
    LaunchedEffect(moodSeq) {
        moodSeries = store.moodSeries()
        loaded = true
    }
    val todayMood = moodSeries.lastOrNull { it.first == todayKey }?.second

    // One row per day by the storage PK, so size == distinct check-in days.
    val checkInDays = moodSeries.size
    val lines = remember(days, moodSeries) {
        if (moodSeries.size >= MIND_GATE_DAYS) buildMindCorrelations(days, moodSeries)
        else emptyList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(stringResource(R.string.mind_title), overline = stringResource(R.string.mind_overline))

        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                if (!loaded) {
                    Text(
                        stringResource(R.string.mind_loading),
                        style = NoopType.subhead,
                        color = Palette.textTertiary,
                    )
                } else if (todayMood == null || editing) {
                    // --- Open check-in: the 5-face scale -----------------------
                    Text(
                        stringResource(R.string.mind_how_feeling),
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MOOD_FACES.forEach { face ->
                            MoodFaceButton(
                                face = face,
                                selected = todayMood == face.value,
                                onClick = {
                                    editing = false
                                    scope.launch {
                                        store.setMood(todayKey, face.value)
                                        moodSeq++
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.mind_one_per_day),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                } else {
                    // --- Collapsed: today's face + Edit ------------------------
                    val face = MOOD_FACES.minByOrNull { abs(it.value - todayMood) } ?: MOOD_FACES[2]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(face.emoji, style = NoopType.number(26f))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(face.word),
                                style = NoopType.headline, color = Palette.textPrimary,
                            )
                            Text(
                                stringResource(R.string.mind_logged_today),
                                style = NoopType.caption, color = Palette.textTertiary,
                            )
                        }
                        MoodChip(stringResource(R.string.mind_edit)) { editing = true }
                    }
                }

                // --- Mood ↔ body correlations (≥7-day gate) --------------------
                if (loaded) {
                    HorizontalDivider(color = Palette.hairline)
                    if (checkInDays < MIND_GATE_DAYS) {
                        val left = MIND_GATE_DAYS - checkInDays
                        Text(
                            stringResource(
                                R.string.mind_unlock_days,
                                MIND_GATE_DAYS,
                                left,
                                stringResource(if (left == 1) R.string.mind_day else R.string.mind_days),
                            ),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    } else if (lines.isEmpty()) {
                        Text(
                            stringResource(R.string.mind_not_enough_overlap),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    } else {
                        lines.forEachIndexed { idx, line ->
                            MindCorrelationRow(line)
                            if (idx < lines.size - 1) HorizontalDivider(color = Palette.hairline)
                        }
                    }
                }
            }
        }

        Text(stringResource(R.string.mind_footnote), style = NoopType.footnote, color = Palette.textTertiary)
    }
}

// MARK: - Pieces

/** One tappable face. Neutral styling: an inset pill whose border firms up when selected —
 *  no good/bad tinting anywhere on the scale. */
@Composable
private fun MoodFaceButton(face: MoodFace, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val desc = stringResource(R.string.mind_face, stringResource(face.word), face.value.toInt())
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, if (selected) Palette.hairlineStrong else Palette.hairline, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            face.emoji,
            style = NoopType.number(22f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Neutral text chip (the collapsed card's Edit affordance). */
@Composable
private fun MoodChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = NoopType.caption,
        color = Palette.textSecondary,
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** One correlation line: title + r on the first line, a plain-English reading under it.
 *  Neutral colours — r is information here, not a verdict. */
@Composable
private fun MindCorrelationRow(line: MindLine) {
    val dir = stringResource(
        if (line.r > 0) R.string.mind_dir_positive
        else if (line.r < 0) R.string.mind_dir_negative
        else R.string.mind_dir_flat,
    )
    val sentence = stringResource(
        R.string.mind_correlation_sentence,
        stringResource(CorrelationEngine.strengthPhraseRes(line.r)), dir, line.n,
    )
    val title = stringResource(line.title)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "$title: $sentence" },
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = NoopType.subhead,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(Locale.US, "r = %+.2f", line.r),
                style = NoopType.captionNumber,
                color = Palette.textSecondary,
            )
        }
        Text(sentence, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

// MARK: - Correlation math

/** A computed mood ↔ outcome line. */
private data class MindLine(@StringRes val title: Int, val r: Double, val n: Int)

/**
 * Up to three mood ↔ body lines over the cached daily metrics: HRV, recovery and
 * total sleep. A line only appears when Pearson r is computable over the day-aligned
 * pairs (≥3 overlapping days with variance) — no fabricated values.
 */
private fun buildMindCorrelations(
    days: List<DailyMetric>,
    mood: List<Pair<String, Double>>,
): List<MindLine> {
    fun line(@StringRes title: Int, series: List<Pair<String, Double>>): MindLine? {
        val c = CorrelationEngine.pearson(CorrelationEngine.alignByDay(mood, series))
            ?: return null
        return MindLine(title, c.r, c.n)
    }
    return listOfNotNull(
        line(R.string.mind_line_hrv, days.mapNotNull { d -> d.avgHrv?.let { d.day to it } }),
        line(R.string.mind_line_recovery, days.mapNotNull { d -> d.recovery?.let { d.day to it } }),
        line(R.string.mind_line_sleep, days.mapNotNull { d -> d.totalSleepMin?.let { d.day to it } }),
    )
}
