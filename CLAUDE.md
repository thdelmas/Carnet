# Carnet — assistant notes

Scientific-instrument camera app for self-as-subject longitudinal video.
Single-purpose, single-shot, no editing. Part of the Bios ecosystem (writes
`recording_session_completed` events; reads vitals at record-start). Manifesto
register: instrument-opinionated, subject-sovereign, faithfulness over flattery,
metadata-load-bearing.

## Architecture (since v0.2 refactor)

- [CameraCaptureService](app/src/main/java/com/carnet/app/CameraCaptureService.kt)
  is a foreground service of type `camera|microphone` that owns the entire
  CameraX lifecycle — Preview, VideoCapture, OverlayEffect (HUD + screen-capture
  PiP), HudPainter, the active Recording, and sidecar/Bios writes.
- [ScreenCaptureService](app/src/main/java/com/carnet/app/ScreenCaptureService.kt)
  is a separate FGS of type `mediaProjection` that owns the
  `ScreenCaptureSource` (MediaProjection → VirtualDisplay → ImageReader →
  Bitmap). Its `activeSource` is read by the camera service's overlay draw.
- [MainActivity](app/src/main/java/com/carnet/app/MainActivity.kt) is a thin UI
  shell: binds to the camera service, hands over the PreviewView surface
  provider, pushes session-config + device-rotation updates, observes the
  recording-state flow.

**Why split:** starting MediaProjection in a process that holds the camera
makes Android's CameraService disconnect the camera client for ~6 s while it
re-evaluates resource ownership. With the camera FGS-covered up front,
MediaProjection's FGS doesn't trigger a re-handshake, and users can arm screen
capture mid-recording without freezing the file. See
[project_carnet_v02_mediaprojection.md](../../.claude/projects/-home-mia-Carnet/memory/project_carnet_v02_mediaprojection.md)
in memory for the full constraint set.

## Design system

**Compliance source of truth:** [/home/mia/Bios/docs/DESIGN_SYSTEM.md](../Bios/docs/DESIGN_SYSTEM.md)
and the token files in [/home/mia/Bios/docs/design-tokens/](../Bios/docs/design-tokens/).

When editing any layout, theme, or color in this app, the spec is binding:

- **Colors.** Reference `@color/role_*` from
  [colors.xml](app/src/main/res/values/colors.xml) — that file maps the
  canonical 14-token role set from `colors.yaml`. Identity-named `carnet_*`
  colors survive only for the HUD painter (hand-tuned palette where the colour
  is part of the instrument) and for legacy callers transitioning to roles.
  Carnet's identity hue is sage `#78A06E`; secondary borrows Bios teal
  `#009688` (kinship pattern from the spec — Carnet is the Bios-spine camera
  writer).
- **Typography.** Data-bearing text (UIDs, timestamps, file names, HUD values)
  uses `TextAppearance.Carnet.Instrument` / `.Small` / `.Large` from
  [themes.xml](app/src/main/res/values/themes.xml). Never inline
  `fontFamily=monospace` + `letterSpacing` — the styles enforce the spec's
  0.04 letter-spacing exactly. Never apply Instrument to body copy or
  human-written labels.
- **Spacing.** Only values from the canonical scale: `4 · 8 · 12 · 16 · 24 ·
  32 · 48 dp`. No 5/10/14/20/56 etc. Default screen-edge inset = 16dp; touch
  target floor = 48dp.
- **Theme.** Locked dark (`Theme.Material3.Dark.NoActionBar`). The HUD's
  contrast assumptions break in light mode; do not switch to DayNight.
- **Bios integration UI** (status pill + pending banner + Open-Bios button)
  is mandatory in every specialist's Settings screen. Carnet has no Settings
  screen today; when one lands, it must include the full
  `settings_bios_section` block from `components.yaml`.

## Bios integration

- Reads vitals at record-start via [BiosClient](app/src/main/java/com/carnet/app/BiosClient.kt)
  (`content://com.bios.app.health/vitals/current`). Null when Bios isn't
  installed — fail soft.
- Writes a `recording_session_completed` event on finalise via
  [BiosCompanionWriter](app/src/main/java/com/carnet/app/BiosCompanionWriter.kt)
  (`content://com.bios.app.companion/events`). Silently swallowed if Bios is
  unavailable.

## Output

`Movies/Carnet/V{N}_{LABEL}_{YYYY-MM-DD}_{UID}.mp4` + sidecar JSON
(`SidecarMetadata`) for each take.
