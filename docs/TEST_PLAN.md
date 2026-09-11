# VW270 field test plan

## A. Baseline without touching the HU

1. Phone collector running; AA disconnected.
2. Connect Android Auto and do not touch the head unit for at least 60 seconds.
3. Confirm `aa/connection_type` becomes `projection`.
4. Check whether `aa/session_created` appears.
5. Record a Shizuku capability snapshot if available.
6. Export JSONL.

Expected decisive split: if a session exists, inspect all `car/*` statuses; if not, phone-side collection works but Android Auto did not instantiate our car app.

## B. Controlled one-touch comparison

Only after A is exported, open **VW270 Probe** once in Android Auto, wait 30 seconds and export another JSONL. Compare the first timestamps of `aa/screen_created`, `car/session`, `car/hardware_manager`, `car/probe_*` and each real `car/*` sample.

A clean transition immediately after opening the app proves the lifecycle barrier.

## C. Capability matrix

Record the eventual status for model, energy, raw/display speed, odometer, toll, EV status, vehicle accelerometer, gyroscope, compass and hardware location. Do not treat `UNIMPLEMENTED` as an app bug; it means the host/OEM did not implement that public property.

## D. Companion comparison

On a later run, enable the equivalent Home Assistant Companion car sensors and compare timestamps/gaps. The dedicated collector should distinguish no projection, no Car App session, permission failure, OEM/host unavailable/unimplemented, stale listener or HA ingestion failure.
