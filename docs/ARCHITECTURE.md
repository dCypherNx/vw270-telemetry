# Architecture

```text
                         Android Auto host / VW270
                                  │
                     creates CarAppService Session
                                  │
                    ┌─────────────▼─────────────┐
                    │ CarHardwareCollector      │
                    │ CarInfo + CarSensors      │
                    └─────────────┬─────────────┘
                                  │
Phone ────────────────────────────┼────────────────────────────
                                  │
 CarConnection ────────┐          │
 UsageStats ───────────┤          │
 AA notifications ─────┤          │
 Phone SensorManager ──┤          │
 Fused/GNSS ───────────┤          │
 BT/System ────────────┤          │
 Shizuku read-only ────┤          │
                       ▼          ▼
                 ┌──────────────────────┐
                 │ TelemetryHub         │
                 │ cache + fused output │
                 └───────┬──────┬───────┘
                         │      │
                  SQLite │      │ MQTT QoS 0
                         ▼      ▼
                 telemetry.db  Home Assistant
```

## Lifecycle

`TelemetryService` is `START_STICKY` and starts at boot after the first user setup. It always keeps only the low-rate Android Auto monitors active.

When `CarConnection` becomes `PROJECTION` or `NATIVE` it enables a partial wake lock, all phone sensors and Fused Location/GNSS. Those expensive collectors stop as soon as the car connection disappears.

The vehicle-side collector is intentionally different: only an Android Auto host-created `CarAppService` session owns a valid `CarContext`, therefore `CarHardwareCollector` is created by `VwCarAppService` and dies with that session.

## Event envelope

Every sample uses one envelope with source, key, value, status, original source timestamp, reception timestamp and source-specific attributes.

## Shizuku boundary

The public PoC uses Shizuku only as a capability probe: binder alive, permission, server UID and server version. It deliberately does not expose a generic privileged shell executor. Future privileged probes should use narrowly scoped Binder calls and emit only the minimum diagnostic metadata required.

## Read-only boundary

There are no methods for vehicle control. The code calls only fetch/listener APIs in `CarInfo`/`CarSensors`. The Shizuku probe is read-only and currently does not execute shell commands. There is no package mutation or binder write to Android Auto.

## Fused continuity

`TelemetryHub` produces an operational `fused` source. A successful Android Auto raw speed is authoritative; after 2.5 seconds without a valid car speed, the source falls back to phone GNSS and marks the sample `estimated`. A successful car odometer becomes a persistent absolute anchor. GNSS trip deltas keep that odometer moving while the car channel is unavailable. Raw `car/*` events are never overwritten.
