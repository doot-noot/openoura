# OpenRing (Android) — v0.1

Cloud-free Android client for Oura Ring 3/4/5, built on the shared Rust core from
[open_health](https://github.com/Th0rgal/open_health) (`oura-core`, rev 8f616ec)
and [open_oura](https://github.com/Th0rgal/open_oura). GrapheneOS-friendly: no
Play Services, no location permission, no network permission at all.

## What v0.1 does
- Pair a **factory-reset** ring on the phone (mints a key; back it up!)
- Sync history events into a local SQLite DB (`files/oura.db`)
- Show the core's summary JSON (sleep, vitals, activity panels)

> **Status: untested on hardware.** Pairing requires a factory-reset ring and
> mints a new auth key — after that the official Oura app can't talk to the ring
> until another reset. Not affiliated with Oura.

## Rebuild
```sh
# 1. Rust core for Android (in open_health checkout)
cargo ndk -t arm64-v8a -P 26 build --release -p oura-core
cp target/aarch64-linux-android/release/liboura_core.so app/src/main/jniLibs/arm64-v8a/
# 2. Kotlin bindings (needs an UNSTRIPPED host build)
CARGO_PROFILE_RELEASE_STRIP=false CARGO_PROFILE_RELEASE_LTO=false CARGO_TARGET_DIR=target/bindgen \
  cargo build --release -p oura-core --lib
target/release/uniffi-bindgen generate --library target/bindgen/release/liboura_core.so \
  --language kotlin --out-dir app/src/main/java
python3 scripts/patch_bindings.py app/src/main/java/uniffi/oura_core/oura_core.kt
# 3. APK
./gradlew assembleDebug
```
