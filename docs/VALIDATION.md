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

Source `8d3d92d`: the Android 16 CI JUnit report contains 38 tests with zero failures/errors/skips, including renderer termination, native Back and all workflow fixtures. The Android 14 CI process was killed with signal 9 during the pre-existing read-only detector fixture, without an application exception stack; that attempt is not passing evidence. The corrected renderer termination test separately passed on the local Android 14 AOSP software emulator (`OK (1 test)`, 59.762 seconds including launch/setup). The full local Android 14 suite was subsequently attempted; its partial result and the final accelerated CI rerun are recorded below. GitHub run-status endpoints were stale relative to downloadable artifacts during this investigation, so counts above come from the JUnit reports, not inferred job colors.


## Verified lifecycle follow-up results

[CI run 41103030652](https://github.com/BlackWare-AI/InsFollow/actions/runs/41103030652), source `99141f4` (application code unchanged from `8d3d92d`), produced downloaded JUnit reports confirming **37 JVM tests**, **38 instrumentation tests on Android 14**, and **38 instrumentation tests on Android 16**, all with zero failures, errors or skips. Both device reports include the actual native Back and renderer termination checks. The CI lint report has **zero errors and two warnings**: OldTargetApi and a newer Gradle version. The tested compile/target SDK 36 and Gradle 8.13 remain pinned; this evidence does not establish Android 17/API 37 compatibility.

The initial Android 14 SIGKILL interruption did not reproduce in this complete rerun. The local Android 14 software-emulator renderer-only test passed, but its subsequent full-suite attempt exceeded a 15-second fixture-page wait and was stopped after accelerated CI provided complete coverage. That partial local run is **not** a passing full-suite result. No real Instagram follow action was used by these tests.

Local follow-up APKs: debug 2,890,263 bytes, SHA-256 `c031058cfe45264203c07dbf1ee0e7dc109bdaa3a4bad7c449d968ffdce53d89`; minified unsigned release 154,891 bytes, SHA-256 `392dbef88838898cb8c3141a6341092f81b417299c69a84acc9dc3d3ed46c8cc`. These are validation builds and have not replaced the published debug-0.1-1 asset. Physical-device, live Instagram, target InstaPro and remaining manual lifecycle/low-memory gates remain open.


## Scan-first and scheduling extension

New source adds conservative follower intervals and filters, weekly-window JVM tests, plus native tests for unique/persistent scans, recovery, independent follow history, recalculation, eligible queue order, schema migration/legacy restore, scan exports, read-only execution, restriction pause, late callbacks, direct filters, budgets and same-instance Home/Browser switching. These fixtures use fictional local profiles only. Earlier passing results above do not validate this extension. Final build/CI evidence is recorded below.

No physical Android device is attached in this environment. Physical-device Home/Browser continuity, hidden/minimal WebView rendering, genuine Instagram login/DOM variants/security file upload, public/private classification and specific InstaPro import compatibility remain unverified. Public empty-post accounts and already-followed private/public ambiguity deliberately require review. No real follow operations are used in automated tests.


### Scan-first baseline results

[CI run 41107110169](https://github.com/BlackWare-AI/InsFollow/actions/runs/41107110169), source `90148e2`, passed build validation. Downloaded JUnit reports contain **54 JVM tests**, **59 Android 14/API 34 tests** and **59 Android 16/API 36 tests**, with zero failures/errors/skips. CI lint has zero errors and two version warnings (OldTargetApi and AndroidGradlePluginVersion). Explicit post-test APK reinstalls and launches succeeded: process-cold TotalTime 885 ms on API 34 and 1,353 ms on API 36, measured once on shared KVM runners; these are not physical-device performance claims.

These tests include real local WebView DOM fixtures and same-instance tab switching, plus deterministic fake-browser scan/workflow tests. A tab-switch fixture verifies that a running scan is not explicitly paused. It does not prove arbitrary live Instagram layouts or uninterrupted minimal-height WebView rendering on physical devices.

A subsequent session-boundary correction completes an exhausted queue without waiting the configured interval, applies the configured break, and counts filtered profile visits toward the session target. Its extra instrumentation case and latest validation results are recorded below. See [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md) for outstanding physical-device checks.


### Final scan-first verification

[CI run 41109346630](https://github.com/BlackWare-AI/InsFollow/actions/runs/41109346630), application source `f8c2603`, completed successfully. Downloaded JUnit reports confirm **54 JVM tests**, **61 instrumentation tests on Android 14/API 34**, and **61 instrumentation tests on Android 16/API 36**, all with zero failures/errors/skips. This includes immediate exhausted-queue completion/session breaks, filtered visit accounting, separate daily observation/visit counters, and partial-rescan eligibility/export exclusion while retaining prior observations. Pending rescans are excluded from usable progress/eligibility until completed; the history remains intact.

CI lint: zero errors, two version warnings (OldTargetApi and AndroidGradlePluginVersion). Both emulators reinstalled the debug APK and returned `Status: ok` on launch. Single process-cold launch samples: API 34 1,325 ms; API 36 1,743 ms, on shared KVM runners, not physical-device benchmarks. Local `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` also passed on the same application source after an environment restart interrupted the preceding local build. That interrupted attempt is not counted as a completed check.

| Local artifact | Size | SHA-256 |
| --- | ---: | --- |
| Development-key signed debug APK | 2,878,773 bytes | `d3e2ad16ef05ef11b81f81c2b1973ea239a79d7afc311035bdc5146da5a3c430` |
| Minified unsigned release APK | 206,490 bytes | `70e8e51f5691a1b8e125a8d49b295bcf05596332029a5f0ab6a156b3a7b8296f` |

APK identity/version verification passed: `ai.blackware.insfollow`, versionName `0.1`, versionCode 1, minSdk 34, targetSdk 36. English/Turkish resources contain matching 153 unique keys. These builds do not replace the published older debug-0.1-1 asset and are not a production release. No physical device is connected; physical-device live tab/render continuity, Instagram login/DOM/file-upload compatibility, target InstaPro validation and production signing remain open gates.
