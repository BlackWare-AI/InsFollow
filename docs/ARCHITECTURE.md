# Architecture and lifecycle decisions

One Activity uses native XML/Views, one attached WebView and SQLiteOpenHelper. No production UI/network/database framework is required beyond Kotlin’s runtime and Android.

- `model/`: normalized identities, status/stage enums and conservative verdict mapping.
- `data/`: bounded strict JSON scanning with targeted string edits, nested Favorites parsing, immutable source snapshots, transactional SQLite, exports and app backups.
- `queue/`: eligibility without replacement and local-time window/interval logic.
- `web/`: lifecycle and navigation restrictions; bundled read-only `res/raw/inspect.js` detector.
- `workflow/`: bounded state machine, assisted executor interface and refreshed verification.
- `ui/`: single-screen Activity, SAF, inline cards/settings/confirmation and localized state descriptions.

Accounts are keyed by trimmed lowercase ASCII usernames, not display names. Memberships retain source/ordinal/item identity. Queue position and per-session entry state persist, so no launch-time reshuffle is needed. Each acquisition creates a durable operation before navigation. Finishing writes account/operation/queue/counters in one transaction. Unknown observations keep previous confirmed account codes and the new observation remains in operation history. Restart recovery consumes interrupted entries as uncertain without repeating the interaction. Explicit rechecks preserve all operations.

Settings are saved in SQLite as edited. Invalid settings block execution. Window checks use LocalTime.now() in the device timezone on every workflow step and in a foreground tick. The interval deadline is wall-clock epoch time stored with the queue session. Resume is always manual after lifecycle or operating-window interruption. Sessions stop at their cumulative configured processing cap until the setting is raised. No alarms, services or background permission are used.

## WebView decisions

A live instance stays attached to its inline FrameLayout. Collapse changes its container to one pixel and INVISIBLE, pauses the workflow, stops loading and calls onPause. Expansion changes layout/visibility and calls onResume without replacement. Android does not guarantee activity of an invisible browser; v0.1 does not depend on it. A parent card collapse also pauses it. Activity onPause handles Home, app switch, screen locking and system file pickers; the browser is never expected to keep operating then.

Activity recreation destroys the old instance. Normal WebView storage preserves cookies where Android allows, but an in-flight operation is recovered as uncertain. No WebView reference is retained across Activities. Renderer loss removes/destroys the unusable instance, pauses work and permits user-requested creation of one replacement. There is never more than one live instance. No global pauseTimers is used because it affects other WebViews in a process. No auth data is copied into app backups.

onPageFinished only flushes normal cookies. Inspection polls with a 30-second deadline and two consistent observations. Matching expected HTTPS profile URL, main/header and username identity evidence are required for follow-state controls. Following/requested observations are refreshed and rechecked before saving. ProfileBrowser separates navigation/observation from workflow coordination so deterministic local fixtures can exercise the state machine. Scheduled and asynchronous database errors pause execution without fabricating a result; unfinished durable operations are recovered on restart. Security/login errors also pause during inter-account intervals. The user activates Follow directly; the execution interface merely asks the UI to present it. After user confirmation, the page settles before refresh. Unknown controls are never clicked. Login/challenge/restriction observations stop processing. Network/HTTP/TLS/timeout failures are uncertain; repeated navigation failure stopping is configurable. The detector deliberately prefers uncertainty and requires live validation for evolving Instagram layouts.

## Sources checked during initial implementation

- Android [Manage WebView objects](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview): renderer termination requires removal/destruction; an old renderer’s WebView cannot be reused.
- Android [WebView API](https://developer.android.com/reference/android/webkit/WebView): lifecycle, onPause/onResume, visibility and destruction contracts.
- Android [WebView security](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges): avoid unrestricted native bridges.
- Android [AGP 8.13 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes): Gradle 8.13, JDK 17, API 36 support.

These contracts inform design; they do not replace device testing. See VALIDATION.md for evidence and outstanding checks.
