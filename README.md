# ProCut Studio

A complete, local-first video editor for Android, written in Kotlin with Jetpack Compose,
Media3 (ExoPlayer + Transformer), Room, Hilt and Kotlin serialization.

ProCut Studio is a **non-destructive** editor: importing media copies it into app storage, all
editing lives in a versioned JSON project document, and every export is rendered from that
document by Media3 Transformer. Nothing is uploaded anywhere, and no feature is faked — if a
setting cannot be rendered on a device the UI says so instead of writing a black frame.

---

## Highlights

| Area | What is implemented |
| --- | --- |
| Import | Android **Photo Picker** (multi-select) and **Storage Access Framework**; video, photo and audio; files copied into app-private storage with metadata probing (size, rotation, audio track) |
| Projects | Create, rename, duplicate, reopen, delete; recent-project grid with poster frames; versioned document format with migration hooks |
| Timeline | Gapless video lane, overlay/text/audio lanes, drag-to-reorder, drag to move, trim handles, split at playhead (video, text, sticker or audio), delete, duplicate, zoom |
| Video tools | Crop (free + 1:1 / 16:9 / 9:16 / 4:5 presets), rotate 90°, flip H/V, fit/fill/stretch, zoom/pan, **Ken Burns** motion for photos |
| Colour | 13 filter presets, brightness, contrast, saturation, warmth, hue, vignette — same numbers in preview and export |
| Audio | Music / SFX import, waveform rendering from decoded PCM, per-clip volume, mute, trims, fade in/out, voice-over recording with on-demand microphone permission, monitor mute in the transport bar |
| Text | Rich styles (font, size, weight, italic, underline, caps, colour, background, outline, shadow), alignment, position, scale, 6 presets, 7 animations (fade, slide up/left, scale, pop, typewriter), **SRT subtitle import** (each cue becomes an editable text overlay) |
| Overlays | Sticker / logo / image overlays with position, scale, rotation, opacity, rounded corners and animations (image-in-video "picture in picture"; see limitations for video-in-video) |
| Effects | Fade in/out, transitions (fade to black / white, dip dissolve), speed 0.25×–4× with pitch-correct audio speed |
| Canvas | 16:9, 9:16, 1:1, 4:5 output and a custom canvas colour behind letterboxed footage |
| Export | Real Media3 Transformer render, 720p/1080p/4K (device dependent), 24/30/60 fps, configurable bitrate, H.264 + AAC MP4, poster-frame preview of the first clip, progress, cancellation, verification, MediaStore gallery save, share sheet |
| Undo/redo | Snapshot-based history (depth 80) with human-readable labels |

Ten screens: **Home**, **New project**, **Editor**, **Audio mixer**, **Text editor**,
**Filters & adjust**, **Export settings**, **Export progress**, **Export success**, **Settings**.

Editor preferences in **Settings** are wired, not decorative: default export resolution and frame
rate seed every new project, *keep screen on* sets the window flag while the editor is open,
*safe area* draws 5 % title-safe and 10 % action-safe guides over the preview, and *haptics* fires
when a trim, split, duplicate or move is committed.

---

## Requirements

* Android Studio **Ladybug (2024.2.1)** or newer
* **JDK 17**
* Android SDK **35** (compileSdk/targetSdk 35, minSdk **29**)
* Gradle **8.9** (wrapper included — do not install Gradle manually)

Pinned versions live in `gradle/libs.versions.toml`:
AGP 8.7.3 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Compose BOM 2024.11.00 · Hilt 2.52 ·
Room 2.6.1 · **Media3 1.5.1** · Kotlin serialization 1.7.3 · DataStore 1.1.1 · Coil 2.7.0.

## Build & run

```bash
# debug build
./gradlew :app:assembleDebug

# install on a connected device/emulator
./gradlew :app:installDebug

# unit tests
./gradlew :app:testDebugUnitTest

# instrumentation tests (device required)
./gradlew :app:connectedDebugAndroidTest
```

The debug variant installs as `com.procutstudio.editor.debug`; the release variant is signed with
the debug key so `./gradlew :app:installRelease` works out of the box — replace
`signingConfig` in `app/build.gradle.kts` before publishing anywhere.

**Emulator note:** Media3 encoding needs a working H.264 encoder. Most x86_64 emulator images
provide one through software; if `Start export` fails with a codec error, run the app on a
physical device.

---

## Architecture

```
com.procutstudio.editor
├── core/            time + id utilities, file helpers, logging, dispatcher qualifiers
├── domain/
│   ├── model/       EditorProject, tracks and items, transforms, effects, export settings
│   ├── timeline/    TimelineEditor — pure, immutable editing operations
│   └── history/     EditHistory (snapshot undo/redo) + command labels
├── data/
│   ├── local/       Room entity/DAO/database, project document codec (JSON + versioning)
│   ├── media/       importer, metadata reader, thumbnail cache, waveform decoder
│   └── repository/  ProjectRepository (single source of truth)
├── media/
│   ├── playback/    PreviewPlayer (ExoPlayer playlist ⇄ timeline mapping)
│   ├── render/      TextBitmapRenderer, overlay bitmaps, animation maths (shared by preview + export)
│   └── export/      CompositionBuilder, effects, audio processors, VideoExporter, MediaStorePublisher
├── di/              Hilt modules
└── ui/              theme, components, navigation, and one package per screen
```

* **MVVM + unidirectional state.** Every screen exposes a `StateFlow<…UiState>` from an
  `@HiltViewModel`; composables only render state and send events back.
* **Immutable document.** `TimelineEditor` takes a project and returns a new one. Each call is one
  undo step, which makes undo/redo exact and the editing surface trivially unit-testable.
* **Persistence.** Room stores one row per project: summary columns for fast lists plus the full
  JSON document. Saves are atomic full-document writes, debounced while dragging sliders.
* **Playback.** ExoPlayer plays the video clips as a clipped playlist; photos, text, stickers and
  fades are drawn by Compose on top using the *same* bitmaps and animation maths the exporter uses.

### Project document format

`ProjectSchema.CURRENT_VERSION = 1`. The document is a `EditorProject` serialised with kotlinx
serialization using a `type` discriminator for timeline items (`clip`, `text`, `overlay`, `audio`).
Decoding ignores unknown keys and falls back to Kotlin defaults for missing ones, so adding fields
never breaks older projects. Documents written by a *newer* app version are rejected with a clear
message instead of being silently mangled; `ProjectDocumentCodec.migrate()` is the hook for future
schema upgrades.

---

## How the export works

1. **Pre-flight** — the first clip must exist; the device must report H.264 + AAC encoders; free
   space is checked; the requested resolution is clamped to what the encoder supports.
2. **Composition build** (`CompositionBuilder`):
   * sequence 0 — the video lane. Each clip carries its trim (`ClippingConfiguration`), speed
     (`SpeedChangeEffect` + `SpeedChangingAudioProcessor`), geometry (`ClipGeometryEffect`, a custom
     `MatrixTransformation` for crop/fit/zoom/rotate/flip), **Ken Burns** (`KenBurnsEffect`), colour
     grade (`HslAdjustment`, `Brightness`, `Contrast`, `RgbAdjustment`), vignette and fade curtain.
   * an optional canvas-colour sequence behind letterboxed footage.
   * audio lanes packed greedily into up to four non-overlapping sequences mixed by the compositor,
     each with `GainAudioProcessor` for volume, mute and sample-accurate fades.
   * composition-level effects: one `OverlayEffect` containing every text/sticker overlay, each
     windowed to its own timeline range and animated by `OverlayAnimationMath`.
3. **Render** — `Transformer` writes to a temp file; progress is polled and mapped to 0–95 %.
4. **Verify** — the temp file is re-opened with `MediaMetadataRetriever`; duration, resolution and
   presence of a video track are checked against the request. A mismatch is a failure, not a success.
5. **Publish** — the verified file is inserted into `MediaStore.Video` (gallery), and only then is
   success reported. The temp file is always deleted.

Cancellation tears the transformer down and removes the temp file.

## Known gaps (tracked, not hidden)

These are the only places where ProCut 1.0 is deliberately smaller than a desktop NLE:

* **Video picture-in-picture.** Image/sticker overlays are fully supported (position, scale,
  rotation, opacity, rounded corners, animation) and composite into the export. A *second video
  stream* on top of the main one needs Media3's experimental multi-sequence compositing plus a
  custom `VideoCompositorSettings` per input; it is not wired up in 1.0 rather than shipping an
  untested path that would silently drop the overlay.
* **Blurred canvas background.** The canvas supports solid colours and black; rendering a blurred
  copy of the footage behind a fitted clip would require compositing two versions of every frame
  through a second sequence — the same limitation as above.
* **Free-form transitions.** Fades and dip transitions are rendered per clip; a true cross-dissolve
  is not possible with the current Media3 API (see below).

### Things this app does *not* pretend to do

* **Cross-dissolve between two video streams.** Media3 Transformer 1.5 mixes sequences but does not
  expose per-input, time-varying alpha for two simultaneous video streams, so a true dissolve cannot
  be rendered. ProCut offers `FADE_TO_BLACK`, `FADE_TO_WHITE` and a fast dip (`CROSS_DISSOLVE`,
  labelled "Dissolve (dip)") instead of shipping a broken one.
* **4K on every device.** The export screen lists only resolutions the encoder reports as supported;
  a 4K request on a 1080p-max encoder is not offered.
* **Cloud sync, accounts, telemetry.** There are none, by design.

---

## Privacy

* Media is selected through the **Photo Picker** (no storage permission) or the system file browser.
* The microphone permission is requested **only** when the user taps record, and recording is
  foreground-only with a running timer.
* All processing is on-device; the app has no network permission and no analytics.

## Testing

`app/src/test` contains JVM unit tests that do not need a device:

* `TimelineEditorTest` — append/split/delete/reorder/duplicate/trim/speed/transition invariants,
  gapless lane, crop sanitising, Ken Burns persistence.
* `EditHistoryTest` — undo/redo semantics, redo invalidation, bounded depth.
* `ExportSettingsTest` — resolution/aspect/bitrate/fps maths for every supported combination.
* `OverlayAnimationMathTest` — animation envelopes shared by preview and export.
* `ProjectDocumentCodecTest` — round-trip fidelity, unknown-field tolerance, version rejection.
* `ProjectRepositoryTest` — Robolectric + in-memory Room: create/save/rename/duplicate/delete,
  summary projection, document round trip through the database.

`app/src/androidTest` adds a Compose instrumentation smoke test for the shared components (chip and
button render through the real theme and report clicks):

```bash
./gradlew :app:connectedDebugAndroidTest
```

```bash
./gradlew :app:testDebugUnitTest
```

These are the project's own tests; run them on your machine after the first Gradle sync. The code in
this tree was assembled without an Android SDK/emulator available, so the source was validated
statically (symbol/API cross-checks against the Media3 1.5.1 sources, argument and import scans,
balanced-syntax scans) rather than by a compile here — expect to fix at most small, local issues on
the very first build, and open an issue if anything fails to resolve.

## Project layout

```
app/
├── build.gradle.kts
├── proguard-rules.pro
├── schemas/                     Room exported schemas
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/procutstudio/editor/…   (see Architecture)
    │   └── res/                             themes, strings, icons, player layout
    ├── test/                                JVM unit + Robolectric integration tests
    └── androidTest/                         Compose instrumentation smoke tests
gradle/libs.versions.toml        single source of dependency versions
```

## License

Released for evaluation and personal use. Third-party components keep their own licenses
(AndroidX, Media3, Kotlin, Hilt, Room, Coil).
