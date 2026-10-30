# Validation and release gates

Initial implementation evidence, 2026-10-30. This document records actual checks separately from pending validation. **0.1 is unreleased.**

## Local build and tests

Environment: Linux x86_64, Temurin JDK 17, Android SDK 36, AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21. The initial environment had only a JRE; SDK and JDK were installed for validation. Proxy and CA configuration stayed outside the repository.

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` passed. All **37 JVM tests passed**, zero failures/errors/skips. Lint: **zero errors, one warning** (a newer Gradle version exists; the tested AGP/Gradle pairing is intentionally pinned). The debug and instrumentation APKs compile; the minified/resource-shrunk unsigned release APK compiles. Local APKs produced during the final source validation:

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| Development-key signed debug APK | 2,809,415 bytes | `998c88346820fb31a9bc5af861a560c03f79088ac219b63a1ae33fb9d16e868e` |
| Minified unsigned release APK | 153,471 bytes | `21838b056a7ab8e75fb6c5f567c74a3d4ea74fa3cf8898a662c73df64833d46e` |

These are local validation artifacts, not a production release. CI rebuilds have their own hashes; build metadata and signing differ.

Static checks passed: one manifest Activity; only INTERNET permission; matching 96 English/Turkish string keys without duplicate names; debug APK signature valid; APK application ID `ai.blackware.insfollow`, versionName `0.1`, versionCode `1`, minSdk 34, targetSdk 36. `git diff --check` passed.

JVM tests cover valid/malformed/missing/wrong-type nested Favorites JSON, Unicode, original identities, empty folders/usernames, duplicate membership, repeated parsing, 10,000-item input, size/depth limits, preserved unrelated properties, export/reimport, annotations, original status-like suffixes, unique shuffled eligibility, empty queues, normal/overnight scheduling, interval validation, and conservative result mapping/retention.

## Runtime evidence

[GitHub CI run 40994031672](https://github.com/BlackWare-AI/InsFollow/actions/runs/40994031672), commit `5df852e`, passed compilation, tests, lint and APK verification, plus **16 instrumentation tests on each of Android 14/API 34 and Android 16/API 36**. No tests failed. The strict-export changes, larger source fixture, Activity recreation test and startup measurement are undergoing a final CI rerun.

The local software emulator lacks KVM; its first process was killed with exit 137 and its second boot did not reach package-manager readiness before the equivalent accelerated GitHub runs completed, so it was stopped. Local installation was therefore not verified; the passing accelerated GitHub emulator runs provide installation/launch evidence. No physical-device or live Instagram testing has been performed. No real follow operation has been used as an automated test.

Native instrumentation covers transactional import, source idempotence, queue persistence/order/restart, interrupted operations, reimport preservation, exclusion of completed accounts, pause/stop, write-failure rollback via a rejecting SQLite trigger, backup/restore rollback, export provenance, timeout uncertainty and idempotent completion. UI tests cover same-instance browser expansion/collapse, inline cards, Turkish resources and local deterministic detector fixtures. The final run and startup measurements will be recorded once available. Emulator fixture checks do not establish live website compatibility.

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
- [ ] Passing GitHub CI on reviewed source, repository settings verified, production signing configured, APK install/signature/identity/size/hash checked.

## Repository settings and release blockers

Repository existed publicly with README and MIT license, no application or AGENTS instructions. The existing 2026 BlackWare copyright was preserved. Issues and pull requests are available. No collaborator access was granted.

The available GitHub integration returned **HTTP 403, Resource not accessible by integration** for About/topics, Discussions, secret scanning/push protection, Dependabot alerts/updates and private vulnerability reporting administration. These settings are **not verified/enabled by this implementation**. `scripts/configure_repository.sh` contains the exact requested changes for an owner credential with administration scope. Dependabot configuration and CI/release workflows are supplied as source.

No production signing credentials are configured in this environment. No v0.1 tag or GitHub Release has been published. The release workflow fails unless secure signing secrets and `RELEASE_VALIDATION_APPROVED=true` are present. It never publishes unsigned production artifacts.
