# VEdit Technical Design Document (TDD)
## Hybrid Rust Engine Migration — v1.0
**Status:** Proposed architecture and implementation mandate  
**Project:** VEdit Android Video Editor  
**Repository:** https://github.com/rudicrash4/VEdit  
**Baseline:** Existing VEdit repository, currently reported at v0.5.0  
**Target:** A complete, buildable application ready for a new round of real-device testing

---

## 1. Purpose

This document defines the target architecture and migration requirements for transforming the existing VEdit Android application into a Rust-powered video editor.

This is a major architectural migration, not a small feature update. Work must continue in the **existing repository and branch history**. Do not create an unrelated replacement project or discard the working application.

The objective is to move appropriate editing-engine responsibilities from Kotlin into Rust while retaining Android-native integration where it remains the best technical choice.

The final result of this project phase must be a complete Android application that builds successfully and is ready for the user to install and test again on a physical OPPO A18 device.

## 2. Product and project constraints

Preserve the established VEdit product direction unless the repository audit identifies a concrete incompatibility that must be discussed and documented:

- Android-native video editor.
- Kotlin and Jetpack Compose for the Android application/UI unless a strong technical reason justifies a limited change.
- Offline-first operation.
- No mandatory account, login, backend, cloud service, subscription, payment, or watermark.
- No forced change to the existing application ID/package identity.
- Keep existing working functionality and project compatibility.
- Use GitHub Actions to build and publish testable APK artifacts/releases.
- Debug signing remains acceptable for this development phase; do not introduce permanent signing secrets or change signing strategy without explicit user approval.
- Preserve the existing project and autosave data formats where possible. Any required migration must be versioned, reversible where practical, and tested against real legacy fixtures.
- Do not force-push, rewrite shared history, or delete existing releases/tags.
- Update Markdown documentation in the same commit as relevant code changes.

## 3. Existing baseline and mandatory audit

The repository is the source of truth. Before changing implementation:

1. Inspect the current default branch, recent commits, tags/releases, Gradle configuration, CI workflows, source tree, tests, and documentation.
2. Verify the actual current version and commit. Do not assume the version remains v0.5.0 merely because it was previously reported.
3. Inspect all existing Kotlin editor, timeline, project, preview, composition, effects, audio, and export code.
4. Inspect Media3 dependencies and the exact APIs currently used.
5. Inspect the project schema, serialization, autosave, undo/redo, and compatibility paths.
6. Identify existing device-tested behavior versus CI-only behavior.
7. Record the audit in `docs/RUST_MIGRATION_AUDIT.md`.

Do not start a destructive rewrite before the audit. Do not claim to have inspected files, run tests, built APKs, or tested a device unless those actions actually occurred.

## 4. Architecture decision

### 4.1 Overall approach

Use a **Rust-centered hybrid architecture**:

- Kotlin owns Android application integration and UI.
- Rust owns the editing domain/core where it offers a clear benefit.
- Android Media APIs and/or Media3 provide platform media capabilities where appropriate.
- The media pipeline may remain hybrid or be replaced selectively after a documented, evidence-based comparison.

The terms “Hybrid Rust Engine” and “Hybrid Media Pipeline” describe the initial direction, not a permanent prohibition against replacing Media3. Claude is explicitly authorized to recommend and implement a full or partial Media3 replacement **if Rust/native alternatives are demonstrably substantially better for VEdit’s requirements** and the migration is safe, maintainable, legally usable, and feasible on the target Android devices.

Do not replace Media3 merely because Rust is available. Do not retain Media3 merely because it is already present. Decide component by component using evidence.

### 4.2 Kotlin responsibilities

Expected Kotlin responsibilities include:

- Android application entry points and lifecycle.
- Jetpack Compose UI and navigation.
- Android permissions and Storage Access Framework (SAF).
- Android content URI and file descriptor handling.
- Android-specific project file persistence integration.
- Window/insets and device integration.
- Media surface and platform resource lifecycle.
- User-facing error presentation and accessibility.
- Bridge calls and UI state projection.
- Platform-specific services and notifications if required for reliable long exports.

Some of these responsibilities may move only if a compelling technical case exists. Rust must not take ownership of Android UI lifecycle or retain unsafe Android object references.

### 4.3 Rust responsibilities

Expected Rust core responsibilities include:

- Project-domain model and validation.
- Timeline model and time calculations.
- Track and clip operations.
- Split, trim, move, duplicate, delete, reorder.
- Undo/redo or a well-defined command/history core.
- Canvas/aspect-ratio model and Fit/Fill semantics.
- Composition planning and render-plan generation.
- Transform/effect parameter model and deterministic ordering.
- Audio timeline/mix planning and parameter calculations.
- Export planning and validation.
- Core project serialization logic if it improves consistency and compatibility.
- Deterministic, testable business logic.

These are target responsibilities, not an instruction to move every module blindly. The audit may justify keeping specific logic in Kotlin temporarily or permanently. Any exception must be documented.

### 4.4 Media pipeline decision

Evaluate the following separately:

- Video/audio demuxing and decoding.
- Hardware decoding.
- Preview playback and seeking.
- Composition and multi-clip sequencing.
- GPU effects and transforms.
- Audio mixing and processing.
- Video/audio encoding.
- Muxing and output writing.
- Cancellation, progress, and background execution.

Candidates may include Media3/ExoPlayer/Transformer, Android MediaCodec/MediaExtractor/MediaMuxer, Rust crates, FFmpeg (subject to licensing and distribution review), and a custom Rust/native pipeline.

For each component, compare:
- Feature completeness for VEdit’s requirements.
- Hardware codec compatibility on Android.
- Preview latency, frame pacing, seeking, and memory use.
- Export speed, output correctness, and A/V synchronization.
- Multi-track and effects support.
- Stability across Android versions/devices.
- Maintenance burden and dependency health.
- Binary size and ABI complexity.
- License compatibility and obligations.
- Ability to cancel safely and recover from errors.

A replacement is justified only when there is credible evidence of a substantial net benefit, not theoretical performance claims. If a full replacement cannot be completed and validated within this phase, retain the proven Media3 path and document the next migration stage rather than shipping an incomplete engine.

## 5. Kotlin–Rust bridge selection

The bridge is intentionally **not preselected**. Claude must evaluate and choose the option with the best long-term fit.

Candidates:
- UniFFI.
- JNI directly.
- A carefully justified combination, only if the split provides real value and does not create unnecessary complexity.

Evaluate:
- Kotlin API ergonomics and type safety.
- Async and callback/event handling.
- Error propagation.
- Data transfer and serialization overhead.
- Android NDK/Gradle integration.
- ABI packaging and reproducible CI builds.
- Memory ownership and lifecycle safety.
- Debugging, testability, documentation, and ecosystem maturity.
- Long-term maintenance and ability to expose stable versioned APIs.

Create `docs/BRIDGE_DECISION.md` containing:
1. Candidate comparison.
2. Prototype scope and results.
3. Chosen bridge and rationale.
4. API boundary conventions.
5. Error, threading, cancellation, and lifecycle model.
6. Known limitations.

Do not select a bridge solely based on familiarity. Build a small working Android proof of concept before committing the full engine to it.

## 6. Target architecture and module boundaries

A suggested Rust crate layout (adjust after repository audit):

```text
rust/
  vedit-core/
    Cargo.toml
    src/
      lib.rs
      error.rs
      project/
      timeline/
      operations/
      history/
      canvas/
      composition/
      effects/
      audio/
      render/
      media/
      bridge/
```

Kotlin should expose a small application-facing engine facade, for example:

```kotlin
interface VEditEngine {
    suspend fun createProject(config: ProjectConfig): ProjectResult
    suspend fun loadProject(source: ProjectSource): ProjectResult
    suspend fun applyOperation(
        projectId: String,
        operation: EditOperation
    ): OperationResult
    suspend fun undo(projectId: String): OperationResult
    suspend fun redo(projectId: String): OperationResult
    suspend fun getRenderPlan(projectId: String): RenderPlanResult
}
```

This is illustrative, not a mandated API. Adapt it to the selected bridge. Avoid passing large video frame buffers through a high-overhead bridge. Prefer handles, file descriptors, shared native buffers, or platform surfaces when justified and safe.

### 6.1 Single source of truth

Choose and document one authoritative owner for editable project state. The target design is Rust-owned project/timeline state, with Kotlin maintaining UI state and display snapshots only.

Avoid independently mutable duplicate timelines in Kotlin and Rust.

Define:
- Project/session handles and lifetime.
- Snapshot/version semantics.
- Operation IDs and ordering.
- Thread-safety rules.
- Serialization boundaries.
- Cache invalidation.
- Recovery after native errors.

If a staged migration temporarily leaves Kotlin as the source of truth for a module, document the transition and prevent divergent state.

### 6.2 Stable domain contracts

Use explicit, versioned data contracts:
- Stable IDs for projects, tracks, clips, and operations.
- Integer microseconds or another single documented time unit for media timing; avoid floating-point timeline authority.
- Explicit canvas dimensions/aspect ratio and Fit/Fill policy.
- Explicit transform/effect ordering.
- Structured errors with stable error codes.
- Schema version and migration metadata.

Do not silently reinterpret existing time units or project fields.

## 7. Functional requirements

All existing features must be inventoried from the repository and retained unless a defect or architectural conflict is demonstrated and documented.

At minimum, verify and preserve applicable existing functionality:

### Project and media
- Create, save, autosave, reopen, and recover projects.
- Import video, image, and audio using Android-supported file access.
- Handle unsupported/corrupt/missing media gracefully.
- Preserve references and permissions required to reopen imported content.

### Timeline editing
- Multiple tracks and clips supported by the existing product.
- Split, trim, delete, duplicate, reorder, and move.
- Undo/redo.
- Accurate duration, gap, and position calculations.
- No unexpected loss of edits during save/reopen.

### Canvas and transforms
- 16:9, 9:16, 1:1, and 4:5 presets, if present in the baseline.
- Fit and Fill.
- Crop and rotation.
- Correct source-to-canvas handling for portrait, landscape, and square media.
- Preview and export must use consistent semantics.

### Preview
- Play/pause and seek.
- Multiple clips, gaps, image duration, and extra audio where supported by baseline.
- Correct lifecycle release and rebuild behavior.
- No unnecessary player recreation.
- Graceful handling of decode failures and unsupported formats.

### Export
- MP4 output and supported codecs.
- Correct dimensions and aspect ratio.
- Correct duration and clip ordering.
- Audio presence, timing, and synchronization.
- Crop, rotation, Fit/Fill, and effects consistent with preview.
- Progress, cancellation, failure handling, and cleanup.
- Safe behavior if the app is backgrounded or interrupted.
- Output saved to the expected user-accessible location.

Do not mark a feature complete based only on compilation.

## 8. Migration strategy

Migration is **module by module**, with a working app maintained throughout.

For each module:
1. Document current Kotlin behavior and dependencies.
2. Define the Rust contract and expected semantics.
3. Implement Rust unit tests first or alongside implementation.
4. Implement the Rust module.
5. Add bridge and Android integration.
6. Run differential tests against the Kotlin implementation where practical.
7. Integrate behind a controlled feature switch or adapter during transition.
8. Test on Android/emulator and physical device when available.
9. Make the Rust path default only after parity and stability are demonstrated.
10. Remove old Kotlin implementation only after parity is proven and rollback is no longer needed.
11. Update TDD, migration audit, development guide, phase report, and changelog in the same commit.

Do not maintain two permanent sources of truth. Temporary dual implementation is for comparison and safe migration only.

## 9. Testing and quality requirements

### Rust
- `cargo fmt --check`
- `cargo clippy` with project-appropriate warning policy
- `cargo test`
- Tests for edge cases, invalid inputs, time precision, serialization, and migrations.

### Android
- Kotlin unit tests.
- Android lint.
- Instrumentation/integration tests where feasible.
- Native library load and bridge smoke tests.
- Debug APK assembly for targeted ABI(s).

### Differential and regression tests
For migrated behavior, compare old and new outputs where possible:
- Timeline positions/durations.
- Clip ordering.
- Split/trim boundaries.
- Undo/redo outcomes.
- Project serialization round trips.
- Render-plan semantics.
- Canvas dimensions and transforms.

### Media validation
Use test fixtures with known properties. Verify actual output metadata and, where feasible, inspect decoded frames and audio:
- Resolution and aspect ratio.
- Duration.
- Frame rate behavior.
- Codec and container.
- Audio stream presence and duration.
- A/V sync.
- Orientation/rotation.
- Clip transitions/gaps.
- Fit/Fill and crop correctness.

### Device testing
The target physical test device previously used by the user is OPPO A18 (CPH2591, Android API 35). This is a user-reported target, not a device available to Claude by default.

Claude must clearly separate:
- CI results.
- Emulator results.
- Physical-device results.
- User-reported results.

Never claim a physical-device test occurred unless it actually did.

## 10. Performance requirements

Do not promise that Rust automatically improves performance.

Establish baseline measurements and compare:
- App startup and native library load time.
- Project open time.
- Timeline operation latency.
- Preview build/ready time.
- Seek response time.
- Playback frame drops/stutter where measurable.
- Memory usage and native allocations.
- Export time and output quality.

Use repeatable test projects and document device, Android version, media properties, build variant, and measurement method.

Do not lower export quality to improve preview. Preview-only quality reduction or proxy media may be considered if visually acceptable and documented.

## 11. Android build and release requirements

- Integrate Rust cross-compilation through Android NDK and Gradle/Cargo tooling.
- Target only required ABIs initially; document the decision.
- Ensure native libraries are packaged correctly in the APK.
- Verify clean builds in GitHub Actions.
- Keep existing package/application ID.
- Keep debug signing for this phase unless explicitly authorized otherwise.
- Publish a clearly named test APK through a GitHub Release.
- Preserve existing release history.
- Do not claim release publication until the release and asset are verified.

CI should include Rust checks, Kotlin checks, lint, native build, and APK assembly.

## 12. Documentation requirements

Update documentation in the same commit as relevant implementation changes:
- `README.md`
- `docs/TDD.md`
- `docs/DEVELOPMENT.md`
- `docs/PHASE1_REPORT.md` or an appropriate new migration report
- `docs/RUST_MIGRATION_AUDIT.md`
- `docs/BRIDGE_DECISION.md`
- `CHANGELOG.md`

Do not overwrite historical test results. Correct stale statements while preserving a dated history where useful.

## 13. Completion criteria

This phase is complete only when:

1. Repository audit and architecture decisions are documented.
2. Bridge choice is justified and implemented.
3. Rust core is integrated into the Android app.
4. The agreed editing-engine responsibilities have migrated to Rust, or any remaining Kotlin responsibilities have documented technical justification.
5. Media pipeline choice is evidence-based; any retained Media3 components are intentional.
6. Existing user-facing features are preserved and regression-tested.
7. Project compatibility and migration tests pass.
8. Rust tests, Kotlin tests, lint, native build, and APK assembly pass.
9. GitHub Actions succeeds on the final commit.
10. A downloadable test APK is attached to a verified GitHub Release.
11. Documentation is complete and consistent.
12. Final report distinguishes verified facts from untested device behavior.

The deliverable is a complete app ready for the user to install and test on OPPO A18. It is not necessary to claim that physical-device testing has already passed.

## 14. Explicit non-goals and cautions

- Do not create a new unrelated app or repository.
- Do not remove working features to simplify migration.
- Do not rewrite the whole codebase in one unreviewable change.
- Do not add cloud/backend/login/ads/subscriptions.
- Do not introduce proprietary or license-incompatible dependencies.
- Do not copy code or assets without license review.
- Do not silently change project formats.
- Do not claim benchmark improvements without measurements.
- Do not replace Media3 without a documented technical comparison and regression plan.
- Do not stop at a demo or proof of concept: complete the planned migration and deliver a testable app.

---

## 15. Final handoff report format

At completion, provide:

1. Repository URL and branch.
2. Starting commit and final commit.
3. Actual app version.
4. Architecture diagram and bridge decision.
5. Modules migrated to Rust.
6. Modules intentionally retained in Kotlin/Media3 and why.
7. Media pipeline decision and supporting evidence.
8. Compatibility and migration details.
9. Rust/Kotlin/Android test results.
10. CI run ID and result.
11. Release URL and exact APK asset name.
12. Known limitations and risks.
13. Device test checklist for OPPO A18.
14. Explicit statement of what has and has not been physically tested.

**Final status must be honest: “ready for device testing” is not the same as “device verified.”**
