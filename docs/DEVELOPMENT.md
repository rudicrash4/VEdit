# VEdit — Development Guide

**Repository:** https://github.com/rudicrash4/VEdit  
**Primary developer agent:** Claude, using the user's connected GitHub integration  
**Build platform:** GitHub Actions

---

## 1. Repository Safety — First Instruction

Before changing anything:
1. Open and inspect `https://github.com/rudicrash4/VEdit`.
2. Confirm the repository owner and exact repository name.
3. Inspect branches, files, commits, and existing workflows.
4. Do not assume the repository is empty.
5. Preserve useful existing files and working code.
6. Do not force-push, rewrite history, delete branches, or overwrite unrelated work.
7. If the repository contains unexpected content or the connected account lacks permission, stop and report the issue before destructive changes.

All implementation work must target this repository unless the user explicitly changes the target.

## 2. Development Workflow

Work in small, verifiable phases.

For each phase:
1. Read relevant GDD/TDD requirements.
2. Inspect existing implementation.
3. State a short implementation plan.
4. Implement only the phase scope.
5. Run relevant unit tests and static checks.
6. Run the Gradle build.
7. Fix failures before declaring completion.
8. Update documentation and progress notes.
9. Commit changes with a clear message.
10. Push to the intended branch using the connected GitHub integration.
11. Check the GitHub Actions workflow result.
12. Report commit, changed areas, test/build status, and any limitations.

Do not start a large batch of later phases while the current phase has unresolved build-breaking failures.

## 3. Phase Plan and Exit Criteria

### Phase 0 — Repository and technical feasibility audit
Deliver:
- Repository inventory.
- Proposed Gradle/module structure.
- Dependency and license inventory.
- Android minimum SDK recommendation with reasons.
- Media3 capability investigation.
- Speed-ramp and multi-track export proof-of-concept plan.
- Initial GitHub Actions workflow.

Exit criteria:
- Clean debug build in GitHub Actions.
- Basic app installs/launches.
- No unreviewed dependency licenses.

### Phase 1 — App foundation
Deliver:
- Android project configuration.
- Compose theme and navigation.
- Main screen and project list placeholder backed by real app state.
- Basic project creation.
- CI build and tests.

Exit criteria:
- App compiles, installs, launches.
- CI produces debug APK artifact.

### Phase 2 — Media import and preview
Deliver:
- Supported media picker integration.
- Media metadata.
- Video preview.
- Basic playback and seeking.
- URI persistence handling.

Exit criteria:
- Import and preview real local media on device.
- App handles unsupported or unavailable media gracefully.

### Phase 3 — Project model and timeline
Deliver:
- Versioned project schema.
- Track/clip domain model.
- Timeline UI.
- Add, select, move, trim, split, and delete for supported clip types.
- Autosave and reopen.

Exit criteria:
- Timeline edits persist after closing/reopening.
- Timeline engine unit tests pass.

### Phase 4 — Basic export
Deliver:
- Export plan and export UI.
- Real MP4 export for supported MVP operations.
- Progress and error handling.
- Export verification tests.

Exit criteria:
- Exported MP4 opens and plays externally.
- Output reflects edits that are declared supported.

### Phase 5 — Speed and keyframes
Deliver:
- Normal speed.
- Speed-curve model and curve UI.
- Variable speed export proof of concept and implementation if technically supported.
- Keyframe model and basic interpolation.

Exit criteria:
- Speed mapping tests pass.
- Preview and export behavior are verified.
- If a feature is not supported by the selected pipeline, document the limitation and propose a tested alternative before proceeding.

### Phase 6 — Audio, text, effects, and adjustments
Deliver in small sub-phases:
- Audio clips and volume/fades.
- Text overlays.
- Basic color adjustments.
- Selected effects.

Exit criteria:
- Each implemented feature is reflected in preview and export.
- Regression tests pass.

### Phase 7 — Optimization and stabilization
Deliver:
- Memory/performance improvements.
- Crash and lifecycle handling.
- Compatibility checks.
- Regression suite.
- README and user instructions.
- Optional release workflow.

Exit criteria:
- CI green.
- Real-device smoke test completed.
- Known limitations documented.

## 4. Git and Commit Rules

- Use the default repository branch unless the repository already has a documented branching convention.
- Prefer small, logical commits.
- Suggested commit messages:
  - `chore: initialize Android project`
  - `feat: add project management foundation`
  - `feat: implement media import and preview`
  - `feat: add timeline clip operations`
  - `ci: add Android build workflow`
  - `fix: preserve project state on restart`
- Do not commit generated build directories, local SDK paths, IDE secrets, signing keys, or private media.
- Do not amend/rewrite published history unless explicitly authorized.
- Never claim a push succeeded without checking the GitHub result.

## 5. GitHub Actions Requirements

At minimum, add a CI workflow that:
- Runs on push and pull request.
- Checks out the repository.
- Configures a compatible JDK and Android SDK.
- Runs tests and lint tasks that exist in the project.
- Builds a debug APK.
- Uploads the APK as a downloadable Actions artifact only if the build succeeds.
- Retains logs for diagnosing failures.

Use the Gradle wrapper in the repository. Verify actual task names rather than assuming them.

Release signing:
- Optional for the initial development phase.
- If implemented, store keystore and passwords only in GitHub Secrets.
- Never commit signing files or print secrets.
- Clearly label debug and release artifacts.

## 6. Quality Rules

- No fake buttons or placeholder claims.
- If a control is not implemented, disable it or label it as planned.
- Do not fake export progress or create a dummy MP4.
- Do not claim a feature works based only on a successful compile.
- Add tests for timeline math, speed mapping, keyframes, and project serialization.
- Preserve working features during refactors.
- Avoid unnecessary rewrites.
- Update GDD/TDD/README when behavior or architecture changes.
- Keep a `CHANGELOG.md` or phase progress section to record meaningful changes.

## 7. Reporting Format After Each Phase

Claude must report:
- Phase completed.
- Features implemented.
- Files/modules changed.
- Tests executed and results.
- Gradle build result.
- Git commit hash and push status.
- GitHub Actions run status.
- APK artifact location if generated.
- Known limitations and next phase.

If a step fails, state the exact failure and what remains unfinished. Do not describe an incomplete phase as complete.

## 8. Device Testing

GitHub Actions cannot prove every Android device behavior. After an APK is generated, request real-device testing for:
- Installation and launch.
- Media picker.
- Playback/seek.
- Timeline touch interactions.
- Save/reopen.
- Export playback.
- Memory and thermal behavior on longer media.

Do not claim universal device compatibility.

## 9. Progress notes
- Phase 0: selesai (CI hijau, uji perangkat CPH2591 API 35: aplikasi terbuka, codec terdeteksi).
- Phase 0B: PoC diuji di CPH2591 (7/8 lolos; preview gagal karena durationUs, sudah diperbaiki di build v0.1.0, belum diuji ulang). Phase 1 belum dimulai (lihat `PHASE0B_REPORT.md`).
- Distribusi APK: GitHub Release (APK langsung) per push ke main; artifact Actions tetap ZIP. Nama `VEdit-<Tahap>-v<versi>.apk` dari `gradle.properties`.
- Ikon sementara adaptive sudah ada (`res/mipmap-*`, `drawable/ic_launcher_foreground.xml`).
- Signing permanen ditunda; debug key sementara dipakai (`SIGNING.md`).
- Phase 1 (1.1-1.2): mesin timeline, penyimpanan proyek berautosave, impor SAF, shell editor. Lolos CI; belum diuji perangkat; belum ada preview (lihat `PHASE1_REPORT.md`). Versi aplikasi 0.2.0, APK `VEdit-Phase1-v0.2.0.apk`.
- Phase 1 sub-tahap 1.3 (v0.3.0): preview nyata (CompositionPlayer + fallback ExoPlayer), sinkron playhead, kontrol putar. Lolos CI; BELUM diuji perangkat. APK `VEdit-Phase1-v0.3.0.apk`. Aturan: setiap perubahan kode memperbarui README, DEVELOPMENT, TDD, laporan fase, CHANGELOG pada commit yang sama.
- Uji perangkat v0.3.0 (laporan pengguna): preview video, gambar, audio lancar.
- v0.4.0: export MP4 awal + UI adaptif. Lolos CI; belum diuji perangkat. APK `VEdit-Phase1-v0.4.0.apk`.

## 10. Peta jalan sampai selesai (status jujur)
| Tahap | Isi | Status |
|---|---|---|
| 1.1-1.3 | Model, timeline, impor, preview | Selesai; preview diuji pengguna |
| 1.4 | Thumbnail klip, waveform, pinch-zoom, drag klip | Belum |
| 4 | Export: pilih resolusi (1080p), bitrate, foreground service untuk export panjang | Export 720p berhasil di OPPO A18 (v0.4.0); rasio kanvas ada (v0.5.0, belum diuji); 1080p belum aktif |
| 5 | Kecepatan (UI), kurva kecepatan, keyframe | Model/PoC ada (Phase 0B), UI belum |
| 6 | Teks overlay, fade audio, penyesuaian warna, efek terpilih | Belum |
| 7 | Kompatibilitas perangkat, memori/performa, regresi, signing rilis permanen, rilis | Belum; signing ditunda atas keputusan pengguna |
Tiap tahap selesai hanya bila kriteria terpenuhi, lolos CI, dan (untuk perilaku visual/audio) terbukti di perangkat.
- v0.5.0: rasio kanvas + optimasi preview (lihat PHASE1_REPORT). Lolos CI; belum diuji perangkat. Hasil uji v0.4.0 di OPPO A18: semua lancar, preview agak lag.
