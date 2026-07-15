<p align="center">
  <img src="docs/assets/logo-v3.png" alt="NOOP" width="72">
</p>

<h1 align="center">NOOP — Android</h1>

<p align="center"><b>Your strap. Your data. Your machine. Offline, on-device, no cloud.</b></p>

<p align="center"><sub>The <b>Android-only</b> line of NOOP — the <b>Liquid Metal</b> look: living liquid scores and a sky that moves with your day.</sub></p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#quickstart-android">Build</a> ·
  <a href="docs/PROTOCOL.md">Protocol</a>
</p>

<p align="center">
  <img src="docs/assets/shot-android-today.png" alt="Today on Android" width="240">
  &nbsp;&nbsp;
  <img src="docs/assets/shot-android-trend.png" alt="A metric's own trend on Android" width="240">
</p>
<p align="center"><sub>The <b>Liquid Metal</b> look: living liquid scores, a sky that moves with your day — Today, and a metric&rsquo;s own trend.</sub></p>

> **This is the Android-only fork** (`tanarchytan/noop`, branch `noop-tan`) of the cross-platform
> [ryanbr/noop](https://github.com/ryanbr/noop). The iOS and macOS apps have been removed here; only the
> Android app is built and shipped. Fixes that help everyone still go upstream as PRs. See [`CLAUDE.md`](CLAUDE.md).

---

## Download

The Android app you can run right now:

| Build | Notes |
|---|---|
| **`NOOP-full.apk`** (see [Releases](https://github.com/tanarchytan/noop/releases)) | The full app. `minSdk 26` (Android 8+). Sideload — enable "install unknown apps". Blocked by Play Protect? See **Installing on Android** below. |

> **Installing on Android (Play Protect blocked it?).** NOOP isn't on the Play Store — it's an
> **unsigned, source-available APK** you sideload, because the project is anonymous and has no paid
> Play identity to publish or sign under. So Android treats it as an "unknown app" and **Google
> Play Protect** may warn or block on install (most stubbornly on stock Pixel / recent Android).
> Nothing is wrong with the file — it's just missing a Play signature. To get it on:
>
> - **Tap "Install anyway."** When the warning appears, choose **More details → Install anyway**.
> - **No "Install anyway" button?** It can vanish after a first install + uninstall. Grant the source
>   directly: **Settings → Apps → Special app access → Install unknown apps**, pick the **browser or
>   file manager you're installing from**, turn on **"Allow from this source"**, then open the APK again.
> - **Still blocked by Play Protect?** It's your call to make for an unsigned app you trust: open the
>   **Play Store → your profile icon → Play Protect → ⚙ Settings**, toggle **"Scan apps with Play
>   Protect" off**, install NOOP, then switch it **back on**.
> - **Reinstalling is safe.** Uninstalling and installing again won't hurt anything — NOOP keeps all
>   data on-device with `allowBackup=false`, so a reinstall simply starts fresh. There's no cloud copy
>   to lose either way.

Prefer to build it yourself? See [Quickstart (Android)](#quickstart-android).

Everything runs **offline**. The only feature that ever uses the network is the optional **AI Coach**, and only with your own API key.

---

NOOP is a standalone, fully **offline** companion app for WHOOP straps (4.0 and
5.0). It pairs directly with the strap over Bluetooth, stores everything on your
own device in SQLite, imports your existing WHOOP and Apple Health history, and
computes recovery, strain, HRV, and sleep **locally**, with no WHOOP account and
no WHOOP cloud.

It is built on prior community interoperability work and exists for one
reason: to let someone who owns a WHOOP strap read **their own biometric data**
from **their own device**, on a machine **they** control.

> **Not affiliated with WHOOP.** NOOP is an independent, unofficial
> interoperability project. It is not affiliated with, endorsed by, or connected
> to WHOOP, Inc. "WHOOP" is used only to identify the hardware NOOP talks to. Use
> it only with a device you own, and not in breach of any agreement that applies
> to you. **NOOP is not a medical device**; every derived metric is an
> approximation, not clinical data. See [`DISCLAIMER.md`](DISCLAIMER.md).

---

## Contents

- [Why NOOP](#why-noop)
- [Features](#features)
- [Strap support](#strap-support)
- [Architecture](#architecture)
- [Quickstart (Android)](#quickstart-android)
- [How your data flows](#how-your-data-flows)
- [Privacy](#privacy)
- [Attribution](#attribution)
- [Disclaimer](#disclaimer)
- [License](#license)

---

## Why NOOP

You bought the strap. The biometric stream it produces is yours. NOOP is built on
that premise:

- **Own your data.** NOOP reads heart rate, R-R intervals, SpO₂, skin temperature,
  respiration, accelerometer/gravity, battery, and event data straight off the
  strap over Bluetooth and writes it to a local SQLite database. Nothing is
  uploaded anywhere.
- **Account-free and local.** NOOP never logs into a WHOOP account and never hits
  a WHOOP server. It does not bypass any login, paywall, or DRM; it simply talks to
  a device you own and reads data you generated.
- **Bring your history.** Already have years of data in the official app or in
  Apple Health? Import the WHOOP CSV export and/or your Apple Health `export.xml`
  once, and it's permanently on your machine.
- **Transparent math.** Recovery, strain, HRV, and sleep are recomputed on-device
  from documented, citable methods (Task Force 1996 HRV, Karvonen %HRR, Edwards /
  Banister TRIMP, Tanaka HRmax, and so on). The algorithms are approximations of —
  not reproductions of — any proprietary model, and every analyzer file documents
  exactly what it does.

---

## Features

Everything below is a real screen in the Android app (Jetpack Compose,
`android/app/src/main/java/com/noop/ui/`):

| Screen | What it does |
|---|---|
| **Today** (Control Center) | Home dashboard: recovery ring, a "today's synthesis" insight, a grid of stat tiles (recovery, strain, sleep, HRV, RHR, SpO₂, respiratory, steps, weight, calories) each with a 14-day sparkline, live strap **battery %** and HR trend, recent workouts, and a data-sources footer. |
| **Readiness** | An on-device "should you push today?" read that synthesizes established sports-science signals from your own history — HRV vs your baseline (Plews/Buchheit), resting-HR drift (Lamberts), sleeping respiratory-rate drift, training-load balance (acute:chronic workload ratio, Gabbett) and training monotony (Foster) — into a single headline (Primed / Balanced / Strained / Run down) with the drivers behind it. Pure local math, not medical advice. |
| **Live** | Real-time view of the connected strap — heart rate and frame stream as they arrive (~1 Hz). |
| **Breathe** | **HRV haptic breathing biofeedback.** The strap both *measures* HRV (R-R intervals) and *buzzes* its haptic motor, so NOOP paces your breath with felt cues (one buzz inhale, two exhale) and shows live HR + rolling RMSSD responding as the session deepens. Presets: Relax 4-6, Coherence 5.5, Box 4-4. Each session reports a **pre/post HRV outcome** so you can see how much you settled. |
| **Intervals** | **Silent haptic HIIT timer.** The strap buzzes every transition (triple-buzz into WORK, single into REST, 3-2-1 tick at phase ends, long buzz on finish) so you train hands-free. Falls back to a glanceable visual timer with no strap. |
| **Explore** (Metric Explorer) | Interrogate any single metric over time, built from the metric catalog. |
| **Compare** | Plot two metrics together / against each other over a shared timeline. |
| **Insights** | Behavioral and correlational insights derived from your own series — including **Activity Cost**, which learns what each activity type typically costs your next-morning recovery (and how long you take to bounce back) from your own history. |
| **Sleep** | Sleep sessions with a hypnogram, stage breakdown, efficiency, resting HR, and HRV — computed by the on-device sleep stager. Browse back through **past nights**, not just last night. |
| **Trends** | Long-range trends across recovery, strain, sleep, and biometrics — and a **shareable one-page PDF report** (recovery / sleep / HRV / resting HR / strain over a range you choose), rendered entirely on-device for a doctor, coach, or your own records. |
| **Workouts** | Detected and manual exercise sessions with strain and heart-rate detail. Tap any session for a full **detail view** — its HR curve over the workout, time in each HR zone, duration, avg/max HR, and the Effort it added. |
| **Health** | Biometric overview (HR, HRV, SpO₂, skin temperature, respiratory rate, etc.). |
| **Stress** | Day-level stress / autonomic load visualization. |
| **Mind** | A quick **daily mood check-in** that correlates how you feel against your own recovery, sleep and HRV over time — so you can see what actually moves your mood. On-device and **non-clinical**: a self-reflection log, not a mental-health assessment. |
| **Apple Health** | Browse and reconcile data imported from your Apple Health export. |
| **Data Sources** | One-tap import of a WHOOP CSV export, an Apple Health export, or a **nutrition CSV** (Cronometer / MacroFactor), plus live-strap status. "Bring your history in once, then it's yours." |
| **Notifications** | Configure local notifications and thresholds. |
| **Coach** | An optional **AI Coach** you can ask about your data in plain language. It's the one feature that can ever use the network: off until you add your own key — Anthropic, OpenAI, or any OpenAI-compatible endpoint including a local/self-hosted model (Ollama, LM Studio) — and it sends only a short text summary of recent metrics plus your question, never raw streams or identifiers. With a local model the conversation never leaves your machine. See [`docs/PRIVACY_SECURITY.md`](docs/PRIVACY_SECURITY.md). |
| **Settings** | Profile, preferences, **step calibration** (tune the stride/step estimate to your own walking), unit choices, the in-app **What's new** changelog, and an opt-in **Experimental** section (WHOOP 5/MG protocol probes). |

There is also a first-run **onboarding wizard** that sets expectations
(independent/experimental, WHOOP 4.0 vs 5/MG, on-device only), and an in-app
**"What's new"** changelog shown after each update.

---

## Strap support

NOOP is an independent, **experimental** project — capable, but a work in progress.

| Strap | Status |
|---|---|
| **WHOOP 4.0** | ✅ The tested, supported path. Live HR, recovery, strain, sleep, history offload — the full experience. |
| **WHOOP 5.0 / MG** | 🧪 **Live heart rate works** (confirmed on real hardware). Pick "WHOOP 5.0 / MG" before connecting — and see the pairing note below, because you can't just scan for it. Deeper 5/MG metrics (recovery, strain, sleep) are still being mapped; there's an opt-in **Settings → Experimental** toggle for 5/MG owners who want to help document the protocol. |

> ### WHOOP 5.0 / MG analysis limits
>
> NOOP's analysis screens and algorithms can only be as complete as the sensor inputs it can
> reliably decode. On WHOOP 5.0 / MG, important overnight inputs remain unavailable or incomplete:
>
> | Input / output | Current direct-from-strap status |
> |---|---|
> | Sleep duration / detection | Experimental; can fall back to heart rate when motion is sparse |
> | Sleep stages | Approximate and not reliable while full overnight motion and cardiorespiratory inputs remain incomplete |
> | Skin temperature | Raw values decode on supported historical layouts; not available consistently across 5/MG firmware |
> | Blood oxygen / SpO₂ | Not recoverable offline from current time-multiplexed PPG data |
> | Overnight HRV and respiratory rate | Incomplete unless sufficient R-R intervals are captured |
>
> In short: seeing the Sleep, Health, Readiness, or Insights screens doesn't mean their deepest
> analysis is available from a WHOOP 5.0 / MG alone yet — scoring and correlations can't conjure a
> measurement the strap hasn't given up. Decoding these inputs reliably is what we're working on, and
> it's the prerequisite for the full 5/MG picture. We'd always rather tell you that straight.
>
> ### Pairing a WHOOP 5.0 / MG — read this first
>
> A WHOOP strap holds an encrypted Bluetooth **bond with only one device at a time**, and yours is
> normally bonded to the **official WHOOP app** on your phone. **You can't just scan for it in NOOP** —
> if the strap is still bonded to the WHOOP app, NOOP's pairing is refused and the strap log shows
> *"Encryption is insufficient"* / *"bond refused."* (Live **heart rate** is the exception — it rides the
> standard Bluetooth heart-rate profile, so it streams without a bond. But pairing — needed for the
> deeper features — does not.)
>
> **To pair properly:**
> 1. **Close the official WHOOP app** on your phone (fully quit it, or turn that phone's Bluetooth off) so
>    it isn't holding the bond.
> 2. **Put the strap in pairing mode** — on a 5.0/MG, **tap the band repeatedly** (firm taps on the
>    sensor) until the **LEDs flash blue**.
> 3. In NOOP: **Live → choose "WHOOP 5.0 / MG" → Scan & Connect.** Success looks like
>    *"CLIENT_HELLO acked — link established"* in the strap log (not *"bond refused"*). It can take a
>    couple of attempts.
>
> **Only one device at a time.** Because the strap holds a single bond, don't leave it connected to your
> phone *and* another device (or the WHOOP app) at once — live heart rate will still show on all of them
> (that rides the bond-free standard profile), but **none** of them will have the real encrypted bond.
> If HR streams fine yet **buzz, alarm, double-tap and history don't work**, that's the tell: the strap
> isn't truly bonded to this device. Free it from everything else, then pair here.
>
> Bonding to NOOP may take the strap's bond away from the WHOOP app, so the official app might need to
> re-pair afterwards. This is the **hardest part of 5/MG support** — if it refuses, you're almost
> certainly still bonded to the WHOOP app (or another device); free the strap and retry.

The app always tells you what's live now versus still building, both in onboarding and on each screen.

### What to expect when you start

NOOP computes your scores on your own device, so like any recovery wearable it
needs a little data before everything fills in:

- **Live heart rate** shows the moment the strap connects.
- **Strain and sleep** appear after you've worn it and synced — the strap's last
  ~14 days offload automatically over the first few minutes.
- **Recovery** needs a few nights for the app to learn your personal baseline,
  then sharpens each night. WHOOP makes you wait for the same reason.
- **In a hurry?** Import your WHOOP export in Data Sources and your full history
  fills in about a minute.

---

## Architecture

The Android app lives entirely under [`android/`](android/) — a self-contained
Gradle project (Kotlin, Jetpack Compose, Room). It's organized by domain under
`android/app/src/main/java/com/noop/`:

```
android/
  app/src/main/java/com/noop/
    ble/         CoreBluetooth-equivalent link: bonding, offload, live notifications
    protocol/    BLE frame parsing, CRC, command/event/packet decode (WHOOP 4.0 + 5.0/MG)
    data/        Room/SQLite persistence — migrations, streams, caches (WhoopRepository)
    analytics/   HRV / recovery / strain / sleep / correlation math (pure, testable)
    ingest/      WHOOP CSV + Apple Health + nutrition importers
    ui/          Compose screens, the design system (Palette / Metrics), charts
    widget/      home-screen widgets
  app/src/test/  JVM unit tests (run on Linux/CI, no device) — analytics, protocol, import
```

- **Protocol** (`com.noop.protocol`) is platform-pure: it implements the on-wire
  frame format for both strap generations (WHOOP 4.0 = CRC8 poly 0x07 header,
  service `61080001-…`; WHOOP 5.0/MG = CRC16-Modbus header, "puffin" packet types,
  service `fd4b0001-…`). Decoding is schema-driven and includes CRC8, CRC16-Modbus,
  and zlib CRC-32, frame framing, value interpretation, and historical-stream reassembly.
- **Analytics** (`com.noop.analytics`) are pure, database-free functions grounded
  in published methods — RMSSD/SDNN from R-R intervals (Task Force 1996, Malik
  ectopic filtering), a 0–100 HRV-dominant recovery score, a 0–21 logarithmic strain
  scale (Karvonen %HRR + Edwards/Banister TRIMP), sleep/wake detection with approximate
  4-class staging, and day-aligned/lagged correlations. Each is explicitly an
  approximation, not a reproduction of any proprietary model.
- **Storage** (`com.noop.data`) keeps everything on-device in Room/SQLite —
  decoded-stream tables (`hrSample`, `rrInterval`, `spo2Sample`, `skinTempSample`,
  `respSample`), server-derived metric caches (`sleepSession`, `dailyMetric`),
  cursors, and a raw-frame outbox. Third-party deps: Room + Compose only.

> This fork keeps the Android reimplementation as its own source of truth. The
> analytics and stored-data values are the same numbers the upstream Swift app
> computes; when a fix belongs upstream too, PR the Kotlin change to
> [ryanbr/noop](https://github.com/ryanbr/noop) with its Swift twin.

---

## Quickstart (Android)

**Requirements:** JDK 17 (a recent Android Studio JBR works), the Android SDK
(`compileSdk 34`), and — to pair live — your own WHOOP strap. To just explore, you
can import a CSV / Apple Health export instead.

```bash
# 1. Clone
git clone https://github.com/tanarchytan/noop.git NOOP
cd NOOP/android

# 2. Build the debug APK (Full flavor)
./gradlew assembleFullDebug        # ./gradlew.bat on Windows

# 3. Install on a connected device
./gradlew installFullDebug         # or: adb install app/build/outputs/apk/full/debug/*.apk

# Run the JVM unit tests (no device needed)
./gradlew testFullDebugUnitTest
```

Notes:

- Flavors: **`full`** (real app) and **`demo`**. Application id `com.noop.whoop`
  (`.staging` / `.debug` suffixes install beside the official app with separate data).
- `minSdk 26` (Android 8+), `compileSdk 34`. Stack: AGP / Gradle / Kotlin, KSP, Room, Compose.
- Release APKs are built and signed by hand (or via the fork release workflow); the
  debug build falls back to the Android debug key, so CI needs no secrets.

---

## How your data flows

```
WHOOP strap ──BLE──▶ com.noop.ble ──▶ com.noop.protocol (decode)
                                          │
WHOOP CSV   ─┐                            ▼
Apple Health ├─▶ com.noop.ingest ──▶ com.noop.data (local Room/SQLite)
Nutrition CSV┘                            │
                                          ▼
                          com.noop.analytics (recovery/strain/
                          HRV/sleep, on-device)
                                          │
                                          ▼
                          com.noop.ui (Jetpack Compose)
```

Every arrow stays on your machine.

---

## Privacy

**Offline by design.** NOOP has no server, no telemetry, and no account. Your
strap data, imports, and computed metrics live in a local SQLite database on your
device and never leave it.

---

## Attribution

NOOP stands on community interoperability and protocol-documentation work. With
thanks:

- **[ryanbr/noop](https://github.com/ryanbr/noop)** — the upstream cross-platform NOOP
  this Android-only fork tracks and contributes back to.
- **`johnmiddleton12/my-whoop`** — the WHOOP 4.0 BLE protocol; the protocol and storage
  layers are adapted from this work.
- **`b-nnett/goose`** — the WHOOP 5.0 / MG BLE protocol documentation (the `fd4b0001-…`
  service family, CRC16-Modbus header, and "puffin" packet types) that NOOP's
  WHOOP 5.0 path is ported from.

NOOP contains no WHOOP proprietary code, firmware, logos, or assets, and performs
no DRM circumvention. Full detail in [`ATTRIBUTION.md`](ATTRIBUTION.md).

---

## Disclaimer

NOOP is an independent, unofficial, non-commercial interoperability project. It is
**not affiliated with, endorsed by, or connected to WHOOP, Inc.** All references to
"WHOOP" are nominative — used only to identify the third-party hardware NOOP
interoperates with.

**NOOP is not a medical device.** Heart rate, HRV, recovery, strain, sleep stages,
SpO₂, respiratory rate, and skin temperature are **approximations** computed from
published methods. They are not clinically validated and are not medical advice. Do
not use them to diagnose, treat, or make health decisions — consult a qualified
professional.

Provided **as-is**, with **no warranty**, for **personal and educational use**. You
use it at your own risk. Read the full notice in [`DISCLAIMER.md`](DISCLAIMER.md).

---

## License

NOOP is **source-available** under the [PolyForm Noncommercial License 1.0.0](LICENSE):
**free for personal and other non-commercial use** — read it, run it, fork it, and
contribute. Commercial use is not granted by this license. (PolyForm Noncommercial is
a proper software license with patent terms; it is deliberately *not* an OSI
"open-source" licence, because that would permit the commercial use this project's
non-commercial nature rules out.)

The license covers NOOP's own original code and docs. Protocol facts (frame layouts,
command numbers, byte offsets) are uncopyrightable and free to reuse; bundled
dependencies keep their own licenses (see [`NOTICE`](NOTICE)). By opening a pull request
you agree your contribution is licensed under the same terms — see
[`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md).
