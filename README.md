# InsFollow

InsFollow **0.1** is an independent, open-source Android companion for managing Instagram follow workflows from InstaPro Favorites backups. It keeps account records on your device and guides you through actions in Instagram’s genuine website. **You press Follow yourself.** InsFollow refreshes the profile and records only observable results.

This repository is initial development. Build and test evidence, unverified behavior and release gates are recorded in [docs/VALIDATION.md](docs/VALIDATION.md). A compiling APK does not establish live Instagram compatibility or release readiness.

## Features

- Bounded Favorites JSON import through Android’s file picker, with every folder and its membership preserved.
- Folder checkboxes, select/deselect all, reference counts and unique account counts.
- Case-insensitive, whitespace-normalized identities; original usernames and Unicode display names remain intact.
- SQLite account history, transactions, randomized queue without replacement, persisted ordering and interruption recovery.
- One persistent System WebView session, inline expansion and collapse, no custom password collection.
- Assisted follow workflow with isolated English/Turkish DOM inspection, repeated observations and refresh verification.
- Separate following, already-following, requested, failed and uncertain records; confirmed records survive later uncertain observations.
- Optional scan-first mode (default) and direct assisted processing; separate persistent privacy/follower observations, eligibility and review records.
- Configurable follower threshold (default 3,000), public/private filters, conservative rounded-count intervals, scan cache/rescan policies and filtered queues preserving existing order.
- Local-time weekly days and multiple windows per day, midnight crossings, interval range, daily processing-visit target, session target, persisted breaks and navigation-failure stop threshold.
- Start/resume, pause, stop, skip, explicit recheck, and manual shuffle of remaining queue entries.
- Search/filter results; JSON and username-based TXT reports; annotated Favorites copies; local application backup and transactional restore.
- Single Activity, inline collapsible cards, AMOLED black, English and Turkish resources, native Views and minimal dependencies.
- No backend, analytics, advertising, tracking, image downloads or cloud service. Saved data works offline; Instagram access requires the Internet.

## How it works

1. Import an existing Favorites backup. The source document is read only; InsFollow stores a local copy.
2. Select folders. Duplicate usernames share one record, including across imports and folders.
3. Expand **Instagram session**, choose **Sign in / open Instagram**, and log in on Instagram’s website.
4. Configure intervals and operating hours inline. Equal start/end times allow all day. A zero session limit means unlimited. These preferences do not guarantee account safety.
5. Choose **Start / Resume**. An unfinished queue always resumes its saved order. Completed S/A/R/F accounts are excluded from new queues by default.
6. When asked, use the actual Follow control in the browser. Choose **I have acted — refresh and verify** afterward. A press alone is never success.
7. Review verified results and uncertain observations. Recheck a record explicitly when necessary. Rechecking appends work to the current queue without deleting its history.
8. Export reports or a separate annotated Favorites copy through the system file picker.

The browser is initially collapsed. **Home** and **Browser** are inline views in the same Activity. Switching between them changes layout only: it does not pause either engine, reload the profile, recreate the WebView or reset cookies. The collapsed WebView stays attached and visible at a minimal height; Android/WebView may still throttle rendering or scripts. Bounded deadlines never turn missing observations into success. This is not a guarantee of reliable invisible processing on every device. Moving the Activity into the background, locking the screen, rotation/recreation or renderer loss pauses work. Resume manually. No service or uninterrupted background browsing is promised. Android may recreate the WebView after Activity/process/renderer destruction; cookies use its normal local storage. Instagram file inputs use Android's document picker; the workflow pauses for user interaction and resumes manually afterward.

Pause does not change account codes. Stop preserves the queue and history; Start / Resume continues that queue. Process interruption finishes an open operation as U / `INTERRUPTED_VERIFICATION`, preserving any earlier confirmed account code. That queue entry is consumed so a potentially completed action is never blindly repeated; explicitly recheck it if needed. Navigation and verification timeouts are U, never F. Restrictions and login challenges pause immediately with no automatic action retry.

## Scanning, filters and scheduling

Choose **Scan first, then process** (default) or **Direct processing** inline. The default minimum is 3,000 followers, public-only and skip-private are enabled, and automatic transition to assisted processing is disabled. Unknown privacy/counts always require review; this conservative rule cannot be disabled. Disabling both privacy filters explicitly allows known private accounts through the eligibility filter; any sent follow request remains R, never S.

Scanning reads only the visible profile DOM and has no follow executor. It persists a shuffled, unique scan plan and every completed observation. Start resumes that plan after pause/process recovery. Cache choices reuse observations, scan missing records, retry errors/review records, rescan checked result rows or rescan all selected folders. **Start a new scan plan** archives an unfinished plan without deleting observations, then Start uses the chosen policy. Completed observations are not automatically expired; their checked date is shown so the user can choose a rescan. Filter changes recalculate eligibility without changing the observation date/history or any follow result.

The scan summary uses real selected-account database counts. Scan states are `NOT_SCANNED`, `SCANNING`, `ELIGIBLE`, `PRIVATE_ACCOUNT`, `BELOW_THRESHOLD`, `FOLLOWER_COUNT_UNKNOWN`, `PRIVACY_UNKNOWN`, `PROFILE_UNAVAILABLE`, and `SCAN_ERROR`. Eligibility is independently `ELIGIBLE`, `FILTERED`, or `REVIEW`; **FILTERED is not a follow failure**. Reasons include `PRIVATE_ACCOUNT`, `BELOW_FOLLOWER_THRESHOLD`, `FOLLOWER_COUNT_UNAVAILABLE`, `PRIVACY_STATUS_UNKNOWN`, and `ROUNDED_COUNT_NEAR_THRESHOLD`. The existing —/S/A/R/F/U codes stay unchanged.

Exact `3.000`/`3,000` counts are accepted. `3K`, `3.5K`, `3,5 B`, `12.4K` and `1M` produce conservative rounding intervals. `3K` straddles 3,000 and requires review; `3.5K` is safely above it. Ambiguous bare B or compact labels with ambiguous grouping are not guessed. Public evidence requires an identified profile, visible posts and a Follow control; visible posts on an already-followed account alone do not prove public privacy. Empty or unfamiliar layouts can therefore require review.

When a scan finishes, the user starts **Process verified eligible accounts**, or explicitly opts into automatic transition. Follow clicks remain manual in either case. A stopped partial scan can also supply its verified eligible records. Selection filters the existing persistent queue without deleting its records, changing completed outcomes or reshuffling its order. Only observed Follow accounts with eligible facts and no confirmed completed result are acquired. Direct processing and filtered processing both re-read facts twice before requesting an action. A changed/unknown observation goes to review, not a follow attempt.

Weekly windows are optional; otherwise the original daily start/end controls apply. Enable days with checkboxes and enter up to eight comma-separated `HH:mm-HH:mm` windows for each. Blank/disabled days do not start work; overnight windows belong to the previous start day. Equal times mean all day. The daily target counts assisted-workflow profile visits, including failed/interrupted attempts; scanning is reported separately and does not consume that action budget. Session targets count recorded follow-workflow results. At a session boundary a persisted break and counter baseline are stored. Resume manually after the break or the next allowed day while the app is foreground. Missed targets never carry over; local calendar dates/timezone are used. Intervals, targets and breaks provide no platform-safety guarantee.

Additional exports are `scan-results.json`, `eligible-accounts.json`, `filtered-accounts.json`, `scan-errors.json` (review/error records), and `daily-report.json`. Scan reports include privacy, raw display, exact count when available, lower/upper count bounds, checked date, eligibility and reason. Daily reports separate total visits, processing visits, scans, following, requested, failed and uncertain follow outcomes. Schema 2 app backups include scan history/plans; schema 1 databases migrate transactionally and schema 1 app backups remain restorable. Originals and authentication storage are never exported or modified by scanning.

## Supported JSON format

The outer object contains `favorite_page_pref` as a **JSON-encoded string**. Decoding that string yields an array of folders, each with `name` and `items`. Each item has `pageId` (the actual username) and `name` (display text). Fictional example:

```json
{
  "other_setting": true,
  "favorite_page_pref": "[{\"name\":\"FAV\",\"items\":[{\"pageId\":\"fiction.one\",\"name\":\"Example Person\"}]}]"
}
```

Import limits: 8 MiB UTF-8, 32 nesting levels, 2,000 folders, 100,000 item references per source and 100,000 unique accounts in the local database. Empty folders are supported; missing/empty/invalid usernames, wrong types, duplicate JSON fields and malformed input reject the entire import with no partial writes. Usernames must contain 1–30 ASCII letters, digits, periods or underscores. Backups are limited to 32 MiB. These bounds are defensive limits, not a performance guarantee for low-memory devices. Imported backups may include other sensitive settings; application backups contain their immutable source copies. Store exports privately.

Source fingerprints make repeated identical imports idempotent. Changed backups preserve existing account results and add their folder associations. Folder names need not be unique: folders retain source and ordinal identities. New folder selections affect a newly built queue; they do not silently alter an unfinished queue.

Annotated export uses the **most recently imported source**, preserves its outer properties, item fields, pageId, membership and ordering, and changes only copied display-name string tokens. Unrelated numeric literals are preserved exactly. It adds ` S` for S/A and optionally ` F` for F. R/U/unprocessed names are untouched. Every export starts from the immutable source, so exporting it repeatedly adds no duplicate suffix. Original names that already end in ` S` or ` F` are never stripped. Locally generated exports have fingerprint provenance so reimporting the identical exported bytes does not treat the annotation as a new original. An annotated file originating elsewhere cannot be distinguished from an original name reliably; InsFollow does not guess or strip text.

**Target InstaPro import compatibility is not yet verified.** JSON roundtrip tests prove structural preservation only. Test a copy with the specific target version before relying on it; no target version is claimed compatible yet.

## Status codes

| Code | Meaning |
| --- | --- |
| S | Following verified after the assisted interaction |
| A | Already following, verified without a new interaction |
| R | Follow request pending; not an accepted follow |
| F | Confirmed unavailable profile or verified Follow state still absent after interaction |
| U | Uncertain, interrupted, skipped or unverified outcome |
| — | Not processed |

Records include timestamps, attempt counts, operation IDs and machine-readable reasons such as `PROFILE_NOT_FOUND`, `LOGIN_REQUIRED`, `NETWORK_ERROR`, `FOLLOW_REQUEST_PENDING`, `ACTION_RESTRICTED`, `VERIFICATION_TIMEOUT`, `UNKNOWN_PAGE_STATE`, `USER_SKIPPED` and `INTERRUPTED_VERIFICATION`. An unavailable username could have changed; the app cannot establish the person’s identity from an unavailable URL.

Exports: `successful.json` (S/A), `failed.json` (F), `pending.json` (R), `unknown.json` (U/unprocessed), `progress.json` (all accounts plus operations, sessions and queue), and TXT lines such as `fiction.one S`. JSON timestamps are Unix epoch milliseconds. App backups include settings, sources, memberships, accounts and history, **never cookies or authentication storage**. Restore replaces local app records after inline confirmation, validates first, and rolls back on failure. Keep a backup before restoring. Android automatic application backup is disabled.

## Privacy and security

All app-managed data stays in private local storage until you explicitly export it. The only Android permission is `INTERNET`. SAF avoids broad storage permissions. Authentication stays in System WebView’s normal cookie/site storage; InsFollow does not collect credentials, intercept tokens, export cookies, or log passwords. No JavaScript bridge, external script injection, private API emulation, automatic Follow clicks or production remote debugging is included. Read-only inspection uses a bundled script via `evaluateJavascript`. HTTPS main-frame navigation is restricted to Instagram; other destinations are blocked. File/content access, mixed content, popup windows and page permission requests are disabled. System WebView’s normal safe-browsing behavior and Instagram’s own network/privacy behavior remain outside this app’s data model.

## Limitations

- Instagram markup and localization change. Inspection recognizes conservative profile-header controls in English and Turkish, requires an expected URL and profile identity evidence, and treats other states as unknown. Many UI variants may require detector updates.
- A page-finished event is not verification. Bounded polling requires repeated consistent observations; confirmed outcomes require a refreshed profile. This still needs live device validation.
- Invisible/minimal-size WebViews and Android lifecycle restrictions prevent an uninterrupted browsing guarantee. Tab switching does not explicitly pause the engine; actual rendering continuity on physical devices remains unverified. Deadlines preserve uncertainty.
- Automated interactions can violate platform terms. Version 0.1 only requests user actions. Execution, navigation, scheduling and verification are separate components for future authorized work.
- Intervals and session limits do not guarantee account safety. There is no challenge solving, restriction bypass, rate-limit evasion, proxy rotation, fingerprint spoofing or hidden account activity.
- Requests to private accounts are R until accepted. Unavailable profiles may have changed usernames. Website responses and connection failures can remain uncertain.
- Restore supports schema 1 and 2 and must fit the backup limit. No migration from INSFAV applications is performed. No other application’s files or data are modified.

## Development

Use Android Studio with JDK **17**, Android SDK platform **36**, and a compatible SDK Build Tools installation. The project pins Android Gradle Plugin **8.13.2**, Gradle **8.13**, Kotlin **2.2.21**. Android 14/API **34** is the minimum; target/compile API is 36. Application ID is `ai.blackware.insfollow`; versionName `0.1`, versionCode `1`.

```sh
git clone https://github.com/BlackWare-AI/InsFollow.git
cd InsFollow
# Set ANDROID_HOME or create an untracked local.properties with sdk.dir.
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest # Android 14+ emulator/device, fictional local fixtures only
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (development key). Release build output: `app/build/outputs/apk/release/app-release-unsigned.apk` until securely signed. Test results: `app/build/test-results/`; lint: `app/build/reports/lint-results-debug.html`. Do not distribute the unsigned output as a production release.

[Architecture and lifecycle decisions](docs/ARCHITECTURE.md) · [Validation evidence and release gates](docs/VALIDATION.md) · [Signing and release](docs/RELEASING.md).

GitHub Actions compiles, tests and lints PRs and main updates. A separate manually triggered emulator workflow runs native instrumentation. Tag releases require signing secrets and a recorded validation approval; absent credentials deliberately fail the release job. No production signing material is committed.

## Contributing

Community development, bug reports, feature requests, translations and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), [SECURITY.md](SECURITY.md) and [AGENTS.md](AGENTS.md). Use fictional account data in public reports. Contributions enter through pull requests; direct write access is not granted by default.

## License

MIT License. See [LICENSE](LICENSE). The existing repository’s rights holder is retained.

## Disclaimer

InsFollow is an independent, unofficial project and is not affiliated with or endorsed by Instagram or Meta. It provides no account safety guarantee. Users are responsible for authorized use and applicable platform rules.
