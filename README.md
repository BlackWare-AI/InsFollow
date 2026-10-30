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
- Local-time operating hours, including midnight crossings; configurable interval range, saved session limit and navigation-failure stop threshold.
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

The browser is initially collapsed. **Version 0.1 conservatively pauses processing when collapsed**, because invisible WebView script/render behavior has not been validated on supported devices. Expanding uses the same WebView instance. Start/resume expands it for processing. Moving the Activity into the background, locking the screen, rotation/recreation or renderer loss pauses work. Resume manually. No service or uninterrupted background browsing is promised. Android may recreate the WebView after Activity/process/renderer destruction; cookies use its normal local storage.

Pause does not change account codes. Stop preserves the queue and history; Start / Resume continues that queue. Process interruption finishes an open operation as U / `INTERRUPTED_VERIFICATION`, preserving any earlier confirmed account code. That queue entry is consumed so a potentially completed action is never blindly repeated; explicitly recheck it if needed. Navigation and verification timeouts are U, never F. Restrictions and login challenges pause immediately with no automatic action retry.

## Supported JSON format

The outer object contains `favorite_page_pref` as a **JSON-encoded string**. Decoding that string yields an array of folders, each with `name` and `items`. Each item has `pageId` (the actual username) and `name` (display text). Fictional example:

```json
{
  "other_setting": true,
  "favorite_page_pref": "[{\"name\":\"FAV\",\"items\":[{\"pageId\":\"fiction.one\",\"name\":\"Example Person\"}]}]"
}
```

Import limits: 8 MiB UTF-8, 32 nesting levels, 2,000 folders, 100,000 item references per source and 100,000 unique accounts in the local database. Empty folders are supported; missing/empty/invalid usernames, wrong types and malformed input reject the entire import with no partial writes. Usernames must contain 1–30 ASCII letters, digits, periods or underscores. Backups are limited to 32 MiB. These bounds are defensive limits, not a performance guarantee for low-memory devices. Imported backups may include other sensitive settings; application backups contain their immutable source copies. Store exports privately.

Source fingerprints make repeated identical imports idempotent. Changed backups preserve existing account results and add their folder associations. Folder names need not be unique: folders retain source and ordinal identities. New folder selections affect a newly built queue; they do not silently alter an unfinished queue.

Annotated export uses the **most recently imported source**, preserves its outer properties, item fields, pageId, membership and ordering, and changes only copied display names. It adds ` S` for S/A and optionally ` F` for F. R/U/unprocessed names are untouched. Every export starts from the immutable source, so exporting it repeatedly adds no duplicate suffix. Original names that already end in ` S` or ` F` are never stripped. Locally generated exports have fingerprint provenance so reimporting the identical exported bytes does not treat the annotation as a new original. An annotated file originating elsewhere cannot be distinguished from an original name reliably; InsFollow does not guess or strip text.

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
- Invisible WebViews, Activity lifecycle and Android background restrictions prevent an uninterrupted browsing guarantee. Collapsed processing is intentionally unsupported pending testing.
- Automated interactions can violate platform terms. Version 0.1 only requests user actions. Execution, navigation, scheduling and verification are separate components for future authorized work.
- Intervals and session limits do not guarantee account safety. There is no challenge solving, restriction bypass, rate-limit evasion, proxy rotation, fingerprint spoofing or hidden account activity.
- Requests to private accounts are R until accepted. Unavailable profiles may have changed usernames. Website responses and connection failures can remain uncertain.
- Restore supports schema 1 only and must fit the backup limit. No migration from INSFAV applications is performed. No other application’s files or data are modified.

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
