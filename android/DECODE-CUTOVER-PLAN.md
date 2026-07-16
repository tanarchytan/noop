# Decode cutover plan (GATED — not applied)

This is the plan to retire the Kotlin decoders and route all frame decode through the whoop-rs FFI
(`WhoopCodec`). **Nothing here is applied.** It is hard-gated on both:

- **(a)** the shadow parity report reading **clean on a real strap night** (runbook below), and
- **(b)** **explicit approval from David** to delete Kotlin decode.

Until both hold, the Kotlin decoders remain the authoritative live path and Rust runs shadow-only
behind `PuffinExperiment.isRustShadowEnabled` (default OFF). See `RUST-FFI.md` for the shadow wiring and
the full pre-deletion checklist (v26 ppgHr, ppgWaveform, EVENT rows, stored-live rows, ts/PK path,
battery-soc precision, Gen4 leg, CI gate, response coverage, reassembly).

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

## Real-strap parity-night runbook (gate (a))

1. **Build + install** the Full debug APK on the test phone with the regenerated `.so` + binding.
2. **Enable the shadow:** Test Centre → Diagnostic tools → toggle **"Rust shadow decode (parity)"** ON
   (sets `PuffinExperiment.isRustShadowEnabled`). Optionally Reset the parity counters first.
3. **Wear + sync** a real band across a full session so real frames flow: an offload/backfill drain
   (historical v18/v24/v25 records) plus live realtime HR/R-R and at least one command response
   (battery / firmware). For the Gen4 leg, repeat with a real WHOOP 4.0 band.
4. **Read the report:** Test Centre → Diagnostic tools → parity readout (Refresh). Confirm the counters
   show **zero mismatches** across every field the shadow covers. Treat the battery-soc float-noise
   caveat and the not-yet-diffed fields (v26 ppgHr, ppgWaveform, EVENT, stored-live rows) per the
   `RUST-FFI.md` checklist — those must be closed separately, not hand-waved by a clean historical diff.
5. **Diff / record** the outcome. A clean report across the covered fields on real hardware, plus the
   checklist closed, satisfies gate (a).
6. **Then, and only with David's explicit approval (gate (b))**, route decode through `WhoopCodec.feed`
   and delete the Kotlin decoders listed above, keeping the "stays Kotlin" set intact.

Compile-success proves nothing about BLE behaviour; the parity night is on real hardware or it does not
count.
