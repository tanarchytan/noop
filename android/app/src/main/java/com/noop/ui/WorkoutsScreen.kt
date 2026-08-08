package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Snowboarding
import androidx.compose.material.icons.filled.SportsBaseball
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.SportsVolleyball
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.RustScores
import com.noop.analytics.WorkoutSport
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Workouts — the activity log, instrument-grade and uniform. Ports the macOS
 * WorkoutsView (Strand/Screens/WorkoutsView.swift) onto the locked Android component
 * system (NoopCard / StatTile / SectionHeader / SegmentedPillControl / SourceBadge)
 * so every card, tile and row lines up:
 *
 * - a range pill (7D / 30D / 90D / 1Y / All) that filters the loaded sessions,
 * - a grid of summary StatTiles (count / time / calories / distance / most-active),
 * - an "Activity Breakdown" of per-sport NoopCards with an identical internal layout,
 * - an "All Sessions" NoopCard of fixed-height rows (date · sport · dur · HR · kcal ·
 * dist · source).
 *
 * Sessions are loaded by the ViewModel from EVERY cached source — strap ("my-whoop": imported +
 * manual), Apple Health / Health Connect, and the on-device DETECTED bouts under "my-whoop-noop" —
 * merged newest first, with dismissed detected bouts filtered out. Each row carries a source
 * badge (Whoop / Apple / HC / Detected / Manual) and an overflow menu to edit, re-label, dismiss or
 * delete. The windowing is anchored to the LATEST session (not "now"), so an old log still resolves;
 * an empty window auto-widens to the next larger range, exactly like the macOS screen.
 */
@Composable
fun WorkoutsScreen(vm: AppViewModel) {
    // The ViewModel owns the loaded rows now (ALL sources incl. detected, dismissed-filtered) so a
    // mutation (add / edit / relabel / dismiss / delete) republishes the list and the screen updates.
    val allRows by vm.workouts.collectAsState()
    // Cached daily metrics — the Charge side of the post-log activity-cost note.
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()
    var loaded by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(WorkoutRange.All) }
    // Pick the default range ONCE on first non-empty load; later mutations must not fight a range the
    // user chose. Mirrors macOS, which sets the default only in `.task` / first onAppear.
    var didPickDefaultRange by remember { mutableStateOf(false) }

    // The manual add/edit dialog target: Some(null) = add, Some(row) = edit, null = closed.
    var dialog by remember { mutableStateOf<DialogTarget?>(null) }

    // filters beyond the time range — sport (null = all), source class (null = all), free-text
    // search over the displayed sport. The pure WorkoutFilter applies them AFTER the window cut.
    var sportFilter by remember { mutableStateOf<String?>(null) }
    var sourceFilter by remember { mutableStateOf<WorkoutSource?>(null) }
    var searchText by remember { mutableStateOf("") }
    val filter = WorkoutFilter(sportFilter, sourceFilter, searchText)

    // multi-select + merge. `selectionMode` toggles the leading checkmarks + the toolbar strip;
    // `selectedKeys` holds the natural keys ("startTs|sport") of the chosen rows. Only MANUAL / DETECTED
    // rows are selectable (imported history is read-only). `mergeSportPrompt` names an all-detected merge.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mergeSportPrompt by remember { mutableStateOf<List<WorkoutRow>?>(null) }

    // displayed-sport names across ALL loaded rows, most-frequent first, for the sport-filter menu.
    // Computed here (a @Composable scope) since the LazyScreenScaffold content lambda is a LazyListScope.
    val availableSports = remember(allRows) {
        allRows.groupingBy { WorkoutEditing.displaySport(it.sport) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    // A transient one-line note shown after a manual save / relabel for a sport that already has a
    // solid/building ActivityCost entry — "Sessions like this usually …". Auto-clears.
    var postLogNote by remember { mutableStateOf<String?>(null) }
    // The sport whose recovery-cost note to surface once the reloaded sessions land. saveManualWorkout
    // / relabelDetected reload `vm.workouts` asynchronously, so we wait for `allRows` to update before
    // computing the note (otherwise it would read the pre-save list). Cleared once consumed.
    var pendingNoteSport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(allRows, recentDays, pendingNoteSport) {
        val sport = pendingNoteSport ?: return@LaunchedEffect
        // Only a solid/building entry (n ≥ minSessions) clears the engine's gate, so this stays silent
        // until there's an honest personal pattern to show.
        val match = computeActivityCosts(allRows, recentDays).firstOrNull { it.sport == sport }
        pendingNoteSport = null
        if (match != null) {
            postLogNote = match.sentence()
            kotlinx.coroutines.delay(7_000)
            postLogNote = null
        }
    }

    LaunchedEffect(Unit) {
        vm.loadWorkouts()
        loaded = true
    }
    LaunchedEffect(allRows) {
        if (!didPickDefaultRange && allRows.isNotEmpty()) {
            range = defaultRange(allRows)
            didPickDefaultRange = true
        }
    }

    // PERF : migrate the eager ScreenScaffold to its lazy twin so each top-level section is its own
    // `item { }` and only the on-screen cards compose + get semantics-walked (the Compose accessibility
    // copy on scroll was a contributor to the OOM). Order / padding / 20dp spacing are unchanged: the
    // hero/summary/breakdown/zones/sessions cards stay one-per-item in the SAME sequence. The per-section
    // `val` resolves run ONCE in the content lambda (captured by each item), so this also de-dupes the
    // range/window/group computation that the eager column re-derived inline. The dialog overlay below the
    // scaffold is untouched. The All-Sessions list still lives inside its single enclosing card (appearance
    // is byte-identical) — see the report note on why it isn't flattened to top-level items here.
    // No topBackground: the scaffold takes its opaque path and paints Palette.surfaceBase, so the canvas
    // follows the theme in both light and dark.
    LazyScreenScaffold(
        title = stringResource(R.string.workouts_title),
        subtitle = stringResource(R.string.workouts_subtitle),
    ) {
        // Start (or stop) a workout right here, not only on Live — mirrors the Live control.
        item {
        WorkoutStartSection(vm)
        }

        if (allRows.isEmpty()) {
            item {
            EmptyWorkouts(loaded, onAdd = { dialog = DialogTarget(null) })
            }
        } else {
            // Resolve the effective range + windowed rows + per-sport groups once. : the pure
            // WorkoutFilter narrows the window AFTER the range cut, so every section reads one filtered set.
            val resolved = effectiveRange(allRows, range, filter)
            val windowRows = filter.apply(sessions(allRows, resolved))
            val groups = sportGroups(windowRows)
            val fellBack = resolved != range

            item {
            RangeBar(
                range = range,
                effectiveRange = resolved,
                rowCount = windowRows.size,
                fellBack = fellBack,
                filterActive = filter.isActive,
                onSelect = { range = it },
                onAdd = { dialog = DialogTarget(null) },
            )
            }
            item {
            FilterBar(
                filter = filter,
                availableSports = availableSports,
                onSport = { sportFilter = it },
                onSource = { sourceFilter = it },
                onSearch = { searchText = it },
                onClear = { sportFilter = null; sourceFilter = null; searchText = "" },
            )
            }
            postLogNote?.let { item { PostLogNoteBanner(it) } }
            item { EffortHero(rows = windowRows, effectiveRange = resolved, groups = groups) }
            item { SummarySection(rows = windowRows, effectiveRange = resolved, groups = groups) }
            item { BreakdownSection(groups = groups, rows = windowRows) }
            item { ZonesSection(windowRows) }
            item {
            SessionsSection(
                vm = vm,
                rows = windowRows,
                selectionMode = selectionMode,
                selectedKeys = selectedKeys,
                onToggleSelectMode = {
                    selectionMode = !selectionMode
                    if (!selectionMode) selectedKeys = emptySet()
                },
                onToggleRow = { row ->
                    val key = sessionSelectionKey(row)
                    selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                },
                onMerge = { chosen ->
                    if (WorkoutMerge.resolvedSport(chosen) == null) {
                        mergeSportPrompt = chosen
                    } else {
                        vm.mergeWorkouts(chosen)
                        selectionMode = false; selectedKeys = emptySet()
                    }
                },
                onBulkDelete = { chosen ->
                    vm.bulkDeleteWorkouts(chosen)
                    selectionMode = false; selectedKeys = emptySet()
                },
                onCancelSelect = { selectionMode = false; selectedKeys = emptySet() },
                onEdit = { dialog = DialogTarget(it) },
                onRelabel = { row, sport ->
                    vm.relabelDetected(row, sport)
                    pendingNoteSport = WorkoutEditing.displaySport(sport)
                },
                onDismiss = { vm.dismissDetected(it) },
                onDelete = { vm.deleteWorkout(it) },
            )
            }
        }
    }

    // name an all-detected merge (no sport to inherit) before committing it.
    mergeSportPrompt?.let { chosen ->
        MergeSportDialog(
            onDismiss = { mergeSportPrompt = null },
            onPick = { sport ->
                vm.mergeWorkouts(chosen, sport)
                mergeSportPrompt = null
                selectionMode = false; selectedKeys = emptySet()
            },
        )
    }

    dialog?.let { target ->
        ManualWorkoutDialog(
            editing = target.editing,
            onDismiss = { dialog = null },
            onSave = { row, replacing ->
                vm.saveManualWorkout(row, replacing)
                pendingNoteSport = WorkoutEditing.displaySport(row.sport)
                dialog = null
            },
        )
    }
}

/** Drives the manual add/edit dialog. [editing] null = add a new workout, non-null = edit it. */
private data class DialogTarget(val editing: WorkoutRow?)

// MARK: - Empty / loading state

@Composable
private fun EmptyWorkouts(loaded: Boolean, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        DataPendingNote(
            title = stringResource(R.string.workouts_empty_title),
            body = stringResource(R.string.workouts_empty_body),
        )
        if (loaded) AddWorkoutButton(onAdd)
    }
}

/**
 * The transient "personal pattern" caption shown after a manual save / relabel — an
 * Effort-tinted frosted strip with a chart glyph and the engine's "Sessions like this usually …"
 * sentence. Mirrors the macOS WorkoutsView.postLogBanner. Auto-dismisses (the caller clears it).
 */
@Composable
private fun PostLogNoteBanner(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.effortColor.copy(alpha = 0.10f))
            .border(Metrics.divider, Palette.effortColor.copy(alpha = 0.22f), shape)
            .padding(Metrics.space12)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ShowChart,
            contentDescription = null,
            tint = Palette.effortColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Metrics.space10))
        Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

/** The "Add workout" pill — opens the manual add dialog. Shown on both the populated screen
 * (in the range bar) and the empty state, so a user with no imports can still log a session. */
@Composable
private fun AddWorkoutButton(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.accentMuted)
            .clickable(onClick = onAdd)
            .padding(horizontal = Metrics.space14, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Metrics.space6))
        Text(stringResource(R.string.workouts_add), style = NoopType.subhead, color = Palette.accent)
    }
}

// MARK: - Range control

@Composable
private fun RangeBar(
    range: WorkoutRange,
    effectiveRange: WorkoutRange,
    rowCount: Int,
    fellBack: Boolean,
    filterActive: Boolean,
    onSelect: (WorkoutRange) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        // Phone width can't fit the labelled Add button beside the 5-segment range pill without
        // crushing/clipping one — stack them (button, then pill), matching the iPhone fix.
        AddWorkoutButton(onAdd)
        SegmentedPillControl(
            items = WorkoutRange.entries,
            selection = range,
            label = { context.getString(it.label) },
            onSelect = onSelect,
        )
        val unit = stringResource(if (rowCount == 1) R.string.workouts_session else R.string.workouts_sessions)
        val window = stringResource(effectiveRange.caption)
        val base = if (fellBack) {
            stringResource(R.string.workouts_range_caption_widened, rowCount, unit, window)
        } else {
            stringResource(R.string.workouts_range_caption, rowCount, unit, window)
        }
        // append "· filtered" when a sport/source/search filter narrows the list.
        val caption = if (filterActive) stringResource(R.string.workouts_range_caption_filtered, base) else base
        Text(
            caption,
            style = NoopType.footnote,
            color = if (fellBack) Palette.statusWarning else Palette.textTertiary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MARK: - Filters

/** The origin classes offered in the Source filter, in a stable menu order (matches the row badges). */
private val SOURCE_FILTER_OPTIONS = listOf(
    WorkoutSource.WHOOP, WorkoutSource.APPLE, WorkoutSource.DETECTED,
    WorkoutSource.MANUAL, WorkoutSource.LIFTING, WorkoutSource.ACTIVITY_FILE,
)

/** The Source-filter menu label for an origin class. */
@StringRes
private fun sourceFilterLabel(c: WorkoutSource): Int = when (c) {
    WorkoutSource.WHOOP -> R.string.workouts_src_filter_whoop
    WorkoutSource.APPLE -> R.string.workouts_src_filter_apple
    WorkoutSource.DETECTED -> R.string.workouts_src_filter_detected
    WorkoutSource.MANUAL -> R.string.workouts_src_filter_manual
    WorkoutSource.LIFTING -> R.string.workouts_src_filter_lifting
    WorkoutSource.ACTIVITY_FILE -> R.string.workouts_src_filter_file
}

/**
 * : filter controls beside the range pill — a Sport menu, a Source menu, and a search field, with a
 * "×" clear chip that appears only when a filter is active. Mirrors the iOS WorkoutsView.filterBar; the
 * predicate is the pure [WorkoutFilter], these controls only drive its state.
 */
@Composable
private fun FilterBar(
    filter: WorkoutFilter,
    availableSports: List<String>,
    onSport: (String?) -> Unit,
    onSource: (WorkoutSource?) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterPillMenu(
                title = filter.sport ?: stringResource(R.string.workouts_all_sports),
                active = filter.sport != null,
                contentDescription = stringResource(R.string.workouts_filter_by_sport),
            ) { dismiss ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.workouts_all_sports),
                            style = NoopType.body, color = Palette.textPrimary,
                        )
                    },
                    onClick = { onSport(null); dismiss() },
                )
                availableSports.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s, style = NoopType.body, color = Palette.textPrimary) },
                        onClick = { onSport(s); dismiss() },
                    )
                }
            }
            FilterPillMenu(
                title = filter.sourceClass?.let { stringResource(sourceFilterLabel(it)) }
                    ?: stringResource(R.string.workouts_all_sources),
                active = filter.sourceClass != null,
                contentDescription = stringResource(R.string.workouts_filter_by_source),
            ) { dismiss ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.workouts_all_sources),
                            style = NoopType.body, color = Palette.textPrimary,
                        )
                    },
                    onClick = { onSource(null); dismiss() },
                )
                SOURCE_FILTER_OPTIONS.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(sourceFilterLabel(opt)),
                                style = NoopType.body, color = Palette.textPrimary,
                            )
                        },
                        onClick = { onSource(opt); dismiss() },
                    )
                }
            }
            if (filter.isActive) {
                val clearLabel = stringResource(R.string.workouts_clear_filters)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClear)
                        .padding(horizontal = Metrics.space8, vertical = 6.dp)
                        .semantics { contentDescription = clearLabel },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Metrics.space4))
                    Text(stringResource(R.string.workouts_clear), style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
        }
        OutlinedTextField(
            value = filter.search,
            onValueChange = onSearch,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.size(Metrics.iconSmall)) },
            trailingIcon = {
                if (filter.search.isNotEmpty()) {
                    IconButton(onClick = { onSearch("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.workouts_clear_search),
                            tint = Palette.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            },
            placeholder = {
                Text(
                    stringResource(R.string.workouts_search_sport),
                    style = NoopType.body, color = Palette.textTertiary,
                )
            },
            singleLine = true,
            colors = workoutFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A pill-styled dropdown filter menu: the current selection as its label, Effort-tinted when active. */
@Composable
private fun FilterPillMenu(
    title: String,
    active: Boolean,
    contentDescription: String,
    items: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.workouts_filter_menu_cd, contentDescription, title)
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (active) Palette.effortColor.copy(alpha = 0.14f) else Palette.surfaceInset.copy(alpha = 0.6f))
                .clickable { open = true }
                .padding(horizontal = Metrics.space10, vertical = 6.dp)
                .semantics { this.contentDescription = menuLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = NoopType.footnote,
                color = if (active) Palette.effortColor else Palette.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.width(Metrics.space4))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (active) Palette.effortColor else Palette.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items { open = false }
        }
    }
}

/** name an all-detected merge (no sport to inherit) via the shared sport picker before committing. */
@Composable
private fun MergeSportDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var sport by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Text(
                stringResource(R.string.workouts_merge_title),
                style = NoopType.title2, color = Palette.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(
                    stringResource(R.string.workouts_merge_body),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                SportPickerField(sport, onChange = { sport = it })
            }
        },
        confirmButton = {
            val context = LocalContext.current
            TextButton(onClick = {
                if (sport.isNotBlank()) {
                    // naming a merge is a real selection too — parity with the macOS/iOS sheet,
                    // whose reused StartWorkoutSheet records on its action button.
                    RecentSportsPrefs.record(context, sport.trim())
                    onPick(sport.trim())
                }
            }, enabled = sport.isNotBlank()) {
                Text(
                    stringResource(R.string.workouts_merge),
                    style = NoopType.body,
                    color = if (sport.isNotBlank()) Palette.accent else Palette.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), style = NoopType.body, color = Palette.textSecondary)
            }
        },
    )
}

/** the selection key for a row (its natural key), stable across a reload so checkmarks persist. */
private fun sessionSelectionKey(row: WorkoutRow): String = "${row.startTs}|${row.sport}"

// MARK: - Effort hero (typical-effort gauge)
//
// The typical session Effort on the shared Effort gauge. The fill reads the AVERAGE per-session strain
// over the user's display scale, so it is identical whether that scale is Effort 0–100 or WHOOP 0–21;
// the number is the same average shown on that scale via UnitFormatter.

/** The Effort hero gauge. */
private val EFFORT_HERO_DIAMETER: Dp = 140.dp

@Composable
private fun EffortHero(
    rows: List<WorkoutRow>,
    effectiveRange: WorkoutRange,
    groups: List<SportGroup>,
) {
    val effortScale = UnitPrefs.effortScale(LocalContext.current)
    val strains = rows.mapNotNull { it.strain }
    val hasEffort = strains.isNotEmpty()
    val avgStrain = if (strains.isEmpty()) 0.0 else strains.sum() / strains.size
    val shownEffort = UnitFormatter.effortValue(avgStrain, effortScale)
    // The denominator of the user's display scale, read off the one formatter that owns the conversion.
    val effortOutOf = UnitFormatter.effortScaleMax(effortScale).toDouble()
    val totalTimeH = rows.mapNotNull { it.durationS }.sum() / 3600.0
    val modal = groups.firstOrNull()

    NoopCard(padding = Metrics.screenRowSpacing, tint = Palette.effortColor) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space18),
            ) {
                Overline(stringResource(R.string.workouts_typical_effort), color = Palette.effortColor)
                // An empty window draws the empty track and no number, never a zero.
                StrainGauge(
                    strain = shownEffort,
                    outOf = effortOutOf,
                    valueText = oneDecimal(shownEffort),
                    diameter = EFFORT_HERO_DIAMETER,
                    showsLabel = hasEffort,
                )
            }
            Spacer(Modifier.width(Metrics.screenRowSpacing))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                Text(
                    stringResource(R.string.workouts_effort_this, stringResource(effectiveRange.heroWord)),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
                // Each stat keeps its own width and drops to the next line when both do not fit: split
                // evenly beside the gauge, "SESSIONS" was wider than its half and broke after "SESSION".
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space18),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space6),
                ) {
                    HeroStat(stringResource(R.string.workouts_stat_sessions), "${rows.size}", Palette.effortColor)
                    HeroStat(stringResource(R.string.workouts_stat_active), oneDecimal(totalTimeH) + "h", Palette.textPrimary)
                }
                val window = stringResource(effectiveRange.caption)
                Text(
                    if (modal != null) {
                        stringResource(R.string.workouts_mostly, WorkoutEditing.displaySport(modal.sport), window)
                    } else {
                        stringResource(R.string.workouts_logged_across, window)
                    },
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun HeroStat(title: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Overline(title)
        Text(value, style = NoopType.number(20f), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Summary tiles (uniform StatTiles)

@Composable
private fun SummarySection(
    rows: List<WorkoutRow>,
    effectiveRange: WorkoutRange,
    groups: List<SportGroup>,
) {
    // Imperial/Metric display preference (D). Distances are stored in metres; the toggle re-labels
    // them. Read here so a change recomposes the tiles. Display-only — nothing stored changes.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    val totalCount = rows.size
    val totalTimeH = rows.mapNotNull { it.durationS }.sum() / 3600.0
    val totalKcal = rows.mapNotNull { it.energyKcal }.sum()
    val totalKm = rows.mapNotNull { it.distanceM }.sum() / 1000.0
    val modal = groups.firstOrNull()
    val window = stringResource(effectiveRange.caption)
    val modalCaption = modal?.let {
        if (it.count == 1) stringResource(R.string.workouts_n_session, it.count)
        else stringResource(R.string.workouts_n_sessions, it.count)
    }

    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            StatTile(
                modifier = m,
                label = stringResource(R.string.workouts_total_workouts),
                value = "$totalCount",
                caption = window,
                accent = Palette.effortColor,
            )
        },
        { m ->
            StatTile(
                modifier = m,
                label = stringResource(R.string.workouts_total_time),
                value = oneDecimal(totalTimeH) + "h",
                caption = stringResource(R.string.workouts_caption_active),
                accent = Palette.textPrimary,
            )
        },
        { m ->
            StatTile(
                modifier = m,
                label = stringResource(R.string.workouts_total_calories),
                value = grouped(totalKcal),
                caption = "kcal",
                accent = Palette.metricAmber,
            )
        },
        { m ->
            StatTile(
                modifier = m,
                label = stringResource(R.string.workouts_total_distance),
                value = UnitFormatter.distanceFromKilometers(totalKm, unitSystem),
                caption = stringResource(R.string.workouts_caption_covered),
                accent = Palette.metricCyan,
            )
        },
        { m ->
            StatTile(
                modifier = m,
                label = stringResource(R.string.workouts_most_active),
                value = modal?.sport ?: EM_DASH,
                caption = modalCaption,
                accent = Palette.textPrimary,
            )
        },
    )

    // Two-column grid so tile heights stay uniform on phone widths.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                rowTiles.forEach { tile -> tile(Modifier.weight(1f)) }
                if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Activity breakdown (per-sport NoopCards, identical layout)

@Composable
private fun BreakdownSection(groups: List<SportGroup>, rows: List<WorkoutRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            title = stringResource(R.string.workouts_activity_breakdown),
            overline = stringResource(R.string.workouts_by_sport),
            trailing = if (groups.size == 1) stringResource(R.string.workouts_n_sport, groups.size)
            else stringResource(R.string.workouts_n_sports, groups.size),
        )
        // This sport's own sessions, so each card can carry an HR-zone mini-bar.
        groups.forEach { g -> SportCard(g, zones = zoneSummary(rows.filter { it.sport == g.sport })) }
    }
}

@Composable
private fun SportCard(g: SportGroup, zones: ZoneSummary?) {
    // Frosted Effort-tinted card with the sport glyph in the Effort world, plus an HR-zone mini-bar
    // when the sessions carry imported zones.
    NoopCard(tint = Palette.effortColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            // Identical header for every card.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sportIcon(g.sport),
                    contentDescription = null,
                    tint = Palette.effortColor,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Spacer(Modifier.width(Metrics.space10))
                Text(
                    WorkoutEditing.displaySport(g.sport),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${g.count}", style = NoopType.number(15f), color = Palette.effortBright)
            }
            if (zones != null) {
                SegmentBar(
                    segments = zones.minutes.mapIndexed { i, m ->
                        Palette.hrZoneColor(i + 1) to (m / zones.totalMinutes).toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 8.dp,
                )
            }
            CardDivider()
            // Identical 4-up stat strip for every card.
            Row(modifier = Modifier.fillMaxWidth()) {
                MiniStat(stringResource(R.string.workouts_stat_sessions), "${g.count}", Modifier.weight(1f))
                MiniStat(stringResource(R.string.workouts_time), oneDecimal(g.totalTimeH) + "h", Modifier.weight(1f))
                MiniStat("Kcal", grouped(g.totalKcal), Modifier.weight(1f), tint = Palette.metricAmber)
                MiniStat(
                    stringResource(R.string.workouts_stat_avg_per_session),
                    "${g.avgTimePerSessionMin.roundToInt()}m",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, tint: Color = Palette.textPrimary) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Overline(label)
        Text(
            value,
            style = NoopType.number(15f),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - HR zones (imported per-workout zone split, one card)

@Composable
private fun ZonesSection(rows: List<WorkoutRow>) {
    val z = remember(rows) { zoneSummary(rows) } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            title = stringResource(R.string.workouts_hr_zones),
            overline = stringResource(R.string.workouts_whoop_import),
            trailing = if (rows.size == 1) {
                stringResource(R.string.workouts_zones_coverage_one, z.sessionsWithZones, rows.size)
            } else {
                stringResource(R.string.workouts_zones_coverage, z.sessionsWithZones, rows.size)
            },
        )
        NoopCard(tint = Palette.effortColor) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                // Proportional stacked bar — the Hypnogram geometry with zone colors.
                SegmentBar(
                    segments = z.minutes.mapIndexed { i, m ->
                        Palette.hrZoneColor(i + 1) to (m / z.totalMinutes).toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 24.dp,
                )
                CardDivider()
                // 5-up stat strip, identical rhythm to the sport cards' MiniStat row.
                val shares = remember(z) { zonePercents(z.minutes) }
                Row(modifier = Modifier.fillMaxWidth()) {
                    z.minutes.forEachIndexed { i, m ->
                        ZoneStat(i + 1, m, shares?.getOrNull(i), Modifier.weight(1f))
                    }
                }
                Text(
                    stringResource(R.string.workouts_zones_note),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/**
 * The five zones as whole percentages summing to exactly 100, so the strip never reads 99 or 101.
 * whoop-rs apportions them; null when no zone carries time, so no zone prints a share it never had.
 */
internal fun zonePercents(minutes: List<Double>): List<Int>? = RustScores.wholePercentages(minutes)

/** What a zone reads as on a session with nothing to split. */
private const val NO_ZONE_SHARE = "--"

@Composable
private fun ZoneStat(zone: Int, minutes: Double, pct: Int?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(Palette.hrZoneColor(zone), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(5.dp))
            Overline(stringResource(R.string.workouts_zone_label, zone))
        }
        Text(
            if (pct == null) NO_ZONE_SHARE else "$pct%",
            style = NoopType.number(15f),
            color = Palette.textPrimary,
            maxLines = 1,
        )
        Text(durationLabel(minutes * 60), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
    }
}

// MARK: - All sessions (one NoopCard, uniform fixed-height rows)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSection(
    vm: AppViewModel,
    rows: List<WorkoutRow>,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    onToggleSelectMode: () -> Unit,
    onToggleRow: (WorkoutRow) -> Unit,
    onMerge: (List<WorkoutRow>) -> Unit,
    onBulkDelete: (List<WorkoutRow>) -> Unit,
    onCancelSelect: () -> Unit,
    onEdit: (WorkoutRow) -> Unit,
    onRelabel: (WorkoutRow, String) -> Unit,
    onDismiss: (WorkoutRow) -> Unit,
    onDelete: (WorkoutRow) -> Unit,
) {
    var selectedRow by remember { mutableStateOf<WorkoutRow?>(null) }

    // paginate the All-Sessions list. This card lives inside ONE LazyColumn item, so every session
    // row composes eagerly: a years-deep WHOOP/Apple import (hundreds to thousands of bouts) built the
    // whole table in one pass, a real jank/OOM contributor. Render a bounded page and grow it on demand,
    // so a heavy history opens fast and the user pages in the rest. Reset when the windowed range changes
    // (the row set changes identity), so switching range never leaves a stale "shown" count.
    var shownCount by remember(rows) { mutableStateOf(SESSIONS_PAGE_SIZE) }
    val visible = if (rows.size <= shownCount) rows else rows.take(shownCount)
    val remaining = rows.size - visible.size

    // only MANUAL / DETECTED rows are selectable — a pure-imported list has nothing to merge/delete.
    val anySelectable = rows.any { WorkoutMerge.isMergeable(it) }
    val chosen = rows.filter { sessionSelectionKey(it) in selectedKeys }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader(
                    title = stringResource(R.string.workouts_all_sessions),
                    overline = stringResource(R.string.workouts_log),
                    trailing = stringResource(R.string.workouts_n_total, rows.size),
                )
            }
            if (anySelectable) SelectPill(selectionMode, onToggleSelectMode)
        }
        if (selectionMode) SelectionToolbar(chosen, onMerge, onBulkDelete, onCancelSelect)
        NoopCard(padding = 0.dp) {
            Column {
                SessionHeaderRow(selectionMode)
                FullDivider()
                visible.forEachIndexed { idx, row ->
                    SessionRow(
                        row = row,
                        background = if (idx % 2 == 1) Palette.surfaceInset.copy(alpha = 0.4f) else Color.Transparent,
                        selectionMode = selectionMode,
                        selected = sessionSelectionKey(row) in selectedKeys,
                        onToggleRow = onToggleRow,
                        onEdit = onEdit,
                        onRelabel = onRelabel,
                        onDismiss = onDismiss,
                        onDelete = onDelete,
                        onClick = { selectedRow = it },
                    )
                    if (idx != visible.lastIndex) FullDivider(alpha = 0.5f)
                }
                // "Show more" pages in the next [SESSIONS_PAGE_SIZE] bouts. Hidden once everything is shown.
                if (remaining > 0) {
                    FullDivider(alpha = 0.5f)
                    val more = minOf(remaining, SESSIONS_PAGE_SIZE)
                    val moreLabel = stringResource(R.string.workouts_show_more_cd, more)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { shownCount += SESSIONS_PAGE_SIZE }
                            .semantics { contentDescription = moreLabel }
                            .padding(vertical = Metrics.space14),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.workouts_show_more, more, remaining),
                            style = NoopType.subhead,
                            color = Palette.accent,
                        )
                    }
                }
            }
        }
    }

    selectedRow?.let { row ->
        WorkoutDetailSheet(vm = vm, row = row, onDismiss = { selectedRow = null })
    }
}

/** the "Select" pill in the All-Sessions header — toggles multi-select mode. */
@Composable
private fun SelectPill(selectionMode: Boolean, onToggle: () -> Unit) {
    val pillLabel = stringResource(
        if (selectionMode) R.string.workouts_finish_selecting else R.string.workouts_select_cd,
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selectionMode) Palette.effortColor.copy(alpha = 0.14f) else Palette.surfaceInset.copy(alpha = 0.6f))
            .clickable(onClick = onToggle)
            .padding(horizontal = Metrics.space12, vertical = 6.dp)
            .semantics { contentDescription = pillLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (selectionMode) R.string.workouts_done else R.string.workouts_select),
            style = NoopType.footnote,
            color = if (selectionMode) Palette.effortColor else Palette.accent,
        )
    }
}

/** the Merge / Delete / Cancel strip shown above the card in selection mode. Merge needs 2+ eligible
 * rows; Delete needs 1+. Imported rows are never selectable, so the chosen set is always mergeable. */
@Composable
private fun SelectionToolbar(
    chosen: List<WorkoutRow>,
    onMerge: (List<WorkoutRow>) -> Unit,
    onBulkDelete: (List<WorkoutRow>) -> Unit,
    onCancel: () -> Unit,
) {
    val canMerge = WorkoutMerge.canMerge(chosen)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .background(Palette.effortColor.copy(alpha = 0.08f))
            .padding(horizontal = Metrics.space12, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        ToolbarAction(
            stringResource(R.string.workouts_merge_n, chosen.size), Icons.AutoMirrored.Filled.MergeType,
            tint = if (canMerge) Palette.effortColor else Palette.textTertiary,
            enabled = canMerge, onClick = { onMerge(chosen) },
        )
        ToolbarAction(
            stringResource(R.string.workouts_delete_n, chosen.size), Icons.Filled.Delete,
            tint = if (chosen.isEmpty()) Palette.textTertiary else Palette.metricRose,
            enabled = chosen.isNotEmpty(), onClick = { onBulkDelete(chosen) },
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.common_cancel),
            style = NoopType.subhead,
            color = Palette.textSecondary,
            modifier = Modifier.clickable(onClick = onCancel).padding(Metrics.space4),
        )
    }
}

@Composable
private fun ToolbarAction(label: String, icon: ImageVector, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Metrics.space4, vertical = 4.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
        Spacer(Modifier.width(Metrics.space6))
        Text(label, style = NoopType.subhead, color = tint)
    }
}

/** the All-Sessions list renders in pages of this size and grows on "Show more", so a years-deep
 * workout history doesn't compose every row in one pass inside the single enclosing card. */
private const val SESSIONS_PAGE_SIZE = 50

@Composable
private fun SessionHeaderRow(selectionMode: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = Metrics.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // a leading spacer over the per-row selection glyph, so the columns stay aligned in select mode.
        if (selectionMode) Spacer(Modifier.width(30.dp))
        // Weights mirror SessionRow. Six columns left the sport at one syllable and the source badge
        // at one letter, so the source moved under the sport it belongs to and its column went to it.
        ColHeader(stringResource(R.string.workouts_col_date), Modifier.weight(1.9f), TextAlign.Start)
        ColHeader(stringResource(R.string.workouts_sport), Modifier.weight(2.3f), TextAlign.Start)
        ColHeader(stringResource(R.string.workouts_col_dur), Modifier.weight(1f), TextAlign.End)
        ColHeader(stringResource(R.string.workouts_col_hr), Modifier.weight(1f), TextAlign.End)
        ColHeader("Kcal", Modifier.weight(1f), TextAlign.End)
        // Trailing spacer column over the per-row overflow menu, so headers line up with the cells.
        Spacer(Modifier.width(32.dp))
    }
}

@Composable
private fun ColHeader(text: String, modifier: Modifier, align: TextAlign) {
    // Built from the overline style directly (not the Overline composable) so the
    // numeric columns can right-align their headers over the right-aligned cells.
    Text(
        text = text.uppercase(),
        style = NoopType.overline,
        color = Palette.textSecondary,
        textAlign = align,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun SessionRow(
    row: WorkoutRow,
    background: Color,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleRow: (WorkoutRow) -> Unit,
    onEdit: (WorkoutRow) -> Unit,
    onRelabel: (WorkoutRow, String) -> Unit,
    onDismiss: (WorkoutRow) -> Unit,
    onDelete: (WorkoutRow) -> Unit,
    onClick: (WorkoutRow) -> Unit,
) {
    // only MANUAL / DETECTED rows are selectable — imported history is read-only.
    val selectable = WorkoutMerge.isMergeable(row)
    val rowBase = stringResource(
        R.string.workouts_row_cd,
        WorkoutEditing.displaySport(row.sport),
        dateLabel(row.startTs),
    )
    val rowLabel = if (!selectionMode) rowBase else stringResource(
        when {
            !selectable -> R.string.workouts_row_cd_imported
            selected -> R.string.workouts_row_cd_selected
            else -> R.string.workouts_row_cd_unselected
        },
        rowBase,
    )
    // The whole row settles inward on press: the SAME interactionSource drives the clickable and the
    // press. The edit/delete overflow menu and the selection glyph stay their own hit targets on top.
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidPress(interaction)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                if (selectionMode) { if (selectable) onToggleRow(row) } else onClick(row)
            }
            .height(56.dp)
            .padding(start = Metrics.cardPadding)
            .semantics { contentDescription = rowLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // leading selection glyph — filled/hollow check for a mergeable row, or a lock for imported.
        if (selectionMode) {
            if (selectable) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) Palette.effortColor else Palette.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Palette.textTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(Metrics.space8))
        }
        // Date over the session's own clock span.
        Column(modifier = Modifier.weight(1.9f)) {
            Text(dateLabel(row.startTs), style = NoopType.subhead, color = Palette.textPrimary, maxLines = 1)
            Text(timeRangeLabel(row.startTs, row.endTs), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        }
        // Sport over where the session came from, so both read in full on one column's width.
        Column(modifier = Modifier.weight(2.3f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sportIcon(row.sport),
                    contentDescription = null,
                    tint = Palette.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    WorkoutEditing.displaySport(row.sport),
                    style = NoopType.subhead,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val (srcLabel, srcTint) = row.sourceBadge
            SourceBadge(stringResource(srcLabel), tint = srcTint)
        }
        Cell(durationLabel(row.durationS), Modifier.weight(1f))
        Cell(
            row.avgHr?.toString() ?: EM_DASH,
            Modifier.weight(1f),
            color = if (row.avgHr != null) Palette.metricRose else null,
        )
        Cell(
            row.energyKcal?.let { grouped(it) } ?: EM_DASH,
            Modifier.weight(1f),
            color = if (row.energyKcal != null) Palette.metricAmber else null,
        )
        // hide the per-row ••• menu in selection mode (the toolbar owns the actions there); keep a
        // 32dp spacer so the Src column stays aligned with the header.
        if (selectionMode) Spacer(Modifier.width(32.dp)) else RowActionsMenu(row, onEdit, onRelabel, onDismiss, onDelete)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutDetailSheet(vm: AppViewModel, row: WorkoutRow, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Per-window reads : the HR curve (downsampled bucket means) and the HR-zone split. Zones
    // prefer the imported per-workout percentages (a WHOOP-computed split); only when the row carries
    // none do we derive zone-minutes from the strap's own raw HR — so we never overwrite a real
    // imported split with an on-device approximation.
    var hrCurve by remember(row.startTs) { mutableStateOf<List<Double>>(emptyList()) }
    var zoneMinutes by remember(row.startTs) { mutableStateOf<List<Double>?>(null) }
    var zonesFromImport by remember(row.startTs) { mutableStateOf(false) }
    var heartRateRecovery by remember(row.startTs) { mutableStateOf<uniffi.whoop_ffi.HrRecoveryInfo?>(null) }
    // Steps for an on-foot sport : the strap's own counter over the window, computed at display time
    // so it "fills in after sync". null for non-foot sports or when no strap counter covers the window.
    var steps by remember(row.startTs) { mutableStateOf<Int?>(null) }
    LaunchedEffect(row.startTs, row.endTs) {
        hrCurve = vm.workoutHrBuckets(row.startTs, row.endTs).map { it.avgBpm }
        steps = if (WorkoutSport.isOnFoot(row.sport)) vm.workoutSteps(row.startTs, row.endTs) else null
        val imported = parseZonePercents(row.zonesJSON)
        if (imported != null) {
            val durMin = (row.durationS ?: (row.endTs - row.startTs).toDouble()) / 60.0
            if (durMin > 0.0) {
                zoneMinutes = imported.map { durMin * it / 100.0 }
                zonesFromImport = true
            }
        }
        if (zoneMinutes == null) {
            zoneMinutes = vm.workoutZoneMinutes(row.startTs, row.endTs)
            zonesFromImport = false
        }
        heartRateRecovery = vm.workoutHeartRateRecovery(row.startTs, row.endTs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.surfaceOverlay,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Metrics.screenRowSpacing)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.space14),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sportIcon(row.sport),
                    contentDescription = null,
                    tint = Palette.effortColor,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Metrics.space10))
                Column(modifier = Modifier.weight(1f)) {
                    Text(WorkoutEditing.displaySport(row.sport), style = NoopType.title2, color = Palette.textPrimary)
                    Text(dateLabel(row.startTs), style = NoopType.footnote, color = Palette.textTertiary)
                }
                val (srcLabel, srcTint) = row.sourceBadge
                SourceBadge(stringResource(srcLabel), tint = srcTint)
            }
            CardDivider()
            DetailRow(stringResource(R.string.workouts_time), timeRangeLabel(row.startTs, row.endTs))
            DetailRow(stringResource(R.string.workouts_detail_duration), durationLabel(row.durationS))
            if (row.avgHr != null) DetailRow(stringResource(R.string.workouts_detail_avg_hr), "${row.avgHr} bpm")
            if (row.maxHr != null) DetailRow(stringResource(R.string.workouts_detail_max_hr), "${row.maxHr} bpm")
            if (row.energyKcal != null) {
                DetailRow(stringResource(R.string.workouts_detail_calories), "${grouped(row.energyKcal)} kcal")
            }
            if (row.distanceM != null) {
                val unitSystem = UnitPrefs.system(LocalContext.current)
                DetailRow(
                    stringResource(R.string.workouts_detail_distance),
                    UnitFormatter.distanceFromKilometers(row.distanceM / 1000.0, unitSystem),
                )
            }
            steps?.let { // on-foot sports
                DetailRow(
                    stringResource(R.string.workouts_detail_steps),
                    stringResource(R.string.workouts_n_steps, grouped(it.toDouble())),
                )
            }
            if (!row.notes.isNullOrBlank()) DetailRow(stringResource(R.string.workouts_detail_notes), row.notes)

            // - per-session Effort contribution. The session's captured strain re-homed from a plain
            // value row into a prominent Effort-amber card (the big count-up value + the "This session"
            // overline + an explainer), mirroring the iOS WorkoutDetailView.effortCard. Gated on a captured
            // strain - an imported session with none simply omits the card. The display honours the Effort
            // scale toggle , so a WHOOP-axis user sees the rescaled 0–21 value; the stored value is
            // unchanged. Presentation only - no new data is computed here.
            row.strain?.let { strain ->
                val effortScale = UnitPrefs.effortScale(LocalContext.current)
                CardDivider()
                SessionEffortCard(strain = strain, effortScale = effortScale)
            }

            // HR curve over the session window. A faint baseline shows under 2 points.
            if (hrCurve.size > 1) {
                CardDivider()
                Overline(stringResource(R.string.workouts_heart_rate))
                LineChart(
                    values = hrCurve,
                    modifier = Modifier.height(Metrics.compactChartHeight),
                    color = Palette.effortColor,
                    fill = true,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    val lo = hrCurve.minOrNull()?.roundToInt() ?: 0
                    val hi = hrCurve.maxOrNull()?.roundToInt() ?: 0
                    MiniStat(
                        stringResource(R.string.workouts_stat_avg),
                        row.avgHr?.let { "$it bpm" } ?: EM_DASH,
                        Modifier.weight(1f),
                    )
                    MiniStat(
                        stringResource(R.string.workouts_stat_peak),
                        (row.maxHr ?: hi).let { "$it bpm" },
                        Modifier.weight(1f),
                    )
                    MiniStat(stringResource(R.string.workouts_stat_low), "$lo bpm", Modifier.weight(1f))
                }
                // the Avg HR shown above can be EDITED on the manual sheet while the graph, zones and
                // Effort stay from the recorded session (preservingCaptured keeps the captured strain/zones).
                // When the typed average disagrees materially with this trace's own mean AND the row carries
                // that captured strain/zones, say so plainly. We do NOT re-score from the typed number.
                // Parity with macOS WorkoutDetailView.avgHrEditedDisclosure.
                val traceMean = hrCurve.sum() / hrCurve.size
                val captured = row.strain != null || !row.zonesJSON.isNullOrEmpty()
                if (captured && row.avgHr != null && kotlin.math.abs(row.avgHr - traceMean) > 3.0) {
                    Text(
                        stringResource(R.string.workouts_avg_hr_edited_note),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }

            // HR-zone split — imported percentages when present, else derived from strap HR.
            zoneMinutes?.let { z ->
                val total = z.sum()
                if (total > 0.0) {
                    CardDivider()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Overline(stringResource(R.string.workouts_hr_zones_lower), modifier = Modifier.weight(1f))
                        Text(
                            stringResource(
                                if (zonesFromImport) R.string.workouts_whoop_import
                                else R.string.workouts_from_strap_hr,
                            ),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                    SegmentBar(
                        segments = z.mapIndexed { i, m -> Palette.hrZoneColor(i + 1) to (m / total).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        height = 24.dp,
                    )
                    val shares = remember(z) { zonePercents(z) }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        z.forEachIndexed { i, m -> ZoneStat(i + 1, m, shares?.getOrNull(i), Modifier.weight(1f)) }
                    }
                    Text(
                        stringResource(
                            if (zonesFromImport) R.string.workouts_zones_imported_note
                            else R.string.workouts_zones_derived_note,
                        ),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }

            heartRateRecovery?.let {
                CardDivider()
                HeartRateRecoveryCard(it)
            }
        }
    }
}

/** Per-workout HR recovery: signed bpm changes from the exercise-end HR. Missing minute windows stay
 *  dashes rather than being interpolated across a strap disconnect. */
@Composable
private fun HeartRateRecoveryCard(result: uniffi.whoop_ffi.HrRecoveryInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        SectionHeader(
            title = stringResource(R.string.workouts_hrr_title),
            overline = stringResource(R.string.workouts_hrr_overline),
            trailing = stringResource(R.string.workouts_hrr_peak, result.endHr),
        )
        NoopCard(tint = Palette.metricRose) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    RecoveryStat("1 min", result.after1min, Modifier.weight(1f))
                    RecoveryStat("2 min", result.after2min, Modifier.weight(1f))
                    RecoveryStat("5 min", result.after5min, Modifier.weight(1f))
                }
                CardDivider()
                Text(
                    stringResource(R.string.workouts_hrr_note),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun RecoveryStat(label: String, value: Int?, modifier: Modifier = Modifier) {
    val statLabel = stringResource(
        R.string.workouts_recovery_stat_cd,
        label,
        value?.toString() ?: stringResource(R.string.workouts_not_available),
    )
    Column(
        modifier = modifier.semantics { contentDescription = statLabel },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Overline(label)
        Text(
            value?.toString() ?: EM_DASH,
            style = NoopType.number(24f),
            color = value?.let { if (it >= 0) Palette.statusPositive else Palette.statusWarning }
                ?: Palette.textTertiary,
        )
        Text("bpm", style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/**
 * - the workout detail's per-session Effort contribution card. The Effort-amber tinted [NoopCard]
 * carries a "This session" overline, the captured strain as a big count-up value (the NOOP signature),
 * its scale caption (Effort 0–100 or strain 0–21), and a one-line explainer. Mirrors the iOS
 * WorkoutDetailView.effortCard: same colour world, same count-up, same copy. [strain] is the stored
 * 0–100 Effort value; [effortScale] only changes how it is DISPLAYED, never the stored number.
 */
@Composable
private fun SessionEffortCard(strain: Double, effortScale: EffortScale) {
    val shown = UnitFormatter.effortValue(strain, effortScale)
    val scaleName = stringResource(
        if (effortScale == EffortScale.WHOOP) R.string.workouts_scale_whoop else R.string.workouts_scale_effort,
    )
    val effortLabel = stringResource(R.string.workouts_session_effort_cd, oneDecimal(shown), scaleName)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        SectionHeader(
            stringResource(R.string.workouts_effort),
            overline = stringResource(R.string.workouts_this_session),
        )
        NoopCard(tint = Palette.effortColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space18),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Metrics.space2),
                    modifier = Modifier.semantics { contentDescription = effortLabel },
                ) {
                    CountUpText(
                        value = shown,
                        format = { oneDecimal(it) },
                        style = NoopType.number(34f),
                        color = Palette.effortBright,
                    )
                    Text(
                        stringResource(
                            if (effortScale == EffortScale.WHOOP) R.string.workouts_strain_caption
                            else R.string.workouts_effort_caption,
                        ),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Text(
                    stringResource(R.string.workouts_session_effort_note),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline(label)
        Text(
            value,
            style = NoopType.body,
            color = Palette.textPrimary,
            maxLines = 3,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * Per-row overflow menu. A DETECTED bout can be re-labelled (becomes a real manual session that
 * survives re-detection) or dismissed (durably hidden so it doesn't come back). A MANUAL session can
 * be edited or deleted. Imported WHOOP / Apple rows are read-only — we never rewrite imported history
 * — but can be duplicated as an editable manual copy.
 */
@Composable
private fun RowActionsMenu(
    row: WorkoutRow,
    onEdit: (WorkoutRow) -> Unit,
    onRelabel: (WorkoutRow, String) -> Unit,
    onDismiss: (WorkoutRow) -> Unit,
    onDelete: (WorkoutRow) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var relabelOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.workouts_row_actions),
                tint = Palette.textTertiary, modifier = Modifier.size(Metrics.iconSmall))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            when (WorkoutEditing.classify(row.source)) {
                WorkoutSource.DETECTED -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.workouts_relabel_as),
                                style = NoopType.body, color = Palette.textPrimary,
                            )
                        },
                        onClick = { open = false; relabelOpen = true },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.workouts_edit_details),
                                style = NoopType.body, color = Palette.textPrimary,
                            )
                        },
                        onClick = { open = false; onEdit(row) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.workouts_dismiss_not_workout),
                                style = NoopType.body, color = Palette.statusCritical,
                            )
                        },
                        onClick = { open = false; onDismiss(row) },
                    )
                }
                WorkoutSource.MANUAL -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.workouts_edit),
                                style = NoopType.body, color = Palette.textPrimary,
                            )
                        },
                        onClick = { open = false; onEdit(row) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.workouts_delete),
                                style = NoopType.body, color = Palette.statusCritical,
                            )
                        },
                        onClick = { open = false; onDelete(row) },
                    )
                }
                WorkoutSource.WHOOP, WorkoutSource.APPLE, WorkoutSource.LIFTING, WorkoutSource.ACTIVITY_FILE -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.workouts_duplicate_as_manual),
                                style = NoopType.body, color = Palette.textPrimary,
                            )
                        },
                        onClick = { open = false; onEdit(row.copy(source = "manual", sport = WorkoutEditing.displaySport(row.sport))) },
                    )
                }
            }
        }
        // Sub-menu of common sports for re-labelling a detected bout.
        DropdownMenu(expanded = relabelOpen, onDismissRequest = { relabelOpen = false }) {
            WorkoutEditing.relabelSports.forEach { sport ->
                DropdownMenuItem(
                    text = { Text(sport, style = NoopType.body, color = Palette.textPrimary) },
                    onClick = { relabelOpen = false; onRelabel(row, sport) },
                )
            }
        }
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier, color: Color? = null) {
    Text(
        text,
        style = NoopType.number(13f, androidx.compose.ui.text.font.FontWeight.Normal),
        color = color ?: if (text == "–") Palette.textTertiary else Palette.textPrimary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier,
    )
}

// MARK: - Manual workout add / edit dialog
//
// Five inputs — sport, start (date-time, here entered as minutes-ago for simplicity on phone),
// duration, average HR, calories — validated by WorkoutEditing.buildManualRow (the same honest-row
// rules the engine uses). Editing carries the original's captured maxHr/strain/route over via
// preservingCaptured so changing sport/duration never wipes them. Android mirror of macOS
// ManualWorkoutSheet (the macOS sheet uses a DatePicker; on phone we take "minutes ago" to keep the
// dialog to plain numeric fields — the persisted startTs is identical).

@Composable
private fun ManualWorkoutDialog(
    editing: WorkoutRow?,
    onDismiss: () -> Unit,
    onSave: (row: WorkoutRow, replacing: WorkoutRow?) -> Unit,
) {
    val nowSec = System.currentTimeMillis() / 1000
    // Pre-fill from the edited row ("detected" shown as "Activity" so a re-label starts clean).
    var sport by remember { mutableStateOf(editing?.let { WorkoutEditing.displaySport(it.sport) } ?: "") }
    // — absolute start date+time (parity with the macOS/iOS sheet's DatePicker) instead of the old
    // "minutes ago" field. Defaults to the edited row's start, or one hour ago for a fresh add.
    var startMillis by remember {
        mutableStateOf((editing?.startTs ?: (nowSec - 3_600)) * 1000L)
    }
    var durationMin by remember {
        mutableStateOf(
            editing?.let { (((it.durationS ?: (it.endTs - it.startTs).toDouble()) / 60).roundToInt()).coerceAtLeast(1).toString() }
                ?: "45",
        )
    }
    var avgHr by remember { mutableStateOf(editing?.avgHr?.toString() ?: "") }
    var kcal by remember { mutableStateOf(editing?.energyKcal?.let { it.roundToInt().toString() } ?: "") }

    // Build the validated row (null disables Save). Start = the chosen date+time. Captured fields preserved.
    val built: WorkoutRow? = run {
        val dur = durationMin.trim().toIntOrNull()
        val hrText = avgHr.trim()
        val kText = kcal.trim()
        // A typed-but-unparseable number is invalid (e.g. "abc" in Avg HR) — reject before building.
        val hr: Int? = if (hrText.isEmpty()) null else hrText.toIntOrNull()
        val k: Double? = if (kText.isEmpty()) null else kText.toDoubleOrNull()
        if (dur == null) return@run null
        if (hrText.isNotEmpty() && hr == null) return@run null
        if (kText.isNotEmpty() && k == null) return@run null
        // A manual workout ALWAYS lives under the strap source (where live-tracked sessions land), so
        // a "duplicate as manual" of an imported apple-health/whoop row never writes back to it.
        val base = WorkoutEditing.buildManualRow(
            deviceId = "my-whoop",
            startSeconds = (startMillis / 1000L).coerceAtMost(nowSec),
            durationMin = dur,
            sport = sport,
            avgHr = hr,
            energyKcal = k,
            nowSeconds = nowSec,
        ) ?: return@run null
        WorkoutEditing.preservingCaptured(base, editing)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            // A small Effort-world glyph so the dialog reads as part of the workouts (amber) world.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Palette.effortColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsRun,
                        contentDescription = null,
                        tint = Palette.effortColor,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
                Spacer(Modifier.width(Metrics.space10))
                Text(
                    stringResource(
                        if (editing == null) R.string.workouts_add_workout_title
                        else R.string.workouts_edit_workout_title,
                    ),
                    style = NoopType.title2, color = Palette.textPrimary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                SportPickerField(sport, onChange = { sport = it })
                StartTimeField(startMillis, onPick = { startMillis = it })
                DialogField(
                    stringResource(R.string.workouts_field_duration), durationMin,
                    onChange = { durationMin = it }, numeric = true,
                )
                DialogField(
                    stringResource(R.string.workouts_field_avg_hr), avgHr,
                    onChange = { avgHr = it }, numeric = true,
                )
                DialogField(
                    stringResource(R.string.workouts_field_calories), kcal,
                    onChange = { kcal = it }, numeric = true,
                )
                if (built == null) {
                    Text(
                        stringResource(R.string.workouts_manual_invalid),
                        style = NoopType.footnote, color = Palette.statusWarning,
                    )
                }
                // editing the Avg HR on a row that carries CAPTURED strain/zones saves the typed
                // average while the HR graph, zones and Effort stay from the recorded session
                // (preservingCaptured keeps them verbatim). That mismatch is silent, so say so plainly.
                // We do NOT re-score from one number. Parity with macOS ManualWorkoutSheet.avgHrEditedNote.
                if (built != null && WorkoutEditing.avgHrEdited(built, editing)) {
                    Text(
                        stringResource(R.string.workouts_avg_hr_typed_note),
                        style = NoopType.footnote, color = Palette.statusWarning,
                    )
                }
            }
        },
        confirmButton = {
            // Pass `replacing` only when editing an existing MANUAL or DETECTED row (the repo replaces
            // it: a manual key change deletes the stale row; a detected original is durably dismissed).
            // Duplicating an imported WHOOP/Apple row is a pure ADD — never pass it, or a changed key
            // would delete the imported original.
            val replacing = editing?.takeIf {
                val c = WorkoutEditing.classify(it.source)
                c == WorkoutSource.MANUAL || c == WorkoutSource.DETECTED
            }
            val context = LocalContext.current
            TextButton(onClick = {
                built?.let {
                    // a confirmed save is a real selection — fold the (validated) sport into the recents.
                    RecentSportsPrefs.record(context, it.sport)
                    onSave(it, replacing)
                }
            }, enabled = built != null) {
                Text(
                    stringResource(if (editing == null) R.string.workouts_add_action else R.string.workouts_save),
                    style = NoopType.body,
                    color = if (built != null) Palette.accent else Palette.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), style = NoopType.body, color = Palette.textSecondary)
            }
        },
    )
}

/**
 * Sport field for the manual add/edit dialog — a searchable PICKER over the shared catalogue
 * ([WorkoutSport.all], the SAME list the live "Start a workout" sheet uses) with a free-text
 * FALLBACK so an unusual sport NOOP doesn't enumerate still saves exactly as typed. The text
 * field IS the value: typing filters the catalogue beneath it; tapping a match fills the field; not
 * tapping anything keeps whatever was typed. The list only shows while the typed text is a partial
 * match (an exact catalogue hit, or a free-typed sport, collapses it).
 */
/**
 * Absolute start date + time for the manual add/edit dialog — parity with the macOS/iOS sheet's
 * DatePicker (; the old Android sheet only took "minutes ago"). A tappable row that opens a date
 * picker, then chains to a time picker, both capped at now (you can't log a workout in the future).
 */
@Composable
private fun StartTimeField(millis: Long, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    val label = remember(millis) { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(java.util.Date(millis)) }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Text(stringResource(R.string.workouts_started), style = NoopType.footnote, color = Palette.textSecondary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.surfaceInset)
                .border(Metrics.divider, Palette.hairline, RoundedCornerShape(10.dp))
                .clickable {
                    val cal = Calendar.getInstance().apply { timeInMillis = millis }
                    DatePickerDialog(
                        context,
                        { _, y, mo, d ->
                            TimePickerDialog(
                                context,
                                { _, h, mi ->
                                    val c = Calendar.getInstance().apply {
                                        timeInMillis = millis
                                        set(Calendar.YEAR, y); set(Calendar.MONTH, mo); set(Calendar.DAY_OF_MONTH, d)
                                        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0)
                                    }
                                    onPick(c.timeInMillis.coerceAtMost(System.currentTimeMillis()))
                                },
                                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false,
                            ).show()
                        },
                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }
                .padding(horizontal = Metrics.space12, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Palette.textTertiary, modifier = Modifier.width(16.dp))
            Text(label, style = NoopType.body, color = Palette.textPrimary)
        }
    }
}

@Composable
private fun SportPickerField(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val sportScroll = rememberScrollState()
    val q = value.trim()
    val matches = if (q.isEmpty()) WorkoutSport.all
    else WorkoutSport.all.filter { it.name.contains(q, ignoreCase = true) }
    // Hide the list once the field exactly equals a catalogue name (a settled choice) or once it's a
    // free-typed sport with no partial matches — so the dialog isn't permanently half-covered.
    val exact = WorkoutSport.all.any { it.name.equals(q, ignoreCase = true) }
    val showList = matches.isNotEmpty() && !exact
    // the user's last selections, one tap away above the full catalogue. Raw stored names —
    // this picker allows free text, so an off-catalogue recent stays selectable here (it just
    // carries no GPS hint). Only rendered while the field is empty (typing means searching).
    val recents = if (q.isEmpty()) RecentSportsPrefs.recent(context) else emptyList()

    DialogField(
        stringResource(R.string.workouts_sport), value,
        onChange = onChange, placeholder = stringResource(R.string.workouts_field_sport_placeholder),
    )
    if (showList) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 168.dp)
                .verticalScroll(sportScroll),
            verticalArrangement = Arrangement.spacedBy(Metrics.space2),
        ) {
            if (recents.isNotEmpty()) {
                Overline(stringResource(R.string.workouts_recent), modifier = Modifier.padding(top = 6.dp))
                recents.forEach { name ->
                    SportSuggestionRow(
                        name = name,
                        isDistance = WorkoutSport.all
                            .firstOrNull { it.name.equals(name, ignoreCase = true) }?.isDistanceSport == true,
                        onPick = { onChange(name) },
                    )
                }
                Overline(stringResource(R.string.workouts_all_activities), modifier = Modifier.padding(top = 6.dp))
            }
            matches.forEach { sp ->
                SportSuggestionRow(name = sp.name, isDistance = sp.isDistanceSport, onPick = { onChange(sp.name) })
            }
        }
    }
}

/** One tappable suggestion row — shared by the  Recent block and the full catalogue list. */
@Composable
private fun SportSuggestionRow(name: String, isDistance: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = Metrics.space8),
    ) {
        Text(name, style = NoopType.body, color = Palette.textPrimary)
        if (isDistance) {
            Spacer(Modifier.width(Metrics.space6))
            Text(stringResource(R.string.workouts_gps_tag), style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

@Composable
private fun DialogField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = NoopType.footnote) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, style = NoopType.body, color = Palette.textTertiary) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = workoutFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun workoutFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    focusedLabelColor = Palette.accent,
    unfocusedLabelColor = Palette.textSecondary,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
)

// MARK: - Dividers

@Composable
private fun CardDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline))
}

@Composable
private fun FullDivider(alpha: Float = 1f) {
    Box(modifier = Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline.copy(alpha = alpha)))
}

// MARK: - Range model

private enum class WorkoutRange(
    @StringRes val label: Int,
    @StringRes val caption: Int,
    val days: Int?,
    @StringRes val heroWord: Int,
) {
    Week(R.string.workouts_range_7d, R.string.workouts_range_caption_7d, 7, R.string.workouts_range_word_week),
    Month(R.string.workouts_range_30d, R.string.workouts_range_caption_30d, 30, R.string.workouts_range_word_month),
    Quarter(R.string.workouts_range_90d, R.string.workouts_range_caption_90d, 90, R.string.workouts_range_word_quarter),
    Year(R.string.workouts_range_1y, R.string.workouts_range_caption_1y, 365, R.string.workouts_range_word_year),
    All(R.string.workouts_range_all, R.string.workouts_range_caption_all, null, R.string.workouts_range_word_log),
}

/** This range plus every larger range, ascending — the auto-expand search order. */
private fun WorkoutRange.widening(): List<WorkoutRange> {
    val order = WorkoutRange.entries
    val i = order.indexOf(this)
    return if (i < 0) listOf(WorkoutRange.All) else order.subList(i, order.size)
}

/** Sessions inside a range, RELATIVE TO THE LATEST session. `All` = everything. */
private fun sessions(all: List<WorkoutRow>, r: WorkoutRange): List<WorkoutRow> {
    val days = r.days ?: return all
    val last = all.maxOfOrNull { it.startTs } ?: return emptyList()
    val cutoff = last - days * 86_400L
    return all.filter { it.startTs >= cutoff }
}

/** The range actually shown: the selected range if it holds ≥1 session (after the active filter),
 * else the smallest larger range that does — so only an empty window widens. */
private fun effectiveRange(all: List<WorkoutRow>, selected: WorkoutRange, filter: WorkoutFilter = WorkoutFilter()): WorkoutRange {
    if (all.isEmpty()) return selected
    for (r in selected.widening()) {
        if (filter.apply(sessions(all, r)).isNotEmpty()) return r
    }
    return WorkoutRange.All
}

/** Pick the tightest range that still holds ≥2 sessions; otherwise show All. */
private fun defaultRange(source: List<WorkoutRow>): WorkoutRange {
    val last = source.maxOfOrNull { it.startTs } ?: return WorkoutRange.All
    for (r in WorkoutRange.entries) {
        val days = r.days ?: continue
        val cutoff = last - days * 86_400L
        if (source.count { it.startTs >= cutoff } >= 2) return r
    }
    return WorkoutRange.All
}

// MARK: - Aggregation

private data class SportGroup(
    val sport: String,
    val count: Int,
    val totalTimeS: Double,
    val totalKcal: Double,
) {
    val totalTimeH: Double get() = totalTimeS / 3600.0
    val avgTimePerSessionMin: Double get() = if (count > 0) (totalTimeS / count) / 60.0 else 0.0
}

/** Sessions grouped by sport, ordered by count (desc), then total time. */
private fun sportGroups(rows: List<WorkoutRow>): List<SportGroup> =
    rows.groupBy { it.sport }
        .map { (sport, list) ->
            SportGroup(
                sport = sport,
                count = list.size,
                totalTimeS = list.sumOf { it.durationS ?: 0.0 },
                totalKcal = list.sumOf { it.energyKcal ?: 0.0 },
            )
        }
        .sortedWith(compareByDescending<SportGroup> { it.count }.thenByDescending { it.totalTimeS })

/**
 * The Src-column badge (label + tint) for a session. Sessions are loaded by their source's
 * deviceId — "my-whoop" / "apple-health" / "health-connect" — and each row also carries a `source`
 * label ("my-whoop" / "Apple Health" / "health-connect"), so we classify on both. This used to be a
 * binary `isWhoop ? "Whoop" : "Apple"`, which mislabelled EVERY Health Connect workout as "Apple"
 *. "HC" is abbreviated to fit the narrow column (Apple is likewise short for "Apple Health");
 * the Data Sources and Today screens spell out "Health Connect". Tints match those screens: WHOOP
 * accent green, Apple cyan, Health Connect purple.
 */
/**
 * Pure source → short badge label. `internal` + Compose-free so the unit test can pin the three
 * stored origins ("my-whoop" / "apple-health"+"Apple Health" / "health-connect") to their labels
 * without dragging in Palette. This is the classification that used to be a binary
 * `isWhoop ? "Whoop" : "Apple"`, which mislabelled every Health Connect workout as "Apple".
 * Rows are loaded by deviceId, and also carry a `source` label, so we check both.
 */
internal fun workoutSourceLabel(deviceId: String, source: String): String {
    val id = deviceId.lowercase()
    val src = source.lowercase()
    return when {
        id == "health-connect" || src.contains("health-connect") -> "HC"
        id.contains("whoop") || src.contains("whoop") -> "Whoop"
        else -> "Apple"
    }
}

// MARK: - Zone parsing/aggregation (internal + Compose-free so the unit test can pin them,
// same pattern as workoutSourceLabel). zonesJSON is a flat one-level numeric object in BOTH
// stored shapes — "zone1".."zone5" (WhoopCsvImporter.zonesJson) and "z1".."z5" (the macOS
// importer's rows) — so an anchored regex is safe, and it keeps org.json (an unmocked
// Android stub in plain-JVM unit tests) out of test-reachable code.

private val ZONE_KEY = Regex("\"z(?:one)?([1-5])\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)")

/** Zone percentages (0–100) indexed Z1..Z5, or null when the row has no usable zone data. */
internal fun parseZonePercents(zonesJSON: String?): List<Double>? {
    if (zonesJSON.isNullOrBlank()) return null
    val out = MutableList(5) { 0.0 }
    var any = false
    for (m in ZONE_KEY.findAll(zonesJSON)) {
        val v = m.groupValues[2].toDoubleOrNull() ?: continue
        out[m.groupValues[1].toInt() - 1] = v.coerceIn(0.0, 100.0)
        any = true
    }
    return if (any && out.sum() > 0.0) out else null
}

internal data class ZoneSummary(val minutes: List<Double>, val sessionsWithZones: Int) {
    val totalMinutes: Double get() = minutes.sum()
}

/** Duration-weighted zone minutes across [rows] — mirrors the macOS WorkoutZones.summary
 * (duration-minutes × pct ÷ 100). APPROXIMATE: an on-device aggregate of imported
 * per-workout percentages, not a WHOOP-computed figure. */
internal fun zoneSummary(rows: List<WorkoutRow>): ZoneSummary? {
    val mins = MutableList(5) { 0.0 }
    var n = 0
    for (r in rows) {
        val p = parseZonePercents(r.zonesJSON) ?: continue
        val durMin = (r.durationS ?: (r.endTs - r.startTs).toDouble()) / 60.0
        if (durMin <= 0.0) continue
        for (i in 0 until 5) mins[i] += durMin * p[i] / 100.0
        n++
    }
    return if (n > 0 && mins.sum() > 0.0) ZoneSummary(mins, n) else null
}

/**
 * The Src-column badge (label + tint). "HC" is abbreviated to fit the narrow column (Apple is
 * likewise short for "Apple Health"); Data Sources / Today spell out "Health Connect". Tints match
 * those screens: WHOOP accent green, Apple cyan, Health Connect purple.
 */
private val WorkoutRow.sourceBadge: Pair<Int, Color>
    get() = when (WorkoutEditing.classify(source)) {
        // Detected (on-device auto-detector) is honestly labelled so a duplicate is recognisable +
        // removable ; manual = user-logged. Both classify on `source` BEFORE the import labels.
        WorkoutSource.DETECTED -> R.string.workouts_src_detected to Palette.metricPurple
        WorkoutSource.MANUAL -> R.string.workouts_src_manual to Palette.statusWarning
        WorkoutSource.LIFTING -> R.string.workouts_src_lifting to Palette.zone2 // imported Hevy / Liftosaur log
        WorkoutSource.ACTIVITY_FILE -> R.string.workouts_src_file to Palette.metricAmber // imported GPX / TCX / FIT
        else -> when (workoutSourceLabel(deviceId, source)) {
            "HC" -> R.string.workouts_src_hc to Palette.metricPurple
            "Whoop" -> R.string.workouts_src_whoop to Palette.accent
            else -> R.string.workouts_src_apple to Palette.metricCyan
        }
    }

// MARK: - Formatting

private val dateFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(ZoneId.systemDefault())
private val timeFmt: DateTimeFormatter =
    // Respect the device's 12-/24-hour locale : "7:10 AM" or "19:10", not forced 24-hour.
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun dateLabel(ts: Long): String = dateFmt.format(Instant.ofEpochSecond(ts))
private fun timeLabel(ts: Long): String = timeFmt.format(Instant.ofEpochSecond(ts))

/** Session span "HH:mm–HH:mm"; start-only when the end isn't after the start. */
private fun timeRangeLabel(startTs: Long, endTs: Long): String =
    if (endTs > startTs) "${timeLabel(startTs)} - ${timeLabel(endTs)}" else timeLabel(startTs)

private fun durationLabel(s: Double?): String {
    if (s == null || s <= 0.0) return "–"
    val total = s.roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun grouped(v: Double): String = String.format(Locale.US, "%,d", v.roundToInt())

// MARK: - Sport icons (Material equivalents of the SF Symbols used on macOS)

// internal (not private): reused by the Today Overview-HR chart to glyph each workout at its HR peak.
internal fun sportIcon(sport: String): ImageVector {
    val s = sport.lowercase()
    return when {
        s.contains("run") -> Icons.AutoMirrored.Filled.DirectionsRun
        s.contains("walk") || s.contains("hike") -> Icons.AutoMirrored.Filled.DirectionsWalk
        s.contains("cycl") || s.contains("bike") || s.contains("ride") -> Icons.AutoMirrored.Filled.DirectionsBike
        s.contains("swim") -> Icons.Filled.Pool
        s.contains("row") -> Icons.Filled.Rowing
        s.contains("yoga") || s.contains("pilates") || s.contains("meditat") || s.contains("stretch") -> Icons.Filled.SelfImprovement
        s.contains("strength") || s.contains("weight") || s.contains("lift") -> Icons.Filled.FitnessCenter
        s.contains("box") || s.contains("martial") || s.contains("jiu") || s.contains("judo") || s.contains("karate") -> Icons.Filled.SportsMartialArts
        s.contains("hiit") || s.contains("functional") || s.contains("gymnast") -> Icons.Filled.SportsGymnastics
        s.contains("snowboard") -> Icons.Filled.Snowboarding
        s.contains("ski") -> Icons.Filled.DownhillSkiing
        // All racquet sports share the tennis glyph (no dedicated icon for padel/pickleball/squash etc.).
        s.contains("tennis") || s.contains("padel") || s.contains("pickle") || s.contains("squash") || s.contains("racquet") || s.contains("badminton") -> Icons.Filled.SportsTennis
        s.contains("volleyball") -> Icons.Filled.SportsVolleyball
        s.contains("golf") -> Icons.Filled.SportsGolf
        // No dedicated bowling icon in the Material set; the plain ball glyph is the closest match
        // (iOS has figure.bowling). (D)
        s.contains("bowl") -> Icons.Filled.SportsBaseball
        s.contains("climb") -> Icons.Filled.Terrain
        s.contains("soccer") || s.contains("football") -> Icons.Filled.SportsSoccer
        s.contains("basketball") -> Icons.Filled.SportsBasketball
        else -> Icons.Filled.FitnessCenter
    }
}
