# WHOOP 5/MG Clock Gap: Findings and Research Plan

**Status:** Open protocol question. No Gen5 wire change approved.

**Scope:** Android-only NOOP path, with `whoop-rs`, firmware research, and captured diagnostics used as cross-checks.

**Evidence snapshot:** NOOP working tree based on `f7238c427e2dd880675201acb845b184b880ff8d`; `whoop-rs` at `17c3a6f5f89a7330f4e1eecedcdd63cb2a34d39a`; inspected 2026-07-20. “Source-direct” below means read directly from code, a saved capture, or a recorded hardware report. It does not automatically mean hardware behavior was isolated experimentally.

## Executive conclusion

NOOP currently sends WHOOP 5/MG `SET_CLOCK` as opcode `10` with an 8-byte body, then sends `GET_CLOCK` as opcode `11` with an empty body. It immediately logs that the clock is synced. Android does not decode a Gen5 clock response, does not verify that either command succeeded semantically, and does not use a Gen5 clock reference.

Repository evidence conflicts with that path. The Maverick command table and firmware strings identify `SET_CLOCK=146` and `GET_CLOCK=147`. `whoop-rs` already sends getter `147`, while its Android-facing clock builder still emits setter `10`. Existing debug captures show outbound `147`, but no matching response fixture was located.

Do not switch Android blindly. The strongest operational evidence currently available links the low `10/11` handshake with restored WHOOP 5 history banking: a strap reporting invalid RTC went from zero historical frames to 246 after that handshake. This makes the existing path unsafe to remove without controlled replacement evidence. It does not isolate setter `10` as the cause, prove getter `11` replied, prove opcode `147` cannot reply, or establish the body expected by setter `146`.

Current safe policy:

- Keep WHOOP 5/MG timestamp correlation disabled.
- Keep valid Gen5 record Unix timestamps unchanged.
- Keep the existing low setter until hardware tests prove a replacement.
- Treat the getter and “clock synced” log as unverified.
- Never sweep clock setters. Both are state-changing commands.

## Product impact

Clock setup affects data availability, not score math directly.

When the strap RTC is invalid, firmware can refuse to bank new sensor history. Live heart rate may continue, while sleep, Recovery, and other history-derived screens become missing or stale. Changing the Gen5 setter incorrectly could therefore break overnight Recovery without causing an obvious BLE error.

Gen5 realtime and historical records already carry native Unix fields. A “valid Gen5 timestamp” in this report means that native field passes existing ingestion plausibility checks without a speculative clock offset. Applying an unverified offset risks shifting correct data onto another day, duplicating re-offloaded rows, and assigning sleep or Recovery to the wrong night.

## What is confirmed

| Confidence | Finding | Evidence |
|---|---|---|
| Source-direct | Android models only low `SET_CLOCK=10` and `GET_CLOCK=11`. | `Enums.kt` |
| Source-direct | Gen5 connect sends low setter `10` through Puffin framing, then low getter `11`. | `WhoopBleClient.kt`, `RustAdapter.kt`, `whoop-ffi` |
| Source-direct | Android logs “clock synced” immediately after queueing writes. No decoded response gates that log. | `WhoopBleClient.kt` |
| Source-direct | Android deliberately suppresses `Response.Clock` for Gen5. | `RustAdapter.kt` |
| Source-direct | Android clock correlation remains WHOOP 4-only. | `WhoopBleClient.kt`, `ClockReference.kt` |
| Source-direct | Gen5 type-40 and type-47 records contain real Unix fields. | captured record layouts and `BLE_REVERSE_ENGINEERING.md` |
| Source-direct | `whoop-rs` defines Maverick setter `146` and getter `147`. Its desktop getter uses `147`. | `command.rs`, `client.rs` |
| Source-direct | `whoop-rs` FFI clock setter always uses low opcode `10`, including Gen5. | `whoop-ffi/src/lib.rs` |
| Source-direct | `whoop-rs` has no Gen5 clock response decoder or raw Gen5 clock fixture. | `response.rs` |
| Source-direct | Firmware research contains strings naming clock commands `0x92` and `0x93`. | `tools/maverick-disasm.py` |
| Source-direct | Debug logs contain outbound getter `147`. No matching `147` response was located in saved captures. | `whoop-debug` logs |
| Source-direct, bundled single-hardware report | Low `10/11` handshake preceded history growth from 0 to 246 frames. Setter, getter, reconnect, and handshake ordering were not isolated. | `CHANGELOG.md` 1.62 report |
| Reference-derived, unverified | Ecosystem research claims Maverick setter `146` uses a 5-byte Unix-plus-timezone body, conflicting with Android's low-opcode 8-byte body. | `whoop-research/references-analysis.md` |

“No matching response located” is not proof that `147` never responds. Existing sweeps mix several commands, response timing, and connection state. A controlled single-command capture remains required.

## What is wrong

### 1. Opcode ownership is split

Android uses `10/11`. Desktop Rust uses getter `147`. Firmware research names `146/147`. No family-aware source of truth controls every caller.

### 2. Setter framing is internally inconsistent

`WhoopCodec.set_clock_frame(gen5=true)` selects Gen5 framing but still embeds opcode `10`. Framing family and command family can therefore disagree while the API name looks correct.

### 3. Success is not verified

Android write-with-response only confirms Android's GATT write completed. It does not prove firmware accepted the command, recognized the body length, or latched the RTC.

### 4. Logging overclaims

`WHOOP 5/MG: clock synced (set/get)` appears before any semantic confirmation. Correct meaning is closer to “clock writes queued.”

### 5. Getter replies cannot be consumed

Gen5 response decoding has no clock case. Even a valid reply cannot produce a verified clock value today.

### 6. No fixture pins the layout

No captured Gen5 command-response establishes:

- returned command number;
- result-code position;
- Unix field offset;
- payload length;
- monotonic tick behavior;
- differences between WHOOP 5 and MG.

### 7. Documentation contradicts itself

`PROTOCOL.md` documents Maverick `146/147` while current Android sends `10/11`. `BLE_REVERSE_ENGINEERING.md` says low getter `11` is accepted in one section and not served in another. The changelog says low `10/11` restored history, without separating setter proof from getter proof.

### 8. Tests cover shapes, not hardware truth

Current tests can prove emitted bytes match current code. They cannot prove which Gen5 opcode/body firmware accepts because no hardware fixture anchors expected behavior.

## Gen5 change risk

Risk labels describe impact if the failure occurs. Likelihood remains unknown until the hardware matrix is complete.

### Critical: history banking loss

Replacing working low setter `10` with unverified high setter `146` could leave RTC invalid. Firmware may then stop saving new sensor history. User symptom: live HR works, but sleep and Recovery stop updating.

### High: correct timestamps corrupted

Using an unverified GET response to offset native Gen5 Unix records could shift data by years, duplicate rows across clock epochs, or move sleep between days.

### High: false confidence

ATT success and current log can hide semantic failure. A release can appear healthy during connection tests while overnight data silently stops banking.

### Medium: model and firmware divergence

WHOOP 5, MG, and firmware revisions may support different aliases or payload lengths. One successful band does not establish a universal mapping.

### Medium: dual-send side effects

Sending setters `10` and `146` together is not proven harmless. Setter `146` has a firmware name, but its accepted body and revision rules remain unverified.

## Open questions

1. Does WHOOP 5 answer getter `11`, getter `147`, both, or neither?
2. Does MG behave identically?
3. Does low setter `10` latch on every current firmware family?
4. Does high setter `146` latch, and what body does it require?
5. Does a clock response echo the requested opcode?
6. Where are result and Unix fields located?
7. Can RTC latch be confirmed through a getter, event timestamp, console message, or all three?
8. Which behavior restores data-range growth and new type-47 banking?
9. Are low opcodes compatibility aliases or different handlers?

## Hardware research plan

### Stage 0: preserve baseline

Before any clock write:

- Record model, hardware revision, firmware, device identifier suffix, battery, phone wall time, and timezone.
- Offload and preserve existing history.
- Capture current data-range oldest/newest values.
- Capture firmware console RTC warnings when available.
- Synchronize capture-host time to UTC and record measured clock error.
- Record repository commits, APK version, capture-tool hash, and exact trial order.
- Hash preserved history and every raw fixture with SHA-256.
- Redact full serial numbers, MAC addresses, and device tokens from published artifacts.
- Run a no-command control connection before each command family.
- Use one command per fresh connection. Do not run a command sweep.
- Never intentionally invalidate or roll back the strap RTC.

### Stage 1: read-only getter matrix

Test these separately on WHOOP 5 and MG:

| Trial | Opcode | Body | Expected evidence |
|---|---:|---|---|
| A | `11` | empty | complete outbound frame, ATT result, all inbound frames for a fixed observation window |
| B | `147` | empty | same capture set |
| C | accepted getter only | repeat after 5–10 seconds | candidate Unix field advances by elapsed time |

Repeat each isolated trial at least three times. Set the observation window before testing; use at least 10 seconds and longer than five times measured control-response latency. Only test a one-byte getter body if firmware evidence specifically requires it. Do not infer success from the write callback.

Archive raw bytes before writing a decoder. A valid candidate Unix field must:

- land near phone wall time;
- repeat at the same offset;
- advance about one second per second;
- remain stable across reconnects;
- carry an explicit successful result when the protocol supplies one.

### Stage 2: controlled setter verification

Run only on David's own fully charged strap, after preserving history. Test one setter variant per fresh connection:

| Trial | Opcode | Body | Status |
|---|---:|---|---|
| A | `10` | current 8-byte Unix-plus-subseconds body | existing Android behavior |
| B | `146` | body derived from an archived official-app BLE capture with device/firmware metadata and SHA-256 | blocked pending evidence |

Semantic success requires all applicable checks:

- accepted getter reads within ±2 seconds of wall time;
- repeated getter increments normally;
- event or realtime RTC agrees;
- firmware console stops reporting invalid RTC;
- data-range newest advances;
- newly recorded type-47 frames bank and offload.

An ATT write acknowledgment alone fails this test.

Run a no-write control first. Repeat each setter trial at least three times. Keep getter selection fixed during setter comparison so getter behavior does not confound setter results.

Abort immediately on unexpected haptics, alarm state changes, repeated disconnects, a regressing data range, new firmware errors, or loss of readable history. Do not reboot, trim, change configuration, enter DFU, or continue sweeping. Recovery means stopping the trial, reconnecting through the last hardware-proven Android path, checking native event/realtime time, and confirming new history growth before further research.

### Stage 3: compatibility matrix

Minimum matrix:

| Model | Firmware family | Getter 11 | Getter 147 | Setter 10 | Setter 146 | Response fixture | History growth |
|---|---|---|---|---|---|---|---|
| WHOOP 5 | current | pending | pending | partly evidenced | pending | missing | one positive report |
| MG | current | pending | pending | pending | pending | missing | pending |
| WHOOP 5/MG | older available revision | pending | pending | pending | pending | missing | pending |

Record “no response” only after an isolated trial with notifications active before the write and a fixed observation window.

Replicate across separate physical straps where available. One strap across several reconnects measures repeatability, not cross-device compatibility.

### Stage 4: implementation after proof

Only after fixtures exist:

- Centralize family-aware clock opcodes in `whoop-protocol`.
- Add dedicated family-aware GET and SET frame builders.
- Decode Gen5 clock responses at an exact, fixture-backed offset.
- Expose semantic result and clock value through FFI.
- Change Android logging to reflect sent, acknowledged, or verified states accurately.
- Keep normal Gen5 record timestamps in identity mode.
- Add regression fixtures for WHOOP 5 and MG.
- Reconcile `PROTOCOL.md`, `BLE_REVERSE_ENGINEERING.md`, and changelog wording.

## Acceptance criteria

No Gen5 clock implementation change is ready until:

- one real WHOOP 5 fixture exists;
- one real MG fixture exists;
- each decisive trial has a no-write control and three repeats;
- getter opcode and Unix offset are proven by repeated reads;
- setter latch is proven semantically;
- new history banking is verified after the setter;
- existing history remains unchanged;
- fixtures, preserved history, tool versions, and trial order are hashed and archived;
- Android unit tests pin family-specific outbound frames and responses;
- Android build and device smoke test pass;
- contradictory protocol documentation is reconciled.

## Immediate follow-up

No opcode change now. One low-risk code correction can follow separately: replace “clock synced” with wording that reports only what Android has actually confirmed. Keep that change independent from protocol research.

## Evidence map

Primary app paths:

- `android/app/src/main/java/com/noop/protocol/Enums.kt`
- `android/app/src/main/java/com/noop/ble/WhoopBleClient.kt`
- `android/app/src/main/java/com/noop/protocol/RustAdapter.kt`
- `docs/PROTOCOL.md`
- `docs/BLE_REVERSE_ENGINEERING.md`
- `CHANGELOG.md`

Cross-repository paths:

- `../whoop-rs/crates/whoop-protocol/src/command.rs`
- `../whoop-rs/crates/whoop-protocol/src/response.rs`
- `../whoop-rs/crates/whoop-client/src/client.rs`
- `../whoop-rs/crates/whoop-ffi/src/lib.rs`
- `../whoop-frankenstein/FRANKENSTEIN-STATUS.md`
- `../whoop-frankenstein/CHAINS.md`
- `../whoop-debug/last-scan.txt`
- `../whoop-debug/captures/noop-band-scan-whoop-debug-20260714-154036.txt`
- `../tools/maverick-disasm.py`

Saved non-Git artifact hashes:

- `whoop-debug/last-scan.txt`: `949DAA2450E7C01883360F19CDC4FF70AE74B576533B67CF76459B95DCD27972`
- `whoop-debug/captures/noop-band-scan-whoop-debug-20260714-154036.txt`: `9C78D3DEF37BEFE78A9D93609365A4C0C6BAD0828D0AA8A758759FA10350C79E`
- `tools/maverick-disasm.py`: `7011C91E0957A321B72580B6A7255F32C5D652790B95703492EDE5FF81DBF07A`

