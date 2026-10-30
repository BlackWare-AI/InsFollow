# Validation and release gates

Initial implementation evidence, 2026-10-30. This document records actual checks separately from pending validation. **0.1 is unreleased.**

## Local build and tests

Environment: Linux x86_64, Temurin JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21. The initial environment had only a JRE; SDK and JDK were installed for validation. Proxy and CA configuration stayed outside the repository.

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` passed. All **37 JVM tests passed**, zero failures/errors/skips. Lint: **zero errors, one warning** (a newer Gradle version exists; the tested AGP/Gradle pairing is intentionally pinned). The debug and instrumentation APKs compile; the minified/resource-shrunk unsigned release APK compiles. Local APKs produced during the final source validation:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| Development-key signed debug APK | 2,888,979 bytes | `dbde278e6642da587fb97650fe9d2f3a67fc4a287375c5d56e880c6e2368f495` |
| Minified unsigned release APK | 153,563 bytes | `6a1126a044e5d3dc5151dff504425cb572831fb9516d84075fe690600af1af19` |

These are local validation artifacts, not a production release. CI rebuilds have their own hashes; build metadata and signing differ.

Static checks passed: one manifest Activity; only INTERNET permission; matching 96 English/Turkish string keys without duplicate names; debug APK signature valid; APK application ID `ai.blackware.insfollow`, versionName `0.1`, versionCode `1`, minSdk 34, targetSdk 36. `git diff --check` passed.

JVM tests cover valid/malformed/missing/wrong-type nested Favorites JSON, Unicode, original identities, empty folders/usernames, duplicate membership, repeated parsing, 10,000-item input, size/depth limits, preserved unrelated properties, export/reimport, annotations, original status-like suffixes, unique shuffled eligibility, empty queues, normal/overnight scheduling, interval validation, and conservative result mapping/retention. Strict-scan tests reject trailing garbage, duplicate fields and non-JSON syntax, and verify exact preservation of unrelated high-precision numeric literals.

## Runtime evidence

[GitHub CI run 40995393558](https://github.com/BlackWare-AI/InsFollow/actions/runs/40995393558), source commit `a08d3bf`, passed compilation, **37 JVM tests**, lint and APK verification, plus **19 instrumentation tests on each of Android 14/API 34 and Android 16/API 36**. Downloaded JUnit XML confirms zero failures, errors or skips on both emulators. Explicit debug APK reinstalls and launches also succeeded after test cleanup.

| Emulator | Bundled System WebView | Process-cold launch TotalTime |
| --- | --- | ---: |
| Android 14/API 34, Google APIs x86_64 | 113.0.5672.136 | 1,164 ms |
| Android 16/API 36, Google APIs x86_64 | 133.0.6943.137 | 1,203 ms |

Measurements use one `adb shell am start -W` launch after a fresh reinstall and force-stop on shared GitHub Ubuntu runners with KVM and two emulator cores. They are development-APK samples, not physical-device, warm-start or representative production benchmarks. Reports, startup output and WebView package information are attached to that CI run. The emulator images bundle older WebView versions; current physical-device WebView behavior remains unverified.

The local software emulator lacks KVM; its first process was killed with exit 137 and its second boot did not reach package-manager readiness before the equivalent accelerated GitHub runs completed, so it was stopped. Local installation was therefore not verified; the passing accelerated GitHub emulator runs provide installation/launch evidence. No physical-device or live Instagram testing has been performed. No real follow operation has been used as an automated test.

Native instrumentation covers transactional import, source idempotence, queue persistence/order/restart, interrupted operations, reimport preservation, exclusion of completed accounts, pause/stop, write-failure rollback via a rejecting SQLite trigger, backup/restore rollback, export provenance, timeout uncertainty and idempotent completion. A 3 MiB unrelated-source fixture verifies the larger SQLite cursor window, export preservation and restore. A simulated missing browser callback verifies the independent workflow deadline. UI tests cover same-instance browser expansion/collapse, inline cards, Turkish resources, local deterministic detector fixtures and Activity recreation with persisted settings and exactly one new browser. Emulator fixture checks do not establish live website compatibility.

## Required manual acceptance matrix

All unchecked items below remain release gates. Use fictional local fixtures and controlled/authorized accounts for any live checks.

- [ ] Install and launch on a physical Android 14+ device and API 36 device/emulator; measure cold/warm startup, report device/build context.
- [ ] Import a real compatible Favorites source privately; select folders and verify every membership/count. Preserve the original document byte-for-byte.
- [ ] Start/pause/stop/resume/skip/recheck/shuffle; confirm persisted order and no duplicate processing across restart/reimport.
- [ ] Verify supported actual Instagram login and cookie persistence, logout/login expiration, page identity, English/Turkish Following/Requested/Follow layouts, and refreshed outcomes with authorized accounts.
- [ ] Confirm no success from a click, timeout, network error, ambiguous controls, wrong profile, challenge or restricted action. Confirm previous verified account results remain in records and observations remain in operation history.
- [ ] Expand/collapse and parent-card collapse; verify the same browser instance stays attached and workflow pauses. Invisible rendering is not assumed active.
- [ ] Home, app switch, screen lock, rotation/recreation, process kill, device reboot, low memory, renderer termination and network loss: queue/results remain correct, no blind action replay, manual resumption required.
- [ ] Portrait/landscape, responsive WebView height, nested touch scrolling, system Back, keyboard/insets, accessibility font sizes, inline errors, no app dialogs, all buttons operational and both locales visible.
- [ ] Roundtrip every JSON/TXT export and app backup/restore, including corrupt/untrusted backups and storage failures. Ensure authentication secrets never enter exports.
- [ ] Import an annotated copy into a specified target InstaPro version and record its version/result. JSON parsing alone is insufficient proof.
- [ ] Performance at defensive limits and on a low-memory device; verify bounded input and source cursor behavior without ANRs or OOM. A 3 MiB unrelated-source fixture also runs in instrumentation.
- [x] Passing GitHub CI on the reviewed application source, emulator debug APK install/launch, and local debug signature/identity/size/hash checks.
- [ ] Repository administration settings verified, production signing configured, signed release APK installed and validated.

## Repository settings and release blockers

Repository existed publicly with README and MIT license, no application or AGENTS instructions. The existing 2026 BlackWare copyright was preserved. Issues and pull requests are available. No collaborator access was granted.

The available GitHub integration returned **HTTP 403, Resource not accessible by integration** for About/topics, Discussions, secret scanning/push protection, Dependabot alerts/updates and private vulnerability reporting administration. These settings are **not verified/enabled by this implementation**. `scripts/configure_repository.sh` contains the exact requested changes for an owner credential with administration scope. Dependabot configuration and CI/release workflows are supplied as source.

No production signing credentials are configured in this environment. Listing repository Actions secret names was also denied with HTTP 403, so repository-side signing configuration is unverified. No production v0.1 tag or production GitHub Release has been published. The release workflow fails unless secure signing secrets and `RELEASE_VALIDATION_APPROVED=true` are present. It never publishes unsigned production artifacts.

## System Back follow-up

System Back now uses the native OnBackInvokedDispatcher API on the supported Android versions. It collapses an expanded browser without replacing the WebView; otherwise it finishes the single Activity. A native instrumentation test invokes the system global Back action and checks instance preservation. Runtime results for this follow-up are tracked separately from the initial evidence above.

The repository owner reported updating About directly. Other administration settings remain unverified. A development-key signed APK was subsequently published as the separate `debug-0.1-1` prerelease for manual device testing; production v0.1 remains unpublished. The published debug prerelease predates this System Back change.

## Workflow interruption follow-up

The lifecycle follow-up adds a ProfileBrowser interface for deterministic navigation/observation fixtures. No fixture invokes Instagram or presses a real Follow control. New scenarios cover refresh-required A/R/F outcomes, user-assisted S/R/F outcomes, unknown pages, foreground pause/manual resume, late callbacks, skip, network/renderer failures, restrictions while waiting for user action, and login/restrictions between queued accounts.

Scheduled callbacks and asynchronous inspection results now guard SQLite failures and stop in-memory processing while retaining the open durable operation for recovery. A security restriction observed between accounts pauses immediately without consuming a queue entry. New instrumentation injects operation-update failures to validate both scheduling and asynchronous result paths. A separate local WebView fixture terminates its renderer and checks safe destruction and one explicit replacement. Results are pending the follow-up CI run; the initial evidence above applies to its recorded source commit.

The first follow-up renderer test failed on both API levels: an unqualified destroy() inside the WebView builder resolved to WebView.destroy(), leaving the dead view attached. The callback now explicitly invokes BrowserSession.destroy() to detach and destroy it before notifying the Activity. Test-side UI assertions now propagate to the instrumentation thread so a failed assertion is reported without aborting the remaining suite. A rerun is required; the failing run is not counted as passing lifecycle validation.
