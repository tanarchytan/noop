package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.noop.R

// MARK: - PaletteTokens — the per-scheme colour set behind `object Palette`
//
// Compose has no OS-dynamic colour, so the light theme is built the same way the dark one is:
// ONE set of colour tokens, swapped wholesale per scheme. `Palette.active`
// is snapshot state, so every `Palette.X` read (in a composable OR a Canvas DrawScope) re-resolves
// automatically when the theme flips — ZERO call-site changes across the ~1,740 references.
//
// Dark = the "Neon Violet" cyberpunk scheme; light = "Terracotta & Sand". Both schemes carry the same
// token names in the same order.

data class PaletteTokens(
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val surfaceInset: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glowAmbient: Color,
    val accent: Color,
    val accentHover: Color,
    val accentMuted: Color,
    val focusRing: Color,
    // The label/icon on a saturated fill (the scheme accent, the critical red). Both fills stay dark
    // enough in either scheme to carry white, so the two token sets agree — it is a token so the
    // call site never spells a colour, and so one scheme can move without the other.
    val onFill: Color,
    val recovery000: Color,
    val recovery030: Color,
    val recovery055: Color,
    val recovery078: Color,
    val recovery100: Color,
    val strain000: Color,
    val strain033: Color,
    val strain066: Color,
    val strain100: Color,
    val sleepAwake: Color,
    val sleepLight: Color,
    val sleepDeep: Color,
    val sleepREM: Color,
    val zone1: Color,
    val zone2: Color,
    val zone3: Color,
    val zone4: Color,
    val zone5: Color,
    val statusPositive: Color,
    val statusWarning: Color,
    val statusCritical: Color,
    val metricCyan: Color,
    val metricPurple: Color,
    val metricAmber: Color,
    val metricRose: Color,
    val chargeColor: Color,
    val chargeDeep: Color,
    val chargeBright: Color,
    val chargeGlow: Color,
    val effortColor: Color,
    val effortDeep: Color,
    val effortBright: Color,
    val effortGlow: Color,
    val restColor: Color,
    val restDeep: Color,
    val restBright: Color,
    val restGlow: Color,
    val stressColor: Color,
    val stressDeep: Color,
    val stressBright: Color,
    val stressGlow: Color,
    val scenicCenter: Color,
    val scenicEdge: Color,
    val scenicStar: Color,
    val cardFillTop: Color,
    val cardFillBottom: Color,
    val gold: Color,
    val goldLight: Color,
    val goldDeep: Color,
    val goldDeepText: Color,
    val signalYellow: Color,
    val titaniumTop: Color,
    val titaniumMid: Color,
    val titaniumLow: Color,
    val titaniumDeep: Color,
    // The bright gauge-tip / sparkline-head core: white reads as a highlight on dark; on light it
    // would vanish into the white card, so it flips to a deep ink (crisp centre on the coloured bead).
    val tipCore: Color,
)

// Dark palette — "Neon Violet" cyberpunk: a deep violet-ink canvas, neon-violet accent + glows, Rest
// folded into violet, Charge electric-green, Effort electric-blue, magenta/cyan neon pops. Recovery
// ramp stays red→amber→green so "green = recovered" reads on the violet canvas.
val DarkTokens = PaletteTokens(
    surfaceBase = Color(0xFF0F0D1A), surfaceRaised = Color(0xFF1D1832), surfaceOverlay = Color(0xFF17132A),
    surfaceInset = Color(0xFF191430), hairline = Color(0xFF352A5C), hairlineStrong = Color(0xFF4A3A82),
    textPrimary = Color(0xFFF3F1FB), textSecondary = Color(0xFFC9C1E6), textTertiary = Color(0xFF8F86B4),
    glowAmbient = Color(0xFF2A1A55),
    accent = Color(0xFF9D6BFF), accentHover = Color(0xFFBA96FF), accentMuted = Color(0xFF241B45), focusRing = Color(0xFF9D6BFF),
    onFill = Color(0xFFFFFFFF),
    recovery000 = Color(0xFFE0463C), recovery030 = Color(0xFFE8743C), recovery055 = Color(0xFFF9DF4A),
    recovery078 = Color(0xFF8FD86A), recovery100 = Color(0xFF2EF29A),
    strain000 = Color(0xFF3A5FD0), strain033 = Color(0xFF4C7BE8), strain066 = Color(0xFF5B8DF5), strain100 = Color(0xFF8FB4FF),
    sleepAwake = Color(0xFFB9B0DC), sleepLight = Color(0xFF6E8AF0), sleepDeep = Color(0xFF5B44C8), sleepREM = Color(0xFF9B87F0),
    zone1 = Color(0xFF5B8DF5), zone2 = Color(0xFF35D0EE), zone3 = Color(0xFFF9DF4A), zone4 = Color(0xFFF0A030), zone5 = Color(0xFFF45BD0),
    statusPositive = Color(0xFF2EE6A0), statusWarning = Color(0xFFF0A030), statusCritical = Color(0xFFF04F6E),
    metricCyan = Color(0xFF35D0EE), metricPurple = Color(0xFF9D6BFF), metricAmber = Color(0xFFF0A030), metricRose = Color(0xFFF45BD0),
    chargeColor = Color(0xFF2EE6A0), chargeDeep = Color(0xFF10A46C), chargeBright = Color(0xFF6BFFC4), chargeGlow = Color(0xFF2EE6A0),
    effortColor = Color(0xFF5B8DF5), effortDeep = Color(0xFF3A5FD0), effortBright = Color(0xFF8FB4FF), effortGlow = Color(0xFF5B8DF5),
    restColor = Color(0xFF9B87F0), restDeep = Color(0xFF6A4FD0), restBright = Color(0xFFC3B4FF), restGlow = Color(0xFF9B87F0),
    stressColor = Color(0xFFF0A030), stressDeep = Color(0xFF9D6BFF), stressBright = Color(0xFFF45BD0), stressGlow = Color(0xFFF0A030),
    scenicCenter = Color(0xFF201A3A), scenicEdge = Color(0xFF0C0A17), scenicStar = Color(0xFFC9BEEC),
    cardFillTop = Color(0xFF241C44), cardFillBottom = Color(0xFF120D26),
    gold = Color(0xFF9D6BFF), goldLight = Color(0xFFC3A6FF), goldDeep = Color(0xFF6A3FC8),
    goldDeepText = Color(0xFFFFFFFF), signalYellow = Color(0xFFFFD63D),
    titaniumTop = Color(0xFFF1F3F5), titaniumMid = Color(0xFFC9CFD4), titaniumLow = Color(0xFF969DA4), titaniumDeep = Color(0xFF6B737B),
    tipCore = Color(0xFFFFFFFF),
)

// Light palette — "Terracotta & Sand": the warm-paper sand surfaces stay; chrome + the warm world shift
// from gold to terracotta; Charge ramps terracotta→ochre→sage (low→high), Effort a cool denim blue for
// contrast against the warm canvas. Rest keeps the violet it wears on dark: the chrome accent may be a
// scheme's own, but a metric's identity colour cannot change under the reader.
//
// textTertiary is set by the 4.5:1 floor against the DARKEST light surface (surfaceBase), not by eye:
// the shade that looked right measured 2.98:1 there, so every secondary read-out sat under AA.
//
// surfaceInset is the one token whose RELATION to its surroundings has to hold in both schemes: a shade
// under the card it sits in. Spelled as a mid sand it came out darker than the page itself, so muted
// panels, unselected controls and legends all read as filled.
val LightTokens = PaletteTokens(
    surfaceBase = Color(0xFFEBE1CF), surfaceRaised = Color(0xFFFDF9F1), surfaceOverlay = Color(0xFFFDF9F1),
    surfaceInset = Color(0xFFF4EDE0), hairline = Color(0xFFD8CBB2), hairlineStrong = Color(0xFFC6B597),
    textPrimary = Color(0xFF2A2018), textSecondary = Color(0xFF5C5245), textTertiary = Color(0xFF6C6255),
    glowAmbient = Color(0xFFF0E0C4),
    // Light chrome accent is terracotta (the warm brand tone for this scheme), set by the same 4.5:1
    // floor against surfaceBase that fixes textTertiary: the accent carries TEXT (CUSTOMISE, Show
    // fewer, the selected nav label) on the page itself, and the lighter terracotta measured 3.55:1
    // there. Hover keeps the light scheme's convention of going darker, not brighter.
    accent = Color(0xFFA24A2E), accentHover = Color(0xFF8A3D22), accentMuted = Color(0xFFF2E1D6), focusRing = Color(0xFFA24A2E),
    onFill = Color(0xFFFFFFFF),
    recovery000 = Color(0xFFB0402A), recovery030 = Color(0xFFC06A34), recovery055 = Color(0xFFC79A3E),
    recovery078 = Color(0xFF8A9A44), recovery100 = Color(0xFF5E8F4E),
    strain000 = Color(0xFF2A5082), strain033 = Color(0xFF3F6FA8), strain066 = Color(0xFF5A8AC0), strain100 = Color(0xFF7EA8D8),
    sleepAwake = Color(0xFF9AA0AE), sleepLight = Color(0xFF4C7BB8), sleepDeep = Color(0xFF2E5488), sleepREM = Color(0xFF7A5AA8),
    zone1 = Color(0xFF4C7BB8), zone2 = Color(0xFF2E8C9E), zone3 = Color(0xFFC79A3E), zone4 = Color(0xFFC2792E), zone5 = Color(0xFFC0402A),
    // The three STATE tokens are drawn as label text inside a StatePill, which washes the card in the
    // SAME colour at 12% — so the floor is measured against that composite, not against the bare card.
    // Measured on the phone, the all-clear chip's background is exactly 0.12*token + 0.88*surfaceRaised.
    // Spelled by eye the green read 3.63 on the card and 3.35 on its own wash.
    statusPositive = Color(0xFF3F6B34), statusWarning = Color(0xFF8C591D), statusCritical = Color(0xFFB03A26),
    // metricRose is heart rate's IDENTITY, and it had been spelled as the critical red exactly —
    // dE 7.4 from the recovery floor swatch — so a healthy 55 bpm was drawn in the colour that means
    // "this is bad". Rose in the dark scheme is magenta; the light twin is a deep rose, dE 39.6 from
    // critical and 34.4 from the chrome accent.
    metricCyan = Color(0xFF2E8C9E), metricPurple = Color(0xFF7A5AA8), metricAmber = Color(0xFFC2792E), metricRose = Color(0xFFA83E63),
    chargeColor = Color(0xFFB5673A), chargeDeep = Color(0xFF8F4E24), chargeBright = Color(0xFFD89A5E), chargeGlow = Color(0xFFC2743E),
    effortColor = Color(0xFF3F6FA8), effortDeep = Color(0xFF2A5082), effortBright = Color(0xFF6E9BCC), effortGlow = Color(0xFF3F6FA8),
    restColor = Color(0xFF7A5AA8), restDeep = Color(0xFF54397A), restBright = Color(0xFFA98CD0), restGlow = Color(0xFF7A5AA8),
    stressColor = Color(0xFFC2792E), stressDeep = Color(0xFF3F6FA8), stressBright = Color(0xFFC0402A), stressGlow = Color(0xFFC2792E),
    scenicCenter = Color(0xFFFBF6EA), scenicEdge = Color(0xFFEDE4D2), scenicStar = Color(0xFFD8CBB2),
    cardFillTop = Color(0xFFFDF9F1), cardFillBottom = Color(0xFFF6EFE2),
    gold = Color(0xFFC2743E), goldLight = Color(0xFFDDA166), goldDeep = Color(0xFF8F4E24),
    goldDeepText = Color(0xFF3A2008), signalYellow = Color(0xFFD89240),
    titaniumTop = Color(0xFFDDE1E6), titaniumMid = Color(0xFFBBC2C9), titaniumLow = Color(0xFF98A0A8), titaniumDeep = Color(0xFF6B737B),
    tipCore = Color(0xFF241B06),
)

// MARK: - Appearance preference (System / Light / Dark)

/** [storageValue] is the persisted identifier; [labelRes] is the pill caption, resolved when drawn. */
enum class AppearanceMode(val storageValue: String, @StringRes val labelRes: Int) {
    SYSTEM("system", R.string.uicore_theme_system),
    LIGHT("light", R.string.onboarding_theme_light),
    DARK("dark", R.string.onboarding_theme_dark);

    companion object {
        fun fromStorage(raw: String?): AppearanceMode =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

/** Theme preference, persisted in `noop_prefs` and mirrored in snapshot state so the toggle is live.
 *  [load] is called once from MainActivity before first composition (no flash); [set] writes + flips. */
object AppearancePrefs {
    private const val FILE = "noop_prefs"
    private const val KEY = "theme.appearance"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Live appearance mode read by NoopTheme; defaults to System until [load] runs. */
    var mode by mutableStateOf(AppearanceMode.SYSTEM)
        private set

    fun load(ctx: Context) {
        mode = AppearanceMode.fromStorage(prefs(ctx).getString(KEY, AppearanceMode.SYSTEM.storageValue))
    }

    fun set(ctx: Context, value: AppearanceMode) {
        mode = value
        prefs(ctx).edit().putString(KEY, value.storageValue).apply()
    }
}
