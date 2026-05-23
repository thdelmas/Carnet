# Carnet

Scientific-instrument camera app for self-as-subject video documentation.

**Position in the ecosystem:** consumer + writer in the [Bios](https://github.com/thdelmas/Bios) ecosystem. Records longitudinal subject footage on the phone (Pixel 9a primary device), composites a live HUD overlay (subject / session / date / time / REC / UID), and writes session metadata + a snapshot of Bios's current vitals into a sidecar JSON next to the recording.

Inspired by the lab-notebook tradition (*carnet de laboratoire*): a personal scientific record, structured and unembellished.

## Scope (v0.1)

- Live camera preview with overlay (CameraX + canvas-drawn HUD on top).
- Record / stop. Output goes to `Movies/Carnet/V{N}_LABEL_YYYY-MM-DD_UID.mp4`.
- Sidecar JSON next to every recording: subject, session, label, UID, timestamps, duration, Bios snapshot (RHR / HRV / sleep_score / smoking count at recording start).
- Auto session numbering (next-V## by scanning `Movies/Carnet/`).
- One-tap workflow: pick session label → record → stop → file lands archived.

## Out of scope (v0.1)

- Filters, beauty, retouching — anti-features for this register.
- Editing / trimming — the raw recording is the artifact.
- Cloud sync — local-first per Bios manifesto.
- Social sharing — see [video-weekly-framework.md](https://github.com/mi4m/miam-knowledge-base/blob/main/docs/life/video-weekly-framework.md) for the publish-or-archive decision tree.

## Bios integration (day-1, not optional)

`uses-permission` declared:
- `com.bios.app.permission.READ_HEALTH` — read current vitals.
- `com.bios.app.permission.WRITE_COMPANION` — write a recording-event metric.

On record start: query `BiosHealthProvider` for the most recent `heart_rate`, `heart_rate_variability`, `resting_heart_rate`, `sleep_score`, `tobacco_use_event` (24-hour count). Snapshot lands in the sidecar JSON. No values mean the gauge wasn't live — captured faithfully as `absent`.

On record stop: write a `recording_session_completed` event back to Bios as a companion-write so the Bios timeline shows recording sessions inline with other health events.

## Build

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Signing for release tracked under the shared keystore at `~/.miam-secrets/` (see [Android release pipeline memory](https://github.com/mi4m/miam-knowledge-base/) for the standard pipeline).

## License

Private, all rights reserved (for now). License decision deferred until v1 ships.
