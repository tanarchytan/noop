# Decode cutover plan (GATED — flag wired, Kotlin not deleted)

This is the plan to retire the Kotlin decoders and route all frame decode through the whoop-rs FFI
(`WhoopCodec`). The Rust-primary save path **is now wired** behind `PuffinExperiment.isRustPrimaryEnabled`
(SharedPreferences key `noopRustPrimary`, **default OFF**). No Kotlin decoder has been deleted. Deletion is
hard-gated on both:

- **(a)** the Rust-primary parity report reading **clean on a real strap night** (runbook below), and
- **(b)** **explicit approval from David** to delete Kotlin decode.

Until both hold, the Kotlin decoders remain in the tree and the default path is unchanged. Two opt-in
flags exist: `isRustShadowEnabled` (`noopRustShadow`) runs Rust in parallel and stores nothing (diff-only);
`isRustPrimaryEnabled` (`noopRustPrimary`) makes Rust the **authoritative writer** at every storing seam
while Kotlin still decodes to feed the comparator, with a per-frame Kotlin fallback on any native error
(so no frame's data is lost). See `RUST-FFI.md` for the adjudicated decode choices, the FFI regen, the
cutover wiring, and the pre-deletion checklist. Adjudication evidence:
`whoop-research/decoder-adjudication/` (findings.md, ppg_hr_838.py, gravity_gate.py, battery-soc-and-gravity-gate.md).

## Adjudicated decode choices (single source of truth = whoop-rs)

The cutover is not a Kotlin clone — each divergent field was adjudicated against real captures + external
RE (zulusierra / judasclub) and the genuinely-better implementation landed in whoop-rs:

- **ppgHr (v26)** — whoop-rs now does the sub-lag parabolic ACF refine (matches the Kotlin app, which
  already ships it hardwired ON). Confirmed on the 838 night (983 windows, real 52-109 bpm sweep):
  sub-lag MAE 2.12 vs integer-lag 2.50 bpm on the clean discriminating windows. **whoop-rs authoritative.**
- **battery SOC** — f64 division of the integer deci-percent (raw 999 → exact 99.9, not the f32-domain
  99.90000152). Ported into whoop-rs (`Response.Battery.percent` / `BatteryPack.socPct` are f64; the
  event carries the raw deci-% so the adapter divides in f64). Matches Kotlin's already-correct store.
- **gravity gate** — whoop-rs gates v18/v24 |g| to [0.5,1.5] and drops non-finite/out-of-range vectors
  the ungated Kotlin path would store. On 1861 real v18 frames the gate never fired (0 valid drops), so
  it only rejects garbage. **whoop-rs win, no code change needed.**
- **raw + contract fields** (skin_temp, activity_class, gravity v25, spo2 red/ir, resp_raw, sleep_state,
  steps, event kind + canonical payloadJSON) — decode-correct and byte-agree on both sides; kept as-is.

## What gets deleted at cutover

Delete only after the gate is satisfied and each pre-deletion checklist item in `RUST-FFI.md` is closed.

| Kotlin file / function | Role removed | Rust replacement |
|---|---|---|
| `com.noop.protocol.Crc` | CRC compute/verify | `whoop-protocol` framing CRC (inside `WhoopCodec.feed`) |
| `com.noop.protocol.Framing.parseFrame` + `Reassembler` | frame reassembly + parse | `WhoopCodec.feed(chan,bytes)` (reassembly + decode) |
| `com.noop.protocol.HistoricalStreams` (decode portion) | type-47 record decode (v18/v24/v25) | `RustCodec.decodeHistory` → `HistorySummary` |
| `com.noop.protocol.Streams.extractStreams` | live realtime stream extraction | `RustCodec.decodeLive` → `Live` |
| `com.noop.protocol.PpgHr` | v26 PPG→HR autocorrelation estimator | `whoop-metrics` `ppg_hr` (needs sub-lag parity first) |
| `com.noop.protocol.Whoop5RawImu` | v21 raw 6-axis IMU decode | `whoop-protocol` v21 record decode |
| `com.noop.protocol.Enums` (decode role only) | response/live enum decode | `RustCodec.decodeResponse` / `decodeLive` |

Note: `HistoricalStreams` keeps its **app-side** post-decode logic (the ts correction: #547 plausibility
drop + #72 snap-to-5-min grid). Only the raw-field **decode** portion is deleted; the ts transform stays
Kotlin and consumes the raw unix seconds Rust returns.

## What stays Kotlin (do NOT delete)

- `DeviceFamily.forRegistryModel` — registry `model` label → `Gen` family resolution.
- `BackfillCaptureJsonl` — the capture writer (Rust hands back `Step` + frame raw bytes; the JSONL
  schema is app-owned).
- **Command-SEND orchestration** — sequence numbers, the write allow-list, and the confirm/FORBIDDEN
  gates. Rust builds the frame bytes; the app owns when/whether to write them.
- **Standard-GATT parse** — `0x2A37` (HR) / `0x2A19` (battery level) characteristic reads.
- `AlarmPayload` — timezone→epoch alarm math.
- `DataRange` — the off-nominal `GET_DATA_RANGE` inline scan, kept until Rust grows a range-scan decoder.
- The **ts correction + rrInterval `(deviceId,ts,rrMs,seq)` PK** path — app-side, storage-determining.

## Hardware-night runbook — Rust-primary parity (gate (a))

The APK to use is `android/app/build/outputs/apk/full/debug/app-full-debug.apk` (validation build, both
ABIs, carries the regenerated `.so` + binding for the widened event + f64 battery surface).

1. **Install (data-preserving).** The phone runs `com.noop.whoop.staging`. Install over the existing app
   so paired-device + history stay:
   ```bash
   adb install -r android/app/build/outputs/apk/full/debug/app-full-debug.apk
   ```
   `-r` reinstalls keeping data; the signing cert must match the installed one or Android rejects the
   reinstall (verify the SHA-256 first if unsure). Do NOT uninstall — that wipes the Room DB + pairing.
2. **Enable the Rust-primary flag.** Test Centre → Diagnostic tools → toggle
   **"Rust primary decode (authoritative)"** ON (sets `PuffinExperiment.isRustPrimaryEnabled`, key
   `noopRustPrimary`). This makes whoop-rs the authoritative writer at every storing seam
   (`flushLive`, `Backfiller.finishChunk`, `RawHistoryArchive`/`CaptureImporter` replay, and the
   `COMMAND_RESPONSE` battery leg); Kotlin still decodes but only feeds the comparator. Reset the parity
   counters first (Reset button) so the night starts clean.
3. **Connect + sync a real 5.0 AND a real 4.0 band on the main phone.** For EACH band: pair/connect, let
   an offload/backfill drain run (historical v18 for 5.0, v24/v25 for 4.0 — this is where gravity,
   skin_temp, spo2, steps, sleep_state, v26 PPG and EVENT rows flow), keep it worn long enough for live
   realtime HR/R-R + at least one battery/firmware command response, and generate real v26 PPG windows
   (worn, on-wrist). The 4.0 leg is required — it exercises the Gen4 live path, which has no automated
   parity coverage.
4. **Read the Test Centre parity report** (Diagnostic tools → parity readout → Refresh). The header reads
   `Rust primary (whoop-rs authoritative) parity — frames diffed: N, native fallbacks: M`, then a
   per-field `match / delta (X expected / Y unexpected)` line and the `EXPECTED / UNEXPECTED` totals.

### Field-tiered PASS criteria

Not every field is expected to be byte-identical — that is the whole point of adjudication. Judge by tier:

- **Raw + contract fields** (hr, rr, skin_temp, activity_class, spo2, resp, sleep_state, steps, event
  kind + canonical `payloadJSON`, battery percent): **0 UNEXPECTED mismatches.** These decode-agree by
  design; any UNEXPECTED delta here is a real bug to triage before the gate passes.
- **whoop-rs-WIN fields (intended deltas)** — ppgHr (sub-lag), gravity (|g|-gate): these are EXPECTED to
  differ from the OLD integer/ungated Kotlin and are counted as EXPECTED, NOT UNEXPECTED. They do NOT get
  a byte-equality check; instead sanity-check the real values: ppgHr estimate count > 0 and tracking the
  concurrent v18 onboard HR within ~4-5 bpm (no runs of the ~206 bpm low-confidence harmonic dominating);
  gravity retention ~100% (the gate did not silently drop a worn night's vectors — |g| should sit near 1g).
- **native fallbacks (M): must be 0.** A non-zero count means Rust threw and Kotlin decoded that frame —
  the Rust path is not actually authoritative for those frames. Investigate before passing.
- **Records actually persisted.** A clean diff with nothing stored is not a pass. Confirm real rows
  landed under the flag: check per-stream row counts grew (Test Centre stream/DB readout, or the
  post-sync history/live/event/ppg counts), for BOTH bands. `frames diffed` > 0 AND rows persisted.

A night that meets all four tiers on real 5.0 **and** 4.0 hardware satisfies gate (a).

### Deletion step (only after a clean night + gate (b) approval)

With gate (a) satisfied and David's explicit approval (gate (b)), delete the Kotlin decoders in the
**"What gets deleted at cutover"** table above (`Crc`, `Framing.parseFrame` + `Reassembler`,
`HistoricalStreams` decode portion, `Streams.extractStreams`, `PpgHr`, `Whoop5RawImu`, the decode role in
`Enums`), route decode through `WhoopCodec.feed` at the storing seams, and keep the entire "What stays
Kotlin" set intact (`DeviceFamily.forRegistryModel`, `BackfillCaptureJsonl`, command-SEND orchestration,
standard-GATT `0x2A37`/`0x2A19`, `AlarmPayload`, `DataRange`, and the app-side ts/#547/#72/rrInterval-seq
PK path). Do it as one reviewed commit; keep the build + tests green.

Compile-success proves nothing about BLE behaviour; the parity night is on real hardware or it does not
count.
