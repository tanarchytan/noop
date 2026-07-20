# Android Strap Clock Correlation Design

## Goal

Connect decoded WHOOP 4 `GET_CLOCK` replies to Android backfill timestamp handling without changing WHOOP 5/MG wire behavior or valid type-47 timestamps.

## Root cause

`RustAdapter` exposes a valid WHOOP 4 clock reply as `parsed["clock"]`. `WhoopBleClient` routes the response but never stores it. `Backfiller` therefore keeps its construction-time identity `ClockRef` for every connection.

## Design

Add one small, thread-safe `ClockReference` object shared by `WhoopBleClient` and `Backfiller`. It owns the current `(device, wall)` pair, accepts only successful CRC-valid parsed clock replies, keeps the first valid reply from each connection, and resets to a fresh identity pair during disconnect teardown.

`WhoopBleClient` feeds every command response into this object. `Backfiller` reads its current pair immediately before decoding a chunk. Existing type-47 behavior remains unchanged for healthy clocks because those records retain their own Unix timestamps and gross-offset correction stays inactive.

Because Android requests `GET_CLOCK` after `SET_CLOCK`, that live pair cannot safely re-date records banked before the set. Backfill therefore uses the pair only for device-clock fallback streams and explicitly preserves recorded type-47 and event Unix timestamps. Stale callbacks from a replaced `BluetoothGatt` are rejected before routing, preventing an old connection reply from winning the new connection's first-reply latch.

## WHOOP 5/MG boundary

No WHOOP 5/MG opcodes, frame builders, or response layouts change. Repository evidence conflicts: NOOP currently uses low clock opcodes `10/11`, while protocol research names Maverick opcodes `146/147`; captured `147` probes have also returned silence. Changing that wire path without a confirmed response fixture could prevent `SET_CLOCK` from latching and stop history banking.

WHOOP 5/MG therefore retains its identity clock reference. Its realtime and historical records already carry Unix timestamps, so correlation is unnecessary there.

## Safety

- Reject failed parses and bad CRCs.
- Ignore responses without a numeric clock.
- Keep the first valid reply per connection.
- Reset state between connections.
- Reject callbacks from replaced GATT instances.
- Preserve existing historical plausibility gates.
- Preserve existing live timestamp anchoring.

## Verification

Unit tests cover identity fallback, successful correlation, rejection paths, duplicate replies, and reset behavior. Android unit tests, lint, and debug assembly provide regression coverage.
