# Decode Hard-Border Audit — noop-tan (Kotlin) vs whoop-rs

State: **DELETE DONE.** noop-tan HEAD `4fbe79a9`. whoop-rs is the SOLE live+offload bytes->values decoder;
the dead Kotlin frame decoders are removed. The plan/verdict tables below are kept as the record of how we
got here — read the "STATUS — delete complete" section next for what actually shipped.

## STATUS — delete complete (2026-07-17)

The rewire that made whoop-rs authoritative landed first (`ca9cd5fe` route live inbound decode through
`RustAdapter.parseFrame`), then the metadata-emit fix (`63e27f6e`), then this deletion (`4fbe79a9`). The two
commits that close this audit:

| Commit | Subject | Effect |
|---|---|---|
| `63e27f6e` | fix(protocol): emit metadata unix/trim_cursor for every record frame | RustAdapter now emits `unix`/`trim_cursor` on every per-record METADATA frame (was HISTORY_END-only), so offload ack/trim stays fed by the Rust field maps. Took `LiveDecodeParityTest` from 113 METADATA mismatches -> 869/869 clean. |
| `4fbe79a9` | refactor(protocol): delete the dead Kotlin frame decoders (whoop-rs is the sole decoder) | Trimmed `Framing.kt` to just `Reassembler`; removed the dead decoders + orphan tests. |

**Deleted** (verified 0 real callers left in `app/src/main`):
- `protocol/Framing.kt` — the whole `Framing` object: `parseFrame` + `parseWhoop4`/`parseWhoop5` +
  `decodeRealtime`/`decodeEvent`/`decodeCommandResponse`/`decodeMetadata`/`decodeConsole` and the whoop5
  twins (`decodeRealtimeWhoop5`/`decodeEventWhoop5`/`decodeCommandResponseWhoop5`/`decodeConsoleLogsWhoop5`/
  `decodeMetadataWhoop5`) + the `typeName`/`eventLabel`/`metaLabel`/`commandLabel`/`commandResultLabel`/
  `hexLabel` helpers + `verifyWhoop4`/`verifyWhoop5` + the `FrameCheck` data class + the private
  `ByteArray.u8`/`u16`/`u32` LE readers. Framing.kt now contains **only** `Reassembler`.
- `protocol/Crc.kt` — `crc8` + `crc8Table` + `crc16Modbus` (only reachable from the retired
  `verifyWhoop4`/`verifyWhoop5`).
- Tests: `protocol/FramingTest.kt` (whole file), `protocol/CrcTest.kt` (whole file),
  `protocol/LiveDecodeParityTest.kt` (whole file — it was the transitional Rust-vs-Kotlin oracle, now moot),
  and the `parsesWhoop5HistoryEndMetadata` test + `putU32LE` helper + unused `Framing` import in
  `ble/Whoop5OffloadTest.kt` (rest of that file kept green).

**Kept — NOT deleted** (these are NOT bytes->values decode):
- `protocol/Framing.kt` **`Reassembler`** — BLE fragment reassembly, still Kotlin; 3 real callers in
  `WhoopBleClient.kt` (field `:1280`, per-connect reset `:3460`, feed `:3656`) intact.
- `protocol/Crc.kt` **`crc32` + `crc32Table`** — RETAINED (Crc.kt NOT deleted entirely). `crc32` is still
  used as a frame-BUILDER by three KEEP historical-decode tests (`HistoricalStreamsClockCorrectionTest`,
  `RejectedHistoricalRecordsTest`, `Whoop5HistoricalDecodeTest`) to synthesise a valid CRC32 trailer.
  Deleting the file would break their compile; only the truly-dead `crc8`/`crc16Modbus` went.
- `ParsedFrame.kt` (the app contract `RustAdapter.parseFrame` produces), `RustCodec`/`RustAdapter` (FFI bridge).
- `protocol/Whoop5RawImu.decode` (v21 6-axis; sole caller `PuffinDeepBufferLog` diagnostics),
  `protocol/HapticClock.pulses` (buzz timing schedule, no wire bytes) — borderline, KEPT.
- `AlarmPayload.nextWakeEpochMs`; `extractHistoricalStreams` (#547/#72 ts policy);
  `classifyHistoricalMeta` + `rejectedHistoricalRecords` (offload policy); `extractStreams` + carriers;
  `skinTempCelsius`; `Enums` (label table + `fromRaw` registry + allow-list + `RebootProbeVariant`);
  `DeviceFamily`; `BackfillCaptureJsonl`; `ImuFeatureExtractor`.

**Verify (this branch):** `assembleFullDebug` = BUILD SUCCESSFUL; `testFullDebugUnitTest` = BUILD SUCCESSFUL,
2597 tests / 0 failures / 0 errors, with `buildRustHostDll` compiling the whoop-ffi crate so the FFI parity
tests (`CaptureImporterTest`, `RustAdapterTest`, `SendFrameParityTest`, `PpgDecodeGateTest`,
`CaptureTrimParityTest`) decode through real Rust. Offline capture-replay parity was proven 0-mismatch by the
prior prove pass. Line endings: modified files stage as `i/lf`. Scope: edits + commit only in `noop-wt-tan`;
whoop-rs never touched by git (build-only, its `.so`/dll compiled into gitignored `target/`).

## Follow-up in whoop-rs (out of scope for this branch — another agent track)

**GET_DATA_RANGE PENDING drop.** `decode_gen5` returns `null` on a SHORT PENDING ack for GET_DATA_RANGE, so a
`GET_DATA_RANGE -> PENDING` LOG line is dropped. This is TOLERATED and encoded in the parity test as an
accepted divergence: the SUCCESS ack decodes fine (drives `sendHistoricalKick`) and there is a 2s fail-open
fallback, so there is **no** stored-data / backfill-trigger regression. The clean fix when whoop-rs is next
touched: make that response arm `or_else` to `Some(Response::Other{..})` (or equivalent) instead of `None`, so
the PENDING ack still surfaces as a log-only frame and the parity test can drop the accepted-divergence carve-out.
Do NOT make this change from noop-tan — whoop-rs is a separate settled track.

## Hardware-night runbook — the final gate (BLE offload cannot be CI-proven)

CI proves the decode math (2597 host tests + FFI parity), but the offload/live BLE path only exists on real
hardware. Run one clean night per family before trusting the cutover. For each band:

**Setup (both families):**
1. Install the noop-tan APK on the main phone (`adb install -r`; verify cert SHA-256 matches to keep DB data).
2. Enable capture so the raw frames are banked for offline re-parity: Settings capture toggle ON
   (`noopWhoop5Capture` -> `whoop5-backfill-capture.jsonl`), or the Test Centre bundle.
3. Bond the band exclusively to noop (a phone/band pair WITHOUT the WHOOP app, so notify chars route to noop).

**5.0 / MG:**
4. Wear overnight (>= ~4h so an offload window with real records accumulates).
5. Morning: open noop, let it reconnect + run the offload/backfill to completion. Confirm the offload
   **completes** (HISTORY_END acks, no stuck "backfilling"), that **rows land** (recovery/sleep/HRV populate,
   rrInterval rows present), and that **trim is safe** (the band's history trims only AFTER a successful ack —
   no premature trim, no data hole). Watch the metadata `unix`/`trim_cursor` now come from Rust (the `63e27f6e`
   fix) — offload must ack/trim exactly as before the cutover.
6. Export `whoop5-backfill-capture.jsonl`, point `WHOOP_CAPTURE` at it, and re-run
   `CaptureTrimParityTest` + `LiveDecodeParityTest`-equivalent offline replay -> expect 0 mismatch (the one
   accepted divergence = GET_DATA_RANGE PENDING, above).

**4.0:**
7. Same wear/sync/offload-completes/rows-land/trim-safe checks on a 4.0 band. 4.0 exercises the v24/v25/v18
   record path + `crc8` framing (now Rust-side) + the #30/#77 unmapped v24-fallback that the shadow comparator
   did NOT cross-check on 5.0 — so a 4.0 night is the one that validates the paths CI can't. Confirm skin-temp,
   HR, and R-R rows match what the band reported.

**Pass criteria:** offload completes, rows land byte-identically (Room PKs unchanged), trim only after ack, and
the offline replay of the captured jsonl is 0-mismatch. If any of those fail, STOP — do not treat the delete as
final; the Kotlin history is in git (`4fbe79a9^`) if a revert is needed.

## Governing principle (the HARD BORDER) — original audit follows

Governing principle (the HARD BORDER): whoop-rs is the SINGLE SOURCE OF TRUTH for all protocol DECODE
(bytes -> values). The noop(Kotlin) <-> whoop-rs border is ONE-WAY (noop calls whoop-rs via uniffi FFI;
whoop-rs never depends on noop). Any bytes->values decode still authoritative in Kotlin is a border
violation and must move to whoop-rs. A piece is "keep" ONLY if it is genuinely NOT decode (radio
mechanics / send policy / storage-contract format / opcode registry / app ts-policy / analytics parity).

Wiring fact confirmed this pass: Rust-primary decode is OPT-IN (`rustPrimary` default false,
`HistoricalStreams.kt:546`). The Kotlin decoders below are still the AUTHORITATIVE default-path decoder
+ the per-frame fallback + the shadow comparator, so ALL are live-wired. "Border violation" here means
"still Kotlin-authoritative", not "unused". None are dead.

## Verdict table

| # | Kotlin piece | File:line | Verdict | whoop-rs status | Action | Conf |
|---|---|---|---|---|---|---|
| 1 | Crc.crc8 / crc32 / crc16Modbus | protocol/Crc.kt | already-in-rs | crc.rs (all 3), used inside framing::decode + framing::command | delete-kotlin | high |
| 2 | Reassembler | protocol/Framing.kt:44 | already-in-rs | deframe::DeframerMap; FFI `WhoopCodec.feed`/`reset` (lib.rs:267,289) | delete-kotlin | high |
| 3 | parseFrame + decodeRealtime/Event/CommandResponse/Metadata/Console (+whoop5) | protocol/Framing.kt:245 | already-in-rs | framing::decode + live:: + response:: + console::; FFI decode_history/decode_live/decode_response | delete-kotlin | high |
| 4 | buildCommand (gen4 outbound byte-build) | protocol/Framing.kt:535 | in-rs-not-exposed | framing::command exists; FFI has specific builders only, NO generic command_frame(seq,cmd,payload) | expose-ffi-then-delete | med |
| 5 | puffinCommandFrame (gen5 outbound byte-build) | protocol/Framing.kt:580 | in-rs-not-exposed | framing::command(Gen5) exists; same generic-builder FFI gap | expose-ffi-then-delete | med |
| 6 | decodeHistorical + decodeWhoop5Historical (v24/v12/v5/v7/v9/v25/v18) | protocol/HistoricalStreams.kt:167,273 | already-in-rs | records::gen4/gen5; FFI `decode_history`->HistorySummary (lib.rs:280) | delete-kotlin | high |
| 7 | decodeWhoop5HistoricalV26 (v26 24Hz PPG buffer) | protocol/HistoricalStreams.kt:381 | in-rs-not-exposed | gen5::v26 exists but `decode_history` returns None for v26 (matches only Record::History, lib.rs:283); samples only via feed()->Step::Ppg, which the offload path bypasses | expose-ffi-then-delete | high |
| 8 | decodeEventKotlin | protocol/HistoricalStreams.kt:493 | already-in-rs | live::event + battery_event + event_payload_hex; FFI decode_live Event arm (lib.rs:303) | delete-kotlin | high |
| 9 | PpgHr.estimate | protocol/PpgHr.kt:61 | already-in-rs | ppg_hr::estimate; FFI `ppg_hr` (lib.rs:425). rs won the earlier adjudication | delete-kotlin | high |
| 10 | Whoop5RawImu.decode (v21 100Hz 6-axis) | protocol/Whoop5RawImu.kt:63 | in-rs-not-exposed | gen5::v21_imu exists but `decode_history` returns None (lib.rs:283); accel/gyro only via feed()->Step::Imu. Sole caller = PuffinDeepBufferLog diagnostics | expose-ffi-then-delete | high |
| 11 | AlarmPayload.build / disableRev2 (20-byte body) | protocol/AlarmPayload.kt:49,70 | already-in-rs | alarm::build/disable_rev2; FFI alarm_set_frame/alarm_disable_frame (lib.rs:404,409) | delete-kotlin | high |
| 12 | AlarmPayload.nextWakeEpochMs | protocol/AlarmPayload.kt:31 | app-side-keep | tz/wall-clock -> epoch policy; rs alarm::build takes already-resolved wake_epoch_ms | keep | high |
| 13 | Whoop5Config payloadBody/deviceConfigBody/frame (byte-build) | protocol/Whoop5Config.kt | already-in-rs | config::r22_frames/feature_frame_named/device_frame; FFI r22_frames/set_config_frame/broadcast_hr_frame | delete-kotlin | med |
| 13b | Whoop5Config.enableR22Sequence (16-flag order/timing) | protocol/Whoop5Config.kt | app-side-keep | send ORCHESTRATION (what/when), not byte layout; frame bytes come from rs r22_frames | keep | high |
| 14 | HapticClock.pulses | protocol/HapticClock.kt:51 | already-in-rs | haptic::pulses; FFI `haptic_clock_pulses` (lib.rs:416). Borderline (no byte layout) but rs owns it | delete-kotlin | high |
| 15 | DataRange.newestUnix / oldestUnix (every-byte plausibility SCAN) | protocol/DataRange.kt:19,53 | not-in-rs | rs response::DataRange is FIXED-offset only (oldest@3/newest@7, response.rs:47). The every-byte scan + future-skew preference + asymmetric aligned-from-7 oldest (DataRange.kt:19-66) has NO rs twin | port-to-rs-then-delete | high |
| 16 | classifyHistoricalMeta | protocol/HistoricalStreams.kt:461 | app-side-keep | offload-state POLICY (Start/End/Complete). rs Offload/feed() classifies End/Complete internally — a full feed() cutover would make this dead. Underlying metadata FIELD decode lives in #3 | keep | med |
| 17 | rejectedHistoricalRecords | protocol/HistoricalStreams.kt:416 | app-side-keep | archival SELECTION policy over decodeHistorical; not itself a decoder | keep | high |
| 18 | extractHistoricalStreams (#547 plausibility + #72 5-min snap + rrInterval seq PK) | protocol/HistoricalStreams.kt:520 | app-side-keep | app CLOCK policy: device->wall correlation, snap-for-dedupe-stability, future-drop — NOT bytes->values. Wraps rs field decode (rustPrimary path). Canonical clock::to_wall/is_plausible EXISTS stranded in rs (clock.rs, NOT FFI-exposed) | keep (flagged) | high |
| 19 | extractStreams + carriers | Streams.kt:217 | app-side-keep | row assembly over ALREADY-parsed fields + device->wall offset; parse itself is in #3 | keep | high |
| 20 | skinTempCelsius / Whoop4SkinTemp | Streams.kt:53,138 | app-side-keep | cross-platform ANALYTICS scale (byte-identical-to-Swift). rs surfaces skin_temp RAW+C; the worn-anchor affine is parity math | keep | high |
| 21 | Enums EventNumber label table ("NAME(raw)") | protocol/Enums.kt:47 | app-side-keep | storage-contract formatting stored in payloadJSON | keep | high |
| 22 | Enums PacketType/MetadataType/CommandNumber.fromRaw, RebootProbeVariant | protocol/Enums.kt | app-side-keep | opcode REGISTRY + send allow-list + reboot-probe table; rs mirrors constants — keep Kotlin subset for labels + allow-list | keep | high |
| 23 | DeviceFamily (label->Gen, GATT UUIDs, static hello) | protocol/DeviceFamily.kt | app-side-keep | registry mapping + BLE radio identity; rs has GEN5_CLIENT_HELLO const too | keep | high |
| 24 | BackfillCaptureJsonl (writer) | protocol/BackfillCaptureJsonl.kt | app-side-keep | canonical sorted-key JSON-line file-IO format. FLAG: capture-file REPLAY decode (CaptureImporter) is decode — rs decode_capture/capture_line EXIST (whoop-client capture.rs) but are NOT FFI-exposed; replay should route there | keep (flagged) | high |
| 25 | PuffinDeepBufferLog | ble/PuffinDeepBufferLog.kt | app-side-keep | research capture IO + predicates; only strapTs (u32@15) is a trivial decode, low value to move | keep | high |
| 26 | ImuFeatureExtractor | analytics/ImuFeatureExtractor.kt | app-side-keep | cross-platform ANALYTICS parity, not protocol decode | keep | high |
| 27 | RustCodec / RustAdapter | protocol/RustCodec.kt, RustAdapter.kt | app-side-keep | the FFI bridge glue (correct border direction, already Rust-routed) | keep | high |

## Findings that shape the plan

1. **12 genuine byte decoders have byte-identical rs twins already reachable via FFI** and should port+delete
   straight away (#1,2,3,6,8,9,11,13,14): CRC, reassembly, envelope+per-type parse, all record versions,
   event, PPG-HR, alarm build, config frames, haptic pulses.

2. **FOUR real FFI gaps force `expose-ffi-then-delete`** (the rs code EXISTS, the FFI door does not):
   - **v26 PPG waveform (#7):** `decode_history` returns None for v26; samples only via `feed()->Step::Ppg`.
     The Kotlin offload path does not use `feed()`, so it can't delegate today.
   - **v21 IMU buffer (#10):** same shape — `decode_history` None; accel/gyro only via `feed()->Step::Imu`.
   - **generic outbound command build (#4,#5):** framing::command exists in Rust but the FFI exposes only
     specific builders. If any send opcode lacks a specific builder, add a generic
     `command_frame(seq,cmd,payload)` FFI before deleting buildCommand/puffinCommandFrame.
   - **decode_capture replay + standalone decode_metadata (#24,#16):** rs `decode_capture`/`capture_line`
     (whoop-client capture.rs) and standalone metadata-field decode are not on the uniffi surface.

3. **ONE genuine `not-in-rs` port (#15 — DataRange scan):** verified against source. rs
   `response::DataRange` reads FIXED offsets (oldest@3/newest@7, response.rs:47). Kotlin
   `DataRange.newestUnix/oldestUnix` is an every-byte plausibility SCAN (window 1_700_000_000..1_900_000_000)
   with a future-skew preference and a DELIBERATELY asymmetric aligned-from-7 oldest scan guarding a real
   WHOOP-4 straddle word (DataRange.kt:19-66). This is real bytes->values decode+heuristic and must be
   ported into whoop-rs, not assumed present. Gates auto-sync — parity matters.

4. **#547/#72 ts path (FLAG, explicitly asked): app-policy -> KEEP.** `extractHistoricalStreams`
   (HistoricalStreams.kt:566-618) does the #547 future-drop, #72 grossly-stale-RTC 5-min-grid SNAP, #471
   overshoot guard, and rrInterval seq stability. These operate on the (device,wall) clock correlation and
   dedupe stability, NOT on raw bytes — the raw fields already come from the decoder (rustPrimary supplies
   the field map, HistoricalStreams.kt:668). App CLOCK policy, stays Kotlin. NOTE: a canonical
   `clock::to_wall` + `is_plausible` already lives in whoop-rs (clock.rs) but is stranded (not FFI-exposed,
   confirmed absent from lib.rs). Consolidating onto it is optional and OUT of the border's decode scope.

5. **No Kotlin decoder is WRONG vs rs.** The one intentional divergence is v18 frame-byte 82: Kotlin carries
   it raw as `aux_byte_82`, rs surfaces it as `spo2_pct` (gated 70..100). Both agree on the STORED fields
   (RustAdapter maps only stored keys), so shadow parity holds. No `wrong-buggy` / `fix-in-rs` verdicts.

6. **Outbound command building is still Kotlin-primary in the send path** (WhoopBleClient uses
   buildCommand/puffinCommandFrame/Whoop5Config.frame/AlarmPayload.build) even though every rs builder exists
   and is unused by the app — a clean border violation to close (#4,#5,#11,#13).

7. **Host-test gate (does NOT change any verdict):** the whoop-ffi `.so` is an Android-only ELF, not loadable
   by the desktop JVM that runs `testFullDebugUnitTest`. The Kotlin decoders are today the only
   host-runnable decoder the pipeline unit tests exercise, so deleting them reds the gate unless a
   host-loadable Rust lib (desktop cdylib + uniffi JNA binding) ships in the same change. Sequence it first.

## Execution plan (ordered)

**Step 0 — Host-test lib FIRST (unblocks deletion).** Build a host-loadable whoop-ffi cdylib for the desktop
arch (the same uniffi crate, `.dll`/`.so`/`.dylib`) and load it via JNA on the `testFullDebugUnitTest`
classpath, so the pipeline unit tests decode through Rust once the Kotlin decoders are gone. Without this,
Step 4 reds the CI gate. Nothing else depends on decode staying in Kotlin, so do this before any deletion.

**Step 1 — Widen whoop-rs + FFI (no Kotlin deletion yet).** In whoop-rs, add and expose:
- (a) single-frame **v26** decode returning the 24 PPG samples (make `decode_history` yield a waveform for
  v26, or add `decode_ppg_frame`).
- (b) single-frame **v21 IMU** decode (`decode_imu_frame` -> accel/gyro columns).
- (c) **generic command builder** FFI `command_frame(seq, cmd, payload)` for both families (covers
  buildCommand/puffinCommandFrame), OR verify the existing specific builders cover every opcode the send
  path emits and skip this.
- (d) **PORT the DataRange every-byte scan** into whoop-rs (`response::data_range_scan`: plausible-window +
  future-skew newest, asymmetric aligned-from-7 oldest) and expose `data_range_newest/oldest` FFI. Only
  genuine net-new decode.
- (e) (optional, if consolidating capture replay) expose `decode_capture`/`capture_line`.
Rebuild: `cargo test` + `cargo clippy --all-targets` (0 warnings), regen the cargo-ndk `.so` for both ABIs
and the desktop cdylib from Step 0, regen uniffi bindings.

**Step 2 — Flip decode to rs-by-default.** Make `rustPrimary` the default (or drop the toggle) for
storage/live/response paths, keeping the #547/#72 ts pipeline in Kotlin wrapping the rs field maps (#18).
Route v26/v21/DataRange through the new FFI from Step 1. Validate shadow-parity one clean night (0 mismatch)
+ offline replay parity.

**Step 3 — Route the SEND path through rs builders.** Replace WhoopBleClient's Kotlin frame-build calls
(buildCommand / puffinCommandFrame / Whoop5Config.frame / AlarmPayload.build) with the FFI builders
(get_*_frame / r22_frames / set_config_frame / broadcast_hr_frame / alarm_set/disable_frame / generic
command_frame). Keep the seq counter, 5/MG allow-list, confirm/deep-data gates, and enableR22Sequence
ordering in Kotlin (send policy, #13b/#22).

**Step 4 — Delete the Kotlin decoders (behind the Step 0 host lib).** Remove:
Crc; Reassembler; Framing.parseFrame + all per-type decoders + buildCommand + puffinCommandFrame;
decodeHistorical/decodeWhoop5Historical/decodeWhoop5HistoricalV26; decodeEventKotlin; PpgHr.estimate;
Whoop5RawImu.decode; AlarmPayload.build/disableRev2; Whoop5Config byte-build (payloadBody/deviceConfigBody/
frame); HapticClock.pulses; DataRange.newestUnix/oldestUnix.
**Keep** (not decode): AlarmPayload.nextWakeEpochMs; extractHistoricalStreams (ts policy);
classifyHistoricalMeta + rejectedHistoricalRecords (offload policy); extractStreams; skinTemp; Enums label
table + fromRaw registry + allow-list; DeviceFamily; BackfillCaptureJsonl writer; PuffinDeepBufferLog;
ImuFeatureExtractor; RustCodec/RustAdapter; Whoop5Config.enableR22Sequence.

**Step 5 — Verify.** `cargo test` + `cargo clippy` (0 warn); `testFullDebugUnitTest` (now via the host lib);
`assembleFullDebug` both ABIs; one clean hardware night 0-mismatch; offline capture-replay parity.

Ordering rationale: expose before delete (Steps 1,3 before 4); host lib before delete (Step 0 before 4);
flip-default before delete so parity is proven on the rs path while the Kotlin fallback still exists.

## Verify-lens corrections (applied — these change the scope)

1. **METADATA decode is NOT FFI-exposed → reclassify #3's metadata part to expose-ffi-then-delete (was marked
   already-in-rs).** `decode_live` has no METADATA arm; `feed()`/Offload yield only `Step::Ack/Complete`,
   never `meta_type/unix/trim_cursor`. The KEPT `classifyHistoricalMeta` is fed EXCLUSIVELY by Kotlin
   `Framing.decodeMetadata(Whoop5)`, and its `End(unix,trim)` drives the offload ack/trim (Backfiller.kt:321).
   Deleting Framing's per-type decoders without a `decode_metadata` FFI (or a `feed()`-based offload cutover)
   would make 5/MG offload never ack/trim/complete. PREREQUISITE: expose `decode_metadata` OR adopt `feed()`.

2. **Generic `command_frame(seq,cmd,payload)` FFI is MANDATORY, not optional (Step 1c).** ~9 send opcodes have
   NO specific rust builder: SET_CLOCK, GET_CLOCK, GET_ALARM_TIME, REPORT_VERSION_INFO, RUN_HAPTICS_PATTERN
   (4.0), RUN_ALARM, STOP_HAPTICS, SET_ADVERTISING_NAME, HISTORICAL_DATA_RESULT. Deleting
   buildCommand/puffinCommandFrame under the specific-builder-only surface breaks sending on those.

3. **More outbound byte-ENCODERS to port (missed — inventory was protocol/-scoped).** In WhoopBleClient.kt:
   `setClockPayload`/`setClockPayloadLegacy` (:4521,:4538), `whoop4AlarmPayload` (:6044, the 4.0 twin of the
   already-in-rs 5/MG alarm), inline RUN_HAPTICS_PATTERN + SET_ADVERTISING_NAME payloads. rust has no
   set_clock / 4.0-alarm / 4.0-haptics encoder → additional not-in-rs ports before deleting them.

4. **The app never calls `feed()`/Offload/Reassembler today — it's 100% Kotlin (Framing.Reassembler +
   Backfiller).** RustCodec exposes only stateless decodeHistory/Live/Response. And the rustPrimary seams STILL
   call Kotlin: `liveStreamsPrimary` reads realtime ts from the Kotlin `ParsedFrame` (RustAdapter.kt:297);
   `recordFieldsPrimary` keeps `Framing.parseFrame` as the CRC-gate + fallback (RustAdapter.kt:262). So the
   Reassembler/parse deletions REQUIRE first routing live+offload through `feed()` and flipping the ts source —
   enumerate these rewires in Step 2; the "unreferenced" confidence was optimistic without them.

5. **DataRange port must REPLACE rust's fixed-offset decode, not reuse it.** rust `response::DataRange` reads
   fixed oldest@3/newest@7; the Kotlin gate scans the whole frame, prefers the newest non-future word, and
   anchors oldest to an aligned-from-7 grid to dodge a real WHOOP-4 straddle word. Wiring the current rust
   DataRange into the sync gate would surface a wrong (4.0-spurious) value.

6. **"No Kotlin decoder is wrong" is proven only for the seams the shadow comparator exercised** (record/
   live/response/event/ppg on mapped 5.0 fw that night). METADATA, DataRange, command-build, and the #30/#77
   unmapped-4.0 v24-fallback were NOT cross-checked — validate them (esp. on a 4.0 band) as they're ported.

Net: the four FFI gaps become SIX (add decode_metadata + generic command_frame), plus the 4.0 outbound
encoders, and Step 2 must adopt `feed()` for reassembly+offload before the Reassembler/parse deletions.
