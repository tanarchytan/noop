# Rust whoop-ffi integration

The app decodes every WHOOP frame with the Rust codec from `whoop-rs` (`crates/whoop-ffi`, a `uniffi`
surface over the pure sans-IO codec). There is no Kotlin decoder left to fall back to. Bluetooth stays
native Kotlin (`com.noop.ble`); only frame bytes cross the FFI, nothing async or radio-bound.

`whoop-rs/docs/data-flow.md` is the strap-byte-to-pixel map. `docs/ALGORITHMS.md` tracks the Kotlin that
still carries maths it should not.

## What is committed here

- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libwhoop_ffi.so` — the prebuilt Rust core, one per shipped ABI.
- `app/src/main/java/uniffi/whoop_ffi/whoop_ffi.kt` — the generated uniffi Kotlin binding.
- `app/src/main/java/com/noop/protocol/RustCodec.kt` — the bridge (`decodeHistory` / `decodeLive` /
  `decodeResponse` plus the derived-metric entry points).
- `app/src/main/java/com/noop/protocol/RustAdapter.kt` — FFI→`StreamBatch` row mappers.
- `app/src/main/java/com/noop/analytics/RustScores.kt` — the single scoring seam: one thin adapter per
  engine, no arithmetic.
- `build.gradle.kts` — the JNA runtime dep, `arm64-v8a`/`x86_64` `abiFilters`, and the host-JVM
  `jna.library.path` the parity tests load.

## Regenerating after a whoop-rs change

**The `.so` and the binding must always regenerate together — a mismatched pair corrupts reads.** From
the `whoop-rs` checkout, with the Android NDK path set:

```bash
export ANDROID_NDK_HOME=".../Android/Sdk/ndk/<version>"
cargo ndk -t arm64-v8a -t x86_64 build --release -p whoop-ffi
cargo run -p whoop-ffi --features cli --bin uniffi-bindgen -- \
  generate --library target/aarch64-linux-android/release/libwhoop_ffi.so \
  --language kotlin --out-dir target/bindings
```

Then copy both `.so` into `jniLibs/<abi>/` and `whoop_ffi.kt` into
`app/src/main/java/uniffi/whoop_ffi/`.

Changing any FFI record needs all four steps. Skipping one leaves the app reading a stale layout.

## Verifying the border

The `Rust*ParityTest` suite decodes shared fixtures through both sides and asserts field equality, down to
raw bits where a stored column depends on it. It loads the **host** build of the library (`whoop_ffi.dll`
on Windows) over JNA from the sibling checkout's `target/release`, resolved via `WHOOP_RS_DIR` or
`../../whoop-rs`.

```bash
cargo build --release -p whoop-ffi        # in whoop-rs, first
./gradlew.bat testFullDebugUnitTest       # then here
```

**A missing library makes these tests self-skip, and a skip reads as a pass.** Confirm `skipped=0` in the
JUnit XML, not just a green build — several fixture-backed gates have silently stopped running before.
Fixture-dependent tests take `-Dnoop.hrvGoldFixtures=<dir>` / `-Dnoop.rrFixture=<file>` overrides;
without one they fall back to a local path under `whoop-data/harnesses/`.

## Known gaps

- **WHOOP 4.0 has no hardware parity run.** The v24/v25 legs decode real captured frames in tests, but no
  4.0 offload has ever been diffed end to end on a band.
- **CI never builds the cdylib**, so no always-running job loads Rust. The parity gates are a local
  pre-push step, not an enforced one.
- **`ppgWaveform` (LE-i16 BLOB)** agrees by construction but is not routed through the adapter and is
  untested end to end.
