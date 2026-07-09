# com.noop.bp — Blood Pressure (PARKED 2026-07-09)

**Experimental, opt-in, on-device cuffless BP estimate from the WHOOP 5.0 optical PPG. Wellness only —
never medical. PARKED until a BP cuff is available to calibrate/validate against.**

## Status
- **Works mechanically end-to-end** (guided capture → estimate → number; no-cuff population baseline;
  cuff-calibration path).
- **NOT yet trustworthy** — signal quality is motion-sensitive and the on-device DSP is weak (single-window
  autocorr can lock onto noise; no quality gate; confidence can be falsely high).
- **Blocked on a cuff** for absolute alignment.

## How the signal is obtained (the RE win)
The 5.0 does **not** stream raw PPG normally. Writing the **R22 feature flags** (`SET_CONFIG 0x78`,
`Whoop5Config.enableR22Sequence`) makes it bank raw optical (`v20` 24-bit @25 Hz green = **CH1**, `v21`
16-bit @100 Hz) into the offload. Firmware-verified: green=CH1 is the BP channel. Full write-up + resume
plan: `whoop/docs/BP-5.0-STATUS-PARKED.md` (+ `WHOOP5-R22-STREAM-MAP.md`).

## Files
- `BpEngine` — PPG DSP (detrend, autocorr HR, foot/peak features) + estimate (delta-from-baseline).
- `BpStore` — calibration (`calibrated` flag: cuff vs population base) + last reading, SharedPreferences.
- `BpNative` — estimate / calibrate(cuff) / **setBaseline**(population ~120/80) over `PpgBuffer`.
- `PpgBuffer` — bounded ring buffer, fed by the offload decode.
- `BpAutoMeasure` — piggybacks a reading onto strap syncs.
- `LivePpgProbe` + `BpCommandServer` — the RE/debug harness (v20/v21 decode + adb command server). These are
  experiment-only; remove before any upstream PR.
- UI: `ui/BpScreen.kt` (guided still capture), `ui/BpTodayCard.kt` (Home card).

## Resume (when the cuff arrives) — see the parking doc §7
1. Cuff-calibrate + verify (`calibrated=true`, sane HR).
2. **Quality gate** (reject implausible HR / weak autocorr) + honest confidence.
3. Port the offline bandpass + ensemble DSP on-device (got r=0.83 offline).
4. Channel-quality selector (best green photodiode).
5. Validate vs cuff, then port decode into `HistoricalStreams` and lean it for the PR.
