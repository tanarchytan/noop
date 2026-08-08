package com.noop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.WhoopModel
import com.noop.data.DeviceStatus
import com.noop.data.ImportSummary
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.ingest.AppleHealthImporter
import com.noop.ingest.HealthConnectImporter
import com.noop.ingest.WhoopCsvImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - OnboardingScreen
//
// Android's first-run flow mirrors the macOS OnboardingWizard shape: a paged,
// full-screen sequence that sets expectations, scans/connects to the strap, captures
// the profile values that power zones/calories, imports history, and then hands off to
// the app shell. It uses the same AppViewModel/Repository/BLE client as the app itself.

@Composable
fun OnboardingScreen(viewModel: AppViewModel, onFinished: () -> Unit) {
    val context = LocalContext.current
    val pages = remember { OnboardingPage.entries }
    // rememberSaveable so a config change (rotation, dark-mode, font-scale, locale,
    // multi-window) doesn't recreate the Activity and throw the user back to page 1.
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    val live by viewModel.live.collectAsStateWithLifecycle()

    // No auto-advance off the Connect step: a user with several bands must pick which one to pair, so
    // the step now shows a per-band picker and the user advances by tapping Continue. advance() still
    // routes Connect → celebration when a strap is bonded, or Connect → Profile when nothing is.

    fun complete() {
        // Onboarding deferred the foreground promotion; do it now if a strap is live.
        viewModel.promoteBackgroundConnectionIfActive()
        onFinished()
    }

    // Each permission is requested as the user LEAVES the step that explains it — never on top of
    // the explaining screen, and never at launch: Bluetooth on the "before you connect" step,
    // notifications on the dedicated notifications step. We advance once the prompt is dismissed,
    // whatever the result. blePermissions() is the same shared source of truth Live/Settings use.
    val blePerms = remember { blePermissions() }
    val bleAdvanceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { pageIndex++ }
    val notifAdvanceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { pageIndex++ }

    fun advance() {
        when (page) {
            OnboardingPage.Bluetooth -> {
                val granted = blePerms.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (!granted) { bleAdvanceLauncher.launch(blePerms); return }
            }
            OnboardingPage.Connect -> {
                // No strap bonded → skip the celebration and go straight to Profile.
                if (!live.bonded) { pageIndex = pages.indexOf(OnboardingPage.Profile); return }
            }
            OnboardingPage.Notifications -> {
                val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsNotif) { notifAdvanceLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return }
            }
            else -> {}
        }
        pageIndex++
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Palette.surfaceBase,
    ) {
        // Design Reset: the flow sits on a flat opaque surfaceBase substrate — no scenic starfield
        // hero behind the steps (mirrors the iOS onboarding's clean surfaceBase background). Each
        // step's read-outs live on flat opaque NoopCards over this canvas, not floating on a scene.
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Edge-to-edge (setDecorFitsSystemWindows=false) draws under the system bars,
                // so inset for them here — the onboarding has no Scaffold to do it for us.
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Metrics.screenPadding)
                .padding(top = 16.dp, bottom = 16.dp),
        ) {
            OnboardingTopBar(
                page = pageIndex + 1,
                total = pages.size,
                progress = (pageIndex + 1).toFloat() / pages.size.toFloat(),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 44.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (page) {
                    OnboardingPage.Bluetooth -> BluetoothStep()
                    OnboardingPage.Connect -> ConnectStep(viewModel)
                    OnboardingPage.Bonded -> BondedStep(viewModel)
                    OnboardingPage.Profile -> ProfileStep()
                    OnboardingPage.Import -> ImportStep(viewModel)
                    OnboardingPage.Notifications -> NotificationsStep()
                    OnboardingPage.Appearance -> AppearanceStep()
                    OnboardingPage.Done -> DoneStep()
                }
            }

            OnboardingFooter(
                canGoBack = pageIndex > 0,
                cta = page.cta,
                onBack = {
                    var target = pageIndex - 1
                    // Skip the bonded celebration going back when nothing is bonded.
                    if (target >= 0 && pages[target] == OnboardingPage.Bonded && !live.bonded) target--
                    if (target >= 0) pageIndex = target
                },
                onNext = {
                    if (pageIndex == pages.lastIndex) {
                        complete()
                    } else {
                        advance()
                    }
                },
            )
        }
        }
    }
}

// Lean first-run flow. The marketing tour and Wear screens stay removed; setup starts at Bluetooth.
// Order: Bluetooth (permission) → Connect (scan/pair) → [Bonded] → Profile → Import → Notifications →
// Appearance → Done. Bonded is skipped by advance()/onBack when nothing is bonded.
private enum class OnboardingPage(@StringRes val cta: Int) {
    Bluetooth(R.string.onboarding_cta_begin),
    Connect(R.string.onboarding_cta_continue),
    Bonded(R.string.onboarding_cta_continue),
    Profile(R.string.onboarding_cta_save_continue),
    Import(R.string.onboarding_cta_continue),
    Notifications(R.string.onboarding_cta_continue),
    Appearance(R.string.onboarding_cta_continue),
    Done(R.string.onboarding_cta_enter);
}

// MARK: - Shell

@Composable
private fun OnboardingTopBar(page: Int, total: Int, progress: Float) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(Motion.durationStandard),
        label = "onboardingProgress",
    )

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Overline("NOOP", color = Palette.accent)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.onboarding_progress, page, total),
                style = NoopType.captionNumber,
                color = Palette.textTertiary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.hairline),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.accent),
            )
        }
    }
}

@Composable
private fun OnboardingFooter(
    canGoBack: Boolean,
    @StringRes cta: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Metrics.gap),
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            OutlinedButton(
                onClick = onBack,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.textPrimary),
                modifier = Modifier.weight(0.9f),
            ) {
                Text(stringResource(R.string.onboarding_back), style = NoopType.subhead)
            }
        }
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.accent,
                contentColor = Palette.surfaceBase,
            ),
            modifier = if (canGoBack) Modifier.weight(1.4f) else Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(cta), style = NoopType.headline)
        }
    }
}

@Composable
private fun StepShell(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.screenRowSpacing),
    ) {
        if (title != null || subtitle != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                title?.let {
                    // Big SF-Rounded hero headline — the onboarding's first-impression voice.
                    Text(
                        it,
                        style = NoopType.display(30f),
                        color = Palette.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
                subtitle?.let {
                    Text(
                        it,
                        style = NoopType.body,
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        content()
    }
}

// MARK: - Steps

@Composable
private fun BluetoothStep() {
    StepShell(
        title = stringResource(R.string.onboarding_welcome_title),
        subtitle = stringResource(R.string.onboarding_welcome_subtitle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space18),
        ) {
            IconBadge(icon = Icons.Filled.Bluetooth, tint = Palette.accent, size = 86)
            InfoCard(
                icon = Icons.Filled.Bluetooth,
                tint = Palette.accent,
                title = stringResource(R.string.onboarding_bluetooth_card_title),
                message = stringResource(R.string.onboarding_bluetooth_card_body),
            )
        }
    }
}

@Composable
private fun ConnectStep(viewModel: AppViewModel) {
    val context = LocalContext.current
    val live by viewModel.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val blePerms = remember { blePermissions() }
    val bleGranted = blePerms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // The SAME present-only scan the Add-device wizard uses (lists nearby straps in
    // viewModel.discoveredWhoops WITHOUT auto-connecting, so a user with several bands chooses WHICH one
    // to pair), but scanning BOTH WHOOP families at once — the user no longer picks 4.0 vs 5/MG up front;
    // each found band's family comes back on it. Runs once permission is in hand (granted on the Bluetooth
    // step) and nothing is bonded yet — we never raise the OS prompt here, nor start the scanner once bonded.
    LaunchedEffect(Unit) {
        if (bleGranted && !live.bonded) viewModel.presentWhoopScanAll()
    }
    // Stop the present-scan when the user leaves the step (advance / back / dismiss) so the LE scanner
    // isn't left running past this screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopWhoopScan() }
    }

    // Commit the chosen strap the same way the wizard's finishAdd does: build a WHOOP PairedDeviceRow from
    // the picked strap's address/name and the family the merged scan detected on it, register it active
    // (the SourceCoordinator then connects + pins THAT band), and end the present-scan. Once it bonds,
    // live.bonded flips true and the user taps Continue to the celebration.
    fun commit(strap: com.noop.ble.WhoopBleClient.DiscoveredWhoop) {
        // The merged scan tags each strap with the family that advertised it. When an advert carried no
        // service UUID we can't tell yet, so store the neutral "WHOOP" label — DeviceFamily.forRegistryModel
        // treats that as the WHOOP5 default and the family resolves at connect (D8).
        val family = strap.family
        val modelLabel = when (family) {
            WhoopModel.WHOOP4 -> "4.0"
            // Slashed: one WhoopModel covers both, so naming one of them is a claim we cannot make.
            WhoopModel.WHOOP5_MG -> "5.0 / MG"
            null -> "WHOOP"
        }
        // Point the scan/connect family + persist it WITHOUT the model-switch teardown (which would clear
        // the saved device and drop the bond) so a later reconnect targets the right service. Unknown → skip
        // (leave the persisted family as-is, resolve at connect).
        family?.let { viewModel.noteDetectedModel(it) }
        val now = System.currentTimeMillis() / 1000
        val device = PairedDeviceRow(
            id = "whoop-${strap.address}",
            brand = "WHOOP",
            model = modelLabel,
            nickname = strap.name?.takeIf { it.isNotBlank() } ?: (family?.displayName ?: "WHOOP"),
            peripheralId = strap.address,
            sourceKind = SourceKind.liveBLE.name,
            capabilities = "hr,hrv,spo2,skinTemp,sleep,strainLoad",
            status = DeviceStatus.paired.name,
            addedAt = now,
            lastSeenAt = now,
        )
        scope.launch { viewModel.registerDevice(device, makeActive = true) }
        viewModel.stopWhoopScan()
    }

    StepShell(
        title = stringResource(R.string.onboarding_connect_title),
        subtitle = when {
            live.bonded -> stringResource(R.string.onboarding_connect_subtitle_bonded)
            bleGranted -> stringResource(R.string.onboarding_connect_subtitle_ready)
            else -> stringResource(R.string.onboarding_connect_subtitle_no_permission)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            IconBadge(
                icon = if (live.bonded) Icons.Filled.CheckCircle else Icons.Filled.Bluetooth,
                tint = if (live.bonded) Palette.statusPositive else Palette.accent,
                size = 92,
            )

            val (label, tone, pulsing) = when {
                live.encryptedBond ->
                    Triple(stringResource(R.string.onboarding_state_bonded_streaming), StrandTone.Positive, true)
                live.bonded ->
                    Triple(stringResource(R.string.onboarding_state_live_hr), StrandTone.Warning, true)
                live.connected ->
                    Triple(stringResource(R.string.onboarding_state_connected_pairing), StrandTone.Warning, true)
                live.scanning ->
                    Triple(stringResource(R.string.onboarding_state_searching), StrandTone.Accent, true)
                else ->
                    Triple(stringResource(R.string.onboarding_state_ready), StrandTone.Neutral, false)
            }
            StatePill(label, tone = tone, pulsing = pulsing, showsDot = true)

            live.statusNote?.let {
                Text(
                    it,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (!live.bonded) {
                // The SAME per-band picker the Add-device wizard uses: tap the strap that's yours to pair
                // it. No 4.0-vs-5/MG choice up front — the scan lists both families and each row carries the
                // family it detected. Only shown while a present-scan can actually be running (permission
                // granted, not yet bonded); the picker owns its own Rescan.
                if (bleGranted) {
                    WhoopPickStep(
                        viewModel = viewModel,
                        onSelect = { strap -> commit(strap) },
                        onRescan = { viewModel.presentWhoopScanAll() },
                    )
                }
            }

            InfoCard(
                icon = Icons.Filled.Lock,
                tint = Palette.statusPositive,
                title = stringResource(R.string.onboarding_connect_card_title),
                message = stringResource(R.string.onboarding_connect_card_body),
            )

            // WHOOP is NOOP's primary band, so onboarding leads with it — but it isn't required. Make that
            // obvious so a user without a WHOOP doesn't feel stuck on this step: they can continue
            // now and add a device or import history afterwards.
            if (!live.bonded) {
                Text(
                    stringResource(R.string.onboarding_connect_no_whoop),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// A short celebration once the strap bonds — the Connect step auto-advances here on bond, and
// the nav skips it entirely when nothing is bonded (mirrors the macOS scan → bonded moment).
@Composable
private fun BondedStep(viewModel: AppViewModel) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    StepShell {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 430.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                RecoveryRing(score = 100.0, diameter = 200.dp, lineWidth = 14.dp, showsLabel = false)
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Palette.statusPositive,
                    modifier = Modifier.size(54.dp),
                )
            }
            Spacer(Modifier.height(Metrics.space24))
            Text(
                stringResource(R.string.onboarding_bonded_title),
                style = NoopType.title1,
                color = Palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Metrics.space10))
            Text(
                live.batteryPct?.let { stringResource(R.string.onboarding_bonded_battery, it.toInt()) }
                    ?: stringResource(R.string.onboarding_bonded_ready),
                style = NoopType.body,
                color = Palette.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProfileStep() {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    // Imperial/Metric display preference. The stored profile is always SI; only the DISPLAYED value
    // re-labels to lb / ft-in. [UnitPrefs] is snapshot state, so the Units control below flips this
    // screen and every other one at once.
    val unitSystem = UnitPrefs.system(context)
    var rev by remember { mutableIntStateOf(0) }
    fun mutate(block: () -> Unit) {
        block()
        rev++
    }
    @Suppress("UNUSED_VARIABLE") val tick = rev

    // Wheel-picker option lists for weight / height. The stored profile stays SI; the labels re-format per
    // the live unit system, and the picker maps the chosen index back to SI on select.
    val weightSteps = remember { generateSequence(30.0) { it + 0.5 }.takeWhile { it <= 250.0001 }.toList() }
    val heightSteps = remember { (120..230).toList() }
    val weightOptions = remember(unitSystem) { weightSteps.map { UnitFormatter.massFromKilograms(it, unitSystem) } }
    val heightOptions = remember(unitSystem) { heightSteps.map { UnitFormatter.heightFromCentimeters(it.toDouble(), unitSystem) } }

    val sexLabels = ONBOARDING_SEX_OPTIONS.associateWith { stringResource(it.label) }
    val metricLabel = stringResource(R.string.onboarding_units_metric)
    val imperialLabel = stringResource(R.string.onboarding_units_imperial)
    val weightLabel = stringResource(R.string.onboarding_weight)
    val heightLabel = stringResource(R.string.onboarding_height)

    StepShell(
        title = stringResource(R.string.onboarding_profile_title),
        subtitle = stringResource(R.string.onboarding_profile_subtitle),
    ) {
        NoopCard(padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                ProfileFieldRow(label = stringResource(R.string.onboarding_birthday)) {
                    BirthdayPickerField(
                        dobMillis = profile.dateOfBirthMillis,
                        accessibility = stringResource(R.string.onboarding_birthday_a11y, profile.age),
                        onPick = { mutate { profile.dateOfBirthMillis = it } },
                    )
                }
                ThinDivider()
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Overline(stringResource(R.string.onboarding_sex), color = Palette.textTertiary)
                    SegmentedPillControl(
                        items = ONBOARDING_SEX_OPTIONS,
                        selection = ONBOARDING_SEX_OPTIONS.firstOrNull { it.tag == profile.sex }
                            ?: ONBOARDING_SEX_OPTIONS[0],
                        label = { sexLabels.getValue(it) },
                        onSelect = { mutate { profile.sex = it.tag } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ThinDivider()
                // Units control. Onboarding read `unitSystem` for the Weight/Height display but
                // had no way to set it, so US users were locked to kg/cm until they found Settings →
                // Units. Mirror the Sex picker idiom; the stored profile stays SI either way, only the
                // displayed labels re-format (lb / ft-in). Same key Settings → Units writes.
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Overline(stringResource(R.string.onboarding_units), color = Palette.textTertiary)
                    SegmentedPillControl(
                        items = listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL),
                        selection = unitSystem,
                        label = { if (it == UnitSystem.METRIC) metricLabel else imperialLabel },
                        onSelect = { NoopPrefs.setUnitSystem(context, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ThinDivider()
                ProfileFieldRow(label = weightLabel) {
                    WheelPickerField(
                        // Full re-labelled string (e.g. "74.5 kg" / "164.2 lb"); unit folded into value.
                        value = UnitFormatter.massFromKilograms(profile.weightKg, unitSystem),
                        accessibility = weightLabel,
                        options = weightOptions,
                        selectedIndex = weightSteps.indices.minByOrNull { kotlin.math.abs(weightSteps[it] - profile.weightKg) } ?: 0,
                        dialogTitle = weightLabel,
                        onSelected = { mutate { profile.weightKg = weightSteps[it] } },
                    )
                }
                ThinDivider()
                ProfileFieldRow(label = heightLabel) {
                    WheelPickerField(
                        value = UnitFormatter.heightFromCentimeters(profile.heightCm, unitSystem),
                        accessibility = heightLabel,
                        options = heightOptions,
                        selectedIndex = heightSteps.indices.minByOrNull { kotlin.math.abs(heightSteps[it] - profile.heightCm) } ?: 0,
                        dialogTitle = heightLabel,
                        onSelected = { mutate { profile.heightCm = heightSteps[it].toDouble() } },
                    )
                }
            }
        }

        val hrMaxLabel = stringResource(R.string.onboarding_hr_max_a11y, profile.hrMax)
        Row(
            modifier = Modifier.semantics { contentDescription = hrMaxLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(17.dp))
            Text(
                stringResource(R.string.onboarding_hr_max, profile.hrMax),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun ImportStep(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // busy stays transient: a config change / process death cancels the import coroutine,
    // so a persisted busy=true would strand the buttons disabled with nothing running.
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

    fun runImport(block: suspend () -> ImportSummary) {
        busy = true
        status = context.getString(R.string.onboarding_importing)
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { ImportSummary.failure("Import", it.message ?: "failed") }
            }
            // Import & Data Ingest test mode (Test Centre): emit the parser / per-stage / day-delta trace,
            // tagged IMPORT, iff the mode is on. Gated zero-cost when off; shared with the Data Sources flow.
            emitImportTrace(context, viewModel, summary)
            busy = false
            status = summary.message
            Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show()
        }
    }

    val whoopImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { WhoopCsvImporter.importZip(context, uri, viewModel.repo) } }

    val appleImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) runImport { AppleHealthImporter.importExport(context, uri, viewModel.repo) } }

    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.any { it in HealthConnectImporter.PERMISSIONS }) {
            runImport { HealthConnectImporter.import(context, viewModel.repo, ProfileStore.from(context).heightCm,
                onBodyMeasurements = { w, h -> ProfileStore.from(context).applyMeasured(w, h) }) }
        } else {
            val message = context.getString(R.string.onboarding_health_connect_denied)
            status = message
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val healthConnectAvailable = remember {
        HealthConnectImporter.sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    fun startHealthConnect() {
        scope.launch {
            val granted = runCatching {
                HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
            if (granted.any { it in HealthConnectImporter.PERMISSIONS }) {
                runImport { HealthConnectImporter.import(context, viewModel.repo, ProfileStore.from(context).heightCm,
                onBodyMeasurements = { w, h -> ProfileStore.from(context).applyMeasured(w, h) }) }
            } else {
                hcPermissionLauncher.launch(HealthConnectImporter.PERMISSIONS)
            }
        }
    }

    StepShell(
        title = stringResource(R.string.onboarding_import_title),
        subtitle = stringResource(R.string.onboarding_import_subtitle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space14),
        ) {
            IconBadge(icon = Icons.Filled.Storage, tint = Palette.accent, size = 82)
            InfoCard(
                icon = Icons.Filled.AutoGraph,
                tint = Palette.accent,
                title = stringResource(R.string.onboarding_import_card_title),
                message = stringResource(R.string.onboarding_import_card_body),
            )

            NoopCard(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                    OnboardingActionButton(
                        label = stringResource(R.string.onboarding_import_whoop),
                        icon = Icons.Filled.FileUpload,
                        enabled = !busy,
                    ) { whoopImportLauncher.launch(arrayOf("*/*")) }
                    OnboardingActionButton(
                        label = stringResource(R.string.onboarding_import_health_connect),
                        icon = Icons.Filled.MonitorHeart,
                        enabled = !busy && healthConnectAvailable,
                    ) { startHealthConnect() }
                    OnboardingActionButton(
                        label = stringResource(R.string.onboarding_import_apple_health),
                        icon = Icons.Filled.FavoriteBorder,
                        enabled = !busy,
                    ) { appleImportLauncher.launch(arrayOf("*/*")) }
                }
            }

            if (!healthConnectAvailable) {
                Text(
                    stringResource(R.string.onboarding_health_connect_unavailable),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            status?.let {
                Text(
                    it,
                    style = NoopType.footnote,
                    color = if (busy) Palette.accent else Palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun NotificationsStep() {
    StepShell(
        title = stringResource(R.string.onboarding_notifications_title),
        subtitle = stringResource(R.string.onboarding_notifications_subtitle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space18),
        ) {
            IconBadge(icon = Icons.Filled.Notifications, tint = Palette.accent, size = 86)
            InfoCard(
                icon = Icons.Filled.Bluetooth,
                tint = Palette.statusPositive,
                title = stringResource(R.string.onboarding_notifications_card_title),
                message = stringResource(R.string.onboarding_notifications_card_body),
            )
            Checkline(stringResource(R.string.onboarding_notifications_check_alerts))
            Checkline(stringResource(R.string.onboarding_notifications_check_allow))
        }
    }
}

// A late step that tells new users NOOP's look is theirs to set — the same System / Light / Dark
// choice that lives in Settings → Appearance, with a live preview. Writing the choice flips the whole
// app immediately (AppearancePrefs.mode is snapshot state; Palette re-resolves live), so the picker
// IS the preview — and two mini swatches show both the warm-paper Light and dark blue-grey looks.
@Composable
private fun AppearanceStep() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppearancePrefs.mode) }

    StepShell(
        title = stringResource(R.string.onboarding_appearance_title),
        subtitle = stringResource(R.string.onboarding_appearance_subtitle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space18),
        ) {
            // Two mini look-swatches so the choice is concrete: warm-paper Light and dark blue-grey.
            // The one matching the live theme carries an accent (blue) rim; System shows whichever
            // the phone is currently on.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                ThemeSwatch(
                    title = stringResource(R.string.onboarding_theme_light),
                    tokens = LightTokens,
                    selected = Palette.isLight,
                    modifier = Modifier.weight(1f),
                )
                ThemeSwatch(
                    title = stringResource(R.string.onboarding_theme_dark),
                    tokens = DarkTokens,
                    selected = !Palette.isLight,
                    modifier = Modifier.weight(1f),
                )
            }

            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
                    ProfileFieldRow(label = stringResource(R.string.onboarding_theme)) {
                        SegmentedPillControl(
                            items = listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK),
                            selection = mode,
                            label = { it.label },
                            onSelect = {
                                mode = it
                                // Persist + flip live — the rest of the onboarding (and the app) re-themes
                                // instantly, so the user sees their choice land before tapping Continue.
                                AppearancePrefs.set(context, it)
                            },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                    ) {
                        Icon(
                            Icons.Filled.Palette,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            stringResource(
                                when (mode) {
                                    AppearanceMode.SYSTEM -> R.string.onboarding_appearance_system
                                    AppearanceMode.LIGHT -> R.string.onboarding_appearance_light
                                    AppearanceMode.DARK -> R.string.onboarding_appearance_dark
                                },
                            ),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

/** A small fixed-palette look-swatch (a surface chip + accent ring + hairline) so the user can see a
 *  theme without switching to it. Uses the passed token set directly (not the live Palette) so Light
 *  always renders Light and Dark always renders Dark, whatever the current theme. */
@Composable
private fun ThemeSwatch(
    title: String,
    tokens: PaletteTokens,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.surfaceBase)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Palette.accent else tokens.hairline,
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(Metrics.space12),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                // A mini score bead in the live accent (reset blue — gold is killed), on the theme's
                // raised card. Uses the live Palette.accent, not tokens.gold (whose LIGHT value is still
                // the retired gold), so the bead reads as the reset accent on both swatches.
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Palette.accent),
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(tokens.surfaceRaised),
                    )
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(tokens.hairlineStrong),
                    )
                }
            }
        }
        Text(
            title,
            style = NoopType.footnote,
            color = if (selected) Palette.accent else Palette.textTertiary,
        )
    }
}

@Composable
private fun DoneStep() {
    StepShell {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 430.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            IconBadge(icon = Icons.Filled.CheckCircle, tint = Palette.statusPositive, size = 100)
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.onboarding_done_title),
                style = NoopType.title1,
                color = Palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Metrics.space10))
            Text(
                stringResource(R.string.onboarding_done_body),
                style = NoopType.body,
                color = Palette.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: - Pieces

@Composable
private fun InfoCard(icon: ImageVector, tint: Color, title: String, message: String) {
    NoopCard(padding = 16.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.space14),
            verticalAlignment = Alignment.Top,
        ) {
            IconSquare(icon = icon, tint = tint)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary)
                Text(message, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

@Composable
private fun OnboardingActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.accent,
            contentColor = Palette.surfaceBase,
            disabledContainerColor = Palette.surfaceInset,
            disabledContentColor = Palette.textTertiary,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(Metrics.iconSmall))
        Spacer(Modifier.width(Metrics.space8))
        Text(label, style = NoopType.body)
    }
}

@Composable
private fun Checkline(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.statusPositive, modifier = Modifier.size(17.dp))
        Text(text, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.13f))
            .border(Metrics.divider, tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.42f).dp))
    }
}

@Composable
private fun IconSquare(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.13f))
            .border(Metrics.divider, tint.copy(alpha = 0.22f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Label-left, control-right form row — mirrors Settings' FormRow so profile editors match. */
@Composable
private fun ProfileFieldRow(label: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
    ) {
        Text(label, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun ThinDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Metrics.divider)
            .background(Palette.hairline),
    )
}

/** [tag] is the persisted profile value; [label] is the resource the picker resolves for display. */
private data class OnboardingSexOption(val tag: String, @StringRes val label: Int)

private val ONBOARDING_SEX_OPTIONS = listOf(
    OnboardingSexOption("male", R.string.onboarding_sex_male),
    OnboardingSexOption("female", R.string.onboarding_sex_female),
    OnboardingSexOption("nonbinary", R.string.onboarding_sex_other),
)
