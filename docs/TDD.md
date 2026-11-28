# VEdit — Technical Design Document (TDD)

**Project:** VEdit  
**Platform:** Android Native  
**Repository:** https://github.com/rudicrash4/VEdit  
**Status:** Initial technical direction; validate against current library versions before implementation.

---

## 1. Technical Goals

- Build a maintainable Android video editor.
- Reuse mature, compatible open-source components where practical.
- Keep the editor's project model and timeline logic under VEdit's control.
- Ensure preview and export share the same edit semantics.
- Support local projects and media.
- Build and test using GitHub Actions.

## 2. Proposed Technology Stack

| Area | Proposed technology | Notes |
|---|---|---|
| Language | Kotlin | Primary application language |
| UI | Jetpack Compose | Main UI framework |
| Build | Gradle Kotlin DSL | Use wrapper committed to repository |
| Playback | AndroidX Media3 ExoPlayer | Preview playback; validate effect integration |
| Export | AndroidX Media3 Transformer | Candidate export pipeline; validate composition/effect support |
| Platform codecs | Android MediaCodec | Hardware codec path where supported |
| Graphics/effects | OpenGL ES or supported Android graphics APIs | Choose based on preview/export parity and maintenance |
| Persistence | Room + versioned JSON/project serialization | Room for app metadata; project schema must be versioned |
| Background work | Kotlin coroutines; WorkManager where appropriate | Do not force long export into a mechanism that cannot meet foreground execution requirements |
| Media access | Android Photo Picker, SAF, MediaStore | Persist URI permissions where possible |
| Tests | JUnit, AndroidX test, Compose UI test as appropriate | Unit and instrumentation tests |

Use current stable, mutually compatible dependency versions at implementation time. Do not copy stale version numbers from tutorials.

## 3. Open-Source Audit Policy

Claude must inspect candidate repositories before adopting them. For each dependency, record:
- Repository URL.
- License and attribution obligations.
- Last meaningful maintenance/activity.
- Android compatibility and minimum API requirements.
- Supported codecs/formats.
- Known limitations.
- Binary size and native ABI impact.
- Security and supply-chain considerations.
- Whether it is a library, sample, or complete application.
- Whether code can legally and technically be reused.

### Candidate components

**AndroidX Media3**
- Official repository: https://github.com/androidx/media
- Official editing guide: https://developer.android.com/media/implement/editing-app
- Candidate for playback, Transformer export, media composition, and effects.
- Validate current API capabilities for multi-track composition, speed changes, audio mixing, and custom effects before committing to architecture.

**FFmpeg**
- Official repository: https://github.com/FFmpeg/FFmpeg
- Consider only for media operations not adequately covered by Android platform APIs/Media3.
- Review LGPL/GPL configuration, enabled codecs, distribution obligations, native packaging, ABI support, and binary size before integration.
- Do not assume the original FFmpegKit is maintained or suitable. Do not adopt abandoned wrappers without a documented justification and license review.

**OpenCut**
- Repository: https://github.com/OpenCut-app/OpenCut
- Treat as a research/reference candidate, not an assumed drop-in Android engine.
- Inspect its current architecture, supported platforms, license, and code maturity.
- Reuse only components that are compatible and legally appropriate. Do not base VEdit on it merely because it is a video editor project.

No third-party dependency should be added without recording its purpose and license in a dependency/license inventory.

## 4. Architecture

Use a modular, layered architecture. Suggested Gradle modules can be adjusted after repository inspection.

```text
VEdit
├── app
├── core
│   ├── model
│   ├── project
│   ├── timeline
│   ├── media
│   ├── playback
│   ├── render
│   ├── export
│   ├── effects
│   └── common
└── feature
    ├── projects
    ├── editor
    ├── mediaimport
    ├── timeline
    ├── inspector
    ├── audio
    └── export
```

Do not create empty modules solely to match this diagram. Start with the smallest useful structure and split modules when boundaries are clear.

### Responsibilities

- **app:** application entry point, dependency wiring, navigation.
- **core:model:** immutable domain models and shared types.
- **core:project:** serialization, persistence, schema migration, recovery.
- **core:timeline:** timebase, tracks, clips, edit commands, undo/redo.
- **core:media:** URI access, metadata, thumbnails, source validation.
- **core:playback:** preview player and synchronization with timeline.
- **core:render:** transform/effect descriptions and preview rendering.
- **core:export:** export graph, encoding, progress, cancellation, output.
- **feature modules:** UI and user workflows.

## 5. Domain Model and Time Representation

Use a single explicit time model. Prefer integer microseconds or a rational frame/timebase representation; avoid floating-point seconds as the authoritative stored timeline value.

Core concepts:
- `Project`
- `Track`
- `Clip`
- `MediaSource`
- `ClipTransform`
- `SpeedCurve`
- `Keyframe`
- `Effect`
- `AudioSettings`
- `TextOverlay`
- `ExportProfile`

Every project must include a schema version.

A clip should distinguish:
- Source media URI/reference.
- Source in-point and out-point.
- Timeline start.
- Timeline duration after speed mapping.
- Track/layer.
- Transform and opacity.
- Speed mapping.
- Effects and keyframes.

Define and test all time conversions. Avoid rounding drift when moving between frames, microseconds, and source timestamps.

## 6. Timeline Engine

The timeline engine should be independent from Compose UI.

Responsibilities:
- Maintain tracks and clips.
- Calculate clip positions and project duration.
- Validate edits and prevent invalid negative durations.
- Convert timeline time to source-media time.
- Provide commands for split, trim, move, duplicate, delete, and reorder.
- Support undo/redo through reversible commands or state snapshots.
- Notify UI through observable state without coupling domain logic to UI widgets.

### 6.1 Editing Semantics

Define exact behavior for:
- Splitting a clip at the playhead.
- Trimming either edge.
- Moving a clip across tracks.
- Overlap and transition rules.
- Ripple versus non-ripple edits.
- Gaps.
- Track ordering and compositing.

Do not leave these semantics implicit. Add unit tests for boundary cases.

## 7. Speed and Time Remapping

Normal speed and speed ramping require a shared time-remapping model.

Represent a speed curve as ordered control points in clip-local time. Define:
- Valid speed range.
- Interpolation method.
- Mapping from timeline time to source time.
- Mapping from source duration to timeline duration.
- Behavior at clip boundaries.
- Audio pitch/time-stretch policy.

For a varying speed curve, timeline duration is not simply source duration divided by one speed value. Implement and test the mathematical integration/time mapping correctly.

**Critical feasibility gate:** Before designing the final export pipeline around speed ramp, build a small proof of concept using the selected Media3/platform APIs. Confirm that the chosen API can produce the required variable-speed output. If not, evaluate a custom MediaCodec/GL pipeline or a properly licensed FFmpeg integration. Document trade-offs before committing.

Preview and export must use the same speed-curve definition. If preview approximates a feature that export cannot reproduce, label it as unsupported rather than silently producing different output.

## 8. Keyframe Engine

Store keyframes by:
- Target property.
- Clip-local time.
- Value.
- Interpolation type.
- Optional Bezier handles.

Implement linear interpolation first. Add easing and custom Bezier only after deterministic tests pass.

Keyframe evaluation must be independent of UI and reusable by preview/export.

## 9. Preview Pipeline

- Use Media3 ExoPlayer for source playback where suitable.
- Synchronize preview position with timeline playhead.
- Seek accurately enough for editing; document codec/keyframe limitations.
- Apply transforms and supported effects in preview.
- Avoid decoding entire files into memory.
- Manage player lifecycle correctly.
- Handle source URI permission loss and decoder failures.
- Provide a clear fallback/error state.

For multi-layer compositing, validate the chosen rendering approach early. Media3 playback alone is not a complete nonlinear multi-track editor compositor.

## 10. Rendering and Effects

Define effects as serializable parameter objects, not UI-specific objects.

Potential pipeline:
1. Decode source frame.
2. Apply source orientation/transform.
3. Apply clip transform/keyframes.
4. Apply color/effect chain.
5. Composite tracks in layer order.
6. Render text/image overlays.
7. Send frames to preview or export output.

This is a target architecture, not a claim that Media3 automatically provides every stage. Prototype and validate preview/export parity before expanding effects.

## 11. Audio Pipeline

- Maintain timeline audio positions and trim ranges.
- Mix audio sources according to track order and volume.
- Apply fades.
- Define audio behavior under clip speed changes.
- Keep audio/video synchronization.
- Avoid clipping where practical.
- Test mixed output with known sample files.

Use Media3/platform support where adequate. Add FFmpeg or custom processing only when necessary and after license review.

## 12. Export Pipeline

Export should be a separate service/state machine:
- Validate project.
- Resolve all media URIs.
- Build render/export plan.
- Validate requested output profile against device capabilities.
- Render/encode.
- Report progress.
- Handle cancellation and failure.
- Finalize output and register it with MediaStore/SAF.
- Clean temporary files safely.

Export states:
`Idle -> Preparing -> Exporting -> Finalizing -> Completed`
and failure/cancel terminal states.

Requirements:
- Never report success until the output file exists and can be opened.
- Preserve orientation and expected dimensions.
- Verify duration and audio/video streams.
- Ensure temporary output is cleaned after failure.
- Avoid blocking the main thread.
- Handle app lifecycle and foreground execution requirements appropriately.

## 13. Project Persistence and Media References

- Use Room for project index/metadata if useful.
- Store full project document as versioned JSON or another documented stable format.
- Persist URI permissions where supported.
- Never assume a temporary picker URI remains accessible forever.
- Store generated thumbnails/proxies in app-managed cache/storage.
- Original source files must remain untouched.
- Implement schema migration tests.
- Autosave with debouncing and atomic writes where possible.

## 14. Performance and Memory

- Use thumbnail extraction in background.
- Cache thumbnails and waveform data.
- Limit concurrent decoders.
- Avoid retaining full-resolution bitmaps unnecessarily.
- Use proxy media as a later optimization.
- Monitor memory during long timelines.
- Test on at least one real, mid-range Android device.
- Show lower-preview-quality options if required for performance, without lowering final export quality unexpectedly.

## 15. Testing Strategy

### Unit tests
- Timebase conversions.
- Timeline duration.
- Split/trim/move semantics.
- Speed curve mapping.
- Keyframe interpolation.
- Project serialization and migration.
- Undo/redo.

### Instrumentation/integration tests
- Media import.
- Playback and seeking.
- Export sample project.
- Audio/video synchronization.
- URI permission persistence.
- Process restart and project recovery.

### Export verification
For each test export:
- Confirm file exists and is non-empty.
- Probe/open it with supported Android tooling.
- Verify video dimensions, duration, frame rate, and audio stream when expected.
- Play the file in an external player.
- Compare expected edits with actual output.

## 16. GitHub Actions / CI

Create workflows under `.github/workflows/`.

Recommended workflows:
- `android-ci.yml`: run on push and pull request; checkout, configure JDK, set up Android SDK as needed, run lint/tests, assemble debug APK, upload APK artifact.
- `android-release.yml`: manually or tag-triggered release build. Signing must use GitHub Secrets, never committed files.
- Optional dependency/license audit workflow.

CI requirements:
- Use Gradle wrapper committed to repository.
- Pin actions to trusted versions/SHAs where practical.
- Use least-privilege permissions.
- Cache Gradle safely.
- Do not expose secrets in logs.
- Upload debug APK as an artifact after successful build.
- Clearly distinguish debug APK from signed release APK.
- Build failure must be reported honestly.
- Do not run a release workflow with missing signing configuration.

Example build commands should be confirmed against actual Gradle tasks:
- `./gradlew test`
- `./gradlew lint`
- `./gradlew assembleDebug`

## 17. Security and Privacy

- No account or backend is required.
- No analytics or telemetry unless explicitly approved later.
- Do not upload user media to GitHub or external services.
- Do not commit keystores, passwords, tokens, or private signing material.
- Use Android scoped storage and URI permissions.
- Keep dependencies minimal and audited.

## 18. Definition of Done

A feature is done only when:
1. It has a real implementation, not just a UI control.
2. Its data persists in the project when applicable.
3. Preview behavior is implemented.
4. Export behavior is implemented or explicitly marked unsupported.
5. Unit/integration tests cover important behavior.
6. It does not break existing functionality.
7. The app builds successfully through GitHub Actions.
8. Documentation is updated.
9. Changes are committed and pushed to the intended repository.
10. The workflow result is checked and accurately reported.

## 19. Addendum Phase 0B (temuan API, belum final)
- `SpeedProvider` Media3 bersifat piecewise-constant dan berbasis waktu sumber; kurva VEdit harus didiskretisasi (`SpeedCurve.toSegments`). Mulus/tidaknya hasil export belum terbukti di perangkat.
- Multi-track = beberapa `EditedMediaItemSequence` dalam `Composition`; audio yang tumpang tindih dicampur; layout video via `VideoCompositorSettings` (`androidx.media3.common`).
- `CompositionPlayer` masih `@ExperimentalApi`; paritas preview/export wajib diverifikasi per fitur.
- Detail: `docs/PHASE0B_REPORT.md`.
- Update setelah uji CPH2591: `CompositionPlayer` mewajibkan `EditedMediaItem.setDurationUs(durasi sumber tanpa clipping)`; tanpa itu `checkState` tanpa pesan melempar `IllegalStateException`. Aturan arsitektur: SATU builder `Composition` dipakai preview dan export, dan setiap `EditedMediaItem` selalu membawa durationUs.
- Verifikasi export wajib terukur, bukan hanya durasi: RMS audio (volume/mixing), pixel diff (PiP), timestamp frame (ramp). Logika verifikasi murni ada di `poc/Analysis.kt` dan diuji unit.
- Phase 1 (keputusan): satu modul `:app` berpaket `domain`/`data`/`ui`; persistensi proyek = file JSON berversi (schema 1) dengan tulis atomik + cadangan, Room belum dipakai. Detail di `docs/PHASE1_REPORT.md`.

## 20. Addendum Phase 1.3: arsitektur preview
Paket `preview` (Android) + `domain.PreviewPlan` (murni, teruji JVM).
- `CompositionFactory`: Project ke `Composition` Media3 (sama dengan jalur export). Track video = satu `EditedMediaItemSequence` (klip + `addGap`, tanpa gap akhir); track audio = sequence audio-only terpisah. Semua item wajib `durationUs` (akar galat `IllegalStateException` Phase 0B). Efek video: Crop (NDC), `ScaleAndRotateTransformation` (sudut dinegasikan; model searah jarum jam), `Presentation` 1280x720 scale-to-fit. Volume: `ChannelMixingAudioProcessor`; kecepatan: `SpeedProvider` konstan.
- `PreviewEngine` (antarmuka) dengan `CompositionEngine` (utama) dan `ExoEngine` (fallback ExoPlayer playlist V1; tanpa gap/audio tambahan/efek).
- `PreviewController`: state Compose, engine diinjeksi lewat factory (unit test memakai engine palsu); seek tertunda sampai siap; galat pertama berpindah ke fallback, kedua menjadi `error`; callback setelah `release` diabaikan.
- `EditorScreen`: `PreviewPanel` (SurfaceView 16:9); rebuild debounce 300 ms pada `PreviewPlan.structureChanged`; polling playhead 100 ms; jeda di `onStop`, release saat dispose.
- Keputusan: rebuild membuat player baru (sederhana, benar; kerugian: jeda singkat dan pemutaran berhenti). Optimasi edit inkremental ditunda sampai ada ukuran perangkat.

## 21. Addendum v0.4.0: export dan UI adaptif
- `export.ExportController`: Transformer + komposisi `CompositionFactory` (satu definisi untuk preview dan export). Status `ExportState` (Idle/Running/Done/Failed). Output ke `getExternalFilesDir(Movies)` lalu MediaStore `Movies/VEdit` (API 29+). Pemanggilan dari main thread. Export berjalan selama aplikasi di foreground; foreground service untuk export panjang BELUM ada (risiko dihentikan saat app di background).
- Resolusi keluaran masih tetap 1280x720 (`CompositionFactory.OUT_*`); pilihan resolusi belum ada.
- UI adaptif: `EditorScreen` memakai `BoxWithConstraints`: `wide = maxWidth >= 640dp && maxWidth > maxHeight`. Dua panel pada layar lebar. `PreviewPanel` membatasi lebar video agar tinggi <= `maxVideoHeight` (55%/40% tinggi). Insets ditangani sekali di root (`safeDrawingPadding`).

## 22. Addendum v0.5.0: kanvas, kualitas, dan optimasi preview
- Tiga konsep terpisah: (1) rasio kanvas = `Project.canvas.aspect` (disimpan); (2) resolusi output = `OutputQuality` (sisi pendek; bukan bagian proyek); (3) ukuran tampilan preview = urusan UI (`PreviewPanel`, dibatasi tinggi layar). Ukuran frame = `CanvasSpec.size(aspect, shortSide)`, dibulatkan ke genap. Tabel: 720p 1280x720 / 720x1280 / 720x720 / 720x900; 1080p 1920x1080 / 1080x1920 / 1080x1080 / 1080x1350 (belum aktif).
- Penempatan media: `FitMode.FIT` -> `Presentation.LAYOUT_SCALE_TO_FIT`; `FILL` -> `LAYOUT_SCALE_TO_FIT_WITH_CROP`. Urutan efek per klip: Crop -> Rotasi -> Presentation, sehingga crop/rotasi bekerja pada semua rasio dan rasio sumber tidak diubah (hanya diskalakan seragam).
- Preview vs export memakai SATU `CompositionFactory.build` dengan parameter kualitas; rasio/fit selalu dari proyek. Preview = PREVIEW (480), export = P720. Kualitas export tidak diturunkan.
- Schema proyek 2: kunci `canvas`; migrasi 1->2 identitas (default 16:9 FIT dari deserializer). Tes memakai dokumen v1 nyata.
- `PreviewController.update` tidak membangun ulang pemutar bila `structureChanged` salah (struktur = track, sumber, kanvas). Metrik `lastBuildMs`/`buildCount` diukur di perangkat.
- Engine preview tetap `CompositionPlayer` (tidak diganti; belum ada bukti teknis perlu).


## UI presentation update — PR #2 (target v0.5.1)
- Implementasi tetap Jetpack Compose dalam modul `:app`; tidak menambah dependency atau mengubah arsitektur domain.
- Warna dark studio diterapkan melalui `MaterialTheme`/`darkColorScheme`; komponen preview tetap memakai `PreviewController` dan `SurfaceView` yang sudah ada.
- Panel hanya membungkus composable yang sudah ada. Semua callback editing, project persistence, playback, dan export dipertahankan.
- Perubahan layout wajib melewati compile, unit test, lint, assembleDebug, lalu smoke test pada portrait phone dan landscape.

## 23. Addendum v0.6.0: Hybrid Rust Engine
- Pemilik state proyek/riwayat: `EditorEngine`. Implementasi utama `RustEngine` (handle sesi native); `KotlinEngine` = cadangan otomatis dan pembanding. UI hanya memegang snapshot; semua mutasi lewat `EditorEngine.apply(EditOp, nowMs)` (tidak ada dua timeline yang berubah independen).
- Core `vedit-core` (Rust, tanpa dependency): `model`, `ops` (port persis `TimelineEngine`, termasuk `Math.round`-setengah-naik dan teks galat), `history` (snapshot, batas 100), `canvas` (ukuran per rasio/kualitas, export 1080p ditolak), `plan` (urutan klip/celah, locate, structure_changed), `json`, `engine` (sesi + kontrak JSON).
- Bridge JNI: lihat `BRIDGE_DECISION.md`. Pustaka `libvedit_bridge.so` (arm64-v8a, armeabi-v7a). Dimuat lewat `System.loadLibrary`; gagal = Kotlin.
- Persistensi tetap di Kotlin (`ProjectCodec`/`ProjectStore`): Kotlin memigrasikan dokumen ke schema terkini sebelum diberikan ke Rust; Rust menolak schema lain. Schema proyek tidak berubah (2).
- Keputusan media: Media3 (`CompositionPlayer`, `Transformer`, efek) dipertahankan untuk preview dan export karena terbukti di perangkat pengguna dan tidak ada bukti keunggulan alternatif; Rust merencanakan, Media3 mengeksekusi. `CompositionFactory` masih memakai `PreviewPlan`/`CanvasSpec` Kotlin (paritas Rust teruji; peralihan = tahap berikut).
- Pengujian: tes Rust (core + bridge), `DifferentialEngineTest` (40 seed x 120 operasi acak, membandingkan proyek hasil, pesan galat, undo/redo antara Rust dan Kotlin), smoke test muat pustaka host di JVM. Semua di JVM host, bukan perangkat.

## 24. Addendum v0.7.0: Preview Engine V2
Lihat `PREVIEW_V2_AUDIT.md` dan `PREVIEW_V2_DESIGN.md`. Ringkas: ExoPlayer + pengendali timeline sendiri (Rust menjadwal/menghitung geometri, Kotlin mengorkestrasi, ExoPlayer mendekode); MediaCodec langsung ditolak (kompleksitas tanpa bukti). Pengujian: 14 skenario regresi di JVM dengan slot palsu dan jam virtual (bukan bukti perilaku perangkat), paritas Rust/Kotlin, tes Rust `playback`. Verifikasi perangkat menunggu pengguna.
