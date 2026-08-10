package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.Sport
import com.noop.analytics.WorkoutSport
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The shared "Start a workout" picker — sport search + GPS toggle, then [AppViewModel.startWorkout].
 * Lives in one place so both the Live screen and the Workouts screen open the SAME sheet.
 *
 * GPS needs ACCESS_FINE_LOCATION, which the BLE flow does NOT grant on Android 12+, so a GPS start
 * requests it first and falls back to a route-less workout if denied. Calls [onDismiss] once
 * the workout has started (or the user cancels).
 */
@Composable
fun StartWorkoutSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Sport>(WorkoutSport.default) }
    var gpsOn by remember(selected) { mutableStateOf(selected.isDistanceSport) }
    val filtered = WorkoutSport.all.filter { it.name.contains(query, ignoreCase = true) }
    // the user's last selections, one tap away above the full catalogue. Only catalogue-resolvable
    // recents show here — a live start selects a typed [Sport], and the shared store can hold free-typed
    // names from the manual add/edit picker. Hidden once the user starts searching.
    val recents = if (query.isBlank()) {
        RecentSportsPrefs.recent(context)
            .mapNotNull { n -> WorkoutSport.all.firstOrNull { it.name.equals(n, ignoreCase = true) } }
    } else {
        emptyList()
    }
    val sportScroll = rememberScrollState()
    // Live workout mode: once a workout begins, the sheet transitions IN PLACE into the full
    // in-exercise screen — staying mounted so its state survives — and only tells the parent to close
    // (onDismiss) when the live workout itself is closed. Hosted HERE so BOTH entry points (Live +
    // Workouts) that use this sheet get the live workout without each screen wiring it.
    var showLiveWorkout by remember { mutableStateOf(false) }
    val startWithGps = rememberRequestLocation { granted ->
        vm.startWorkout(selected, gpsEnabled = gpsOn && granted)
        showLiveWorkout = true
    }

    if (showLiveWorkout) {
        Dialog(
            onDismissRequest = { showLiveWorkout = false; onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LiveWorkoutScreen(vm = vm, onClose = { showLiveWorkout = false; onDismiss() })
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workouts_start_sheet_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.workouts_search_sport)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier.heightIn(max = 240.dp)
                        .simpleVerticalScrollbar(sportScroll)
                        .verticalScroll(sportScroll),
                ) {
                    if (recents.isNotEmpty()) {
                        Overline(stringResource(R.string.workouts_recent), modifier = Modifier.padding(top = 6.dp))
                        recents.forEach { sp ->
                            StartSportRow(sp, isSelected = sp == selected) {
                                selected = sp; gpsOn = sp.isDistanceSport
                            }
                        }
                        Overline(stringResource(R.string.workouts_all_activities), modifier = Modifier.padding(top = 6.dp))
                    }
                    filtered.forEach { sp ->
                        StartSportRow(sp, isSelected = sp == selected) {
                            selected = sp; gpsOn = sp.isDistanceSport
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.workouts_track_gps), style = NoopType.body, color = Palette.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = gpsOn, onCheckedChange = { gpsOn = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // a confirmed start is a real selection — fold it into the recents (recorded even
                // if the GPS permission is then denied; the workout still starts route-less).
                RecentSportsPrefs.record(context, selected.name)
                if (gpsOn) {
                    startWithGps() // requests location, then starts + opens live workout in the callback
                } else {
                    vm.startWorkout(selected, gpsEnabled = false)
                    showLiveWorkout = true
                }
            }) {
                Text(stringResource(R.string.workouts_start_named, selected.name))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** One tappable sport row — shared by the Recent block and the full catalogue list. */
@Composable
private fun StartSportRow(sp: Sport, isSelected: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            sp.name, style = NoopType.body,
            color = if (isSelected) Palette.accent else Palette.textPrimary,
        )
        if (sp.isDistanceSport) {
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.workouts_gps_tag), style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

/**
 * Start-a-workout entry for the Workouts screen — mirrors the Live screen's control so a user
 * can begin a session from either place. Shows a compact "running" banner while a workout is active
 * (the rich live card stays on Live), the "Start workout" button when a strap is bonded, or nothing
 * when there's no strap to stream from (matching Live, which only offers the start when bonded).
 */
@Composable
fun WorkoutStartSection(vm: AppViewModel) {
    val live by vm.live.collectAsStateWithLifecycle()
    val activeWorkout by vm.activeWorkout.collectAsStateWithLifecycle()
    var showSportPicker by remember { mutableStateOf(false) }
    // Live workout mode: the full-screen in-exercise view. StartWorkoutSheet opens it the
    // moment a workout begins; this re-entry lets the user re-open it from the compact banner after
    // dismissing. Closing just hides the overlay — the workout keeps recording in the background.
    var showLiveWorkout by remember { mutableStateOf(false) }

    // Live/Health fold: the Today "workout in progress" indicator's one-shot moved here from the removed
    // Live screen. consumeActiveWorkoutRequest() returns true exactly once per raise, and only while a
    // workout is active, so a stale flag can never open an empty overlay.
    // AppRoot routes onOpenActiveWorkout to Workouts, which mounts
    // this section and consumes the request to re-open the in-exercise overlay.
    LaunchedEffect(Unit) {
        if (vm.consumeActiveWorkoutRequest()) showLiveWorkout = true
    }

    val w = activeWorkout
    if (w != null) {
        var nowMs by remember { mutableStateOf(w.startMs) }
        LaunchedEffect(w.startMs) {
            while (true) { nowMs = System.currentTimeMillis(); delay(1000) }
        }
        val elapsedS = ((nowMs - w.startMs) / 1000).coerceAtLeast(0)
        NoopCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.workouts_active_sport, w.sport.name.uppercase()),
                    style = NoopType.overline, color = Palette.statusCritical,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    String.format(Locale.US, "%d:%02d", elapsedS / 60, elapsedS % 60),
                    style = NoopType.title2, color = Palette.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                // Re-open the full-screen live workout view.
                OutlinedButton(
                    onClick = { showLiveWorkout = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                ) { Text(stringResource(R.string.workouts_open), style = NoopType.captionNumber) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.endWorkout() },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.statusCritical, contentColor = Palette.surfaceBase,
                    ),
                ) { Text(stringResource(R.string.workouts_end), style = NoopType.captionNumber) }
            }
        }
    } else if (live.bonded) {
        Button(
            onClick = { showSportPicker = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.accent, contentColor = Palette.surfaceBase,
            ),
        ) { Text(stringResource(R.string.workouts_start_workout), style = NoopType.captionNumber) }
    }

    if (showSportPicker) {
        StartWorkoutSheet(vm = vm, onDismiss = { showSportPicker = false })
    }

    // The full-screen live workout overlay. A plain full-screen Dialog so it floats over
    // whichever screen launched it. Dismiss just hides it; End (inside) stops the workout.
    if (showLiveWorkout && activeWorkout != null) {
        Dialog(
            onDismissRequest = { showLiveWorkout = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LiveWorkoutScreen(vm = vm, onClose = { showLiveWorkout = false })
        }
    }
}

/**
 * A thin scroll indicator drawn on the right edge of a vertically-scrolling container, so a capped
 * list — like the named-sport picker (~25 sports, WorkoutSport.all) — visibly reads as scrollable
 * rather than complete. Only paints when there is overflow.
 */
private fun Modifier.simpleVerticalScrollbar(state: ScrollState, width: Dp = 3.dp): Modifier =
    drawWithContent {
        drawContent()
        if (state.maxValue > 0) {
            val viewport = size.height
            val contentH = viewport + state.maxValue
            val thumbH = (viewport / contentH) * viewport
            val thumbY = (state.value.toFloat() / state.maxValue) * (viewport - thumbH)
            val w = width.toPx()
            drawRoundRect(
                color = Palette.textTertiary.copy(alpha = 0.5f),
                topLeft = Offset(size.width - w, thumbY),
                size = Size(w, thumbH),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
