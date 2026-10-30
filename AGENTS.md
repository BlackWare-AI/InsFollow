# InsFollow agent instructions

InsFollow 0.1 is a Kotlin / native Views application, minSdk 34, one Activity.
Read README.md and docs/VALIDATION.md before making changes.

- data/: bounded Favorites parsing, SQLite transactions, exports and backup.
- model/: stable statuses and reason codes.
- queue/: unique persisted queue and local-time scheduling.
- web/: one System WebView; isolated read-only DOM inspection.
- workflow/: assisted state machine; no automatic Follow execution.
- ui/: inline AMOLED interface, resources in English and Turkish.

Never modify INSFAV, INSFAV Plus, INSFAV Thumb Fetcher or their data.
Never erase progress on import, reshuffle an unfinished queue on launch, infer success from a click,
replace a confirmed result with uncertainty, or strip original display-name suffixes.
Preserve imported JSON, folder ordering, pageId values, unrelated fields and operation history.
Use transactions, parameterized SQL, SAF, bounded untrusted input and no authentication exports.
Do not add modals, another navigation Activity, telemetry, services, broad permissions,
a JavaScript bridge, automatic follows, token interception or evasion mechanisms.
All visible text must have English and Turkish Android resources.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` for substantive changes.
Run instrumentation on Android 14+ when available. Use fictional local fixtures only.
Record actual checks in docs/VALIDATION.md; distinguish compilation, unit, emulator, device and live checks.
Never publish v0.1 until signing and the documented device / compatibility gates pass.
