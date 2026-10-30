# Contributing

Bug reports, feature requests, code, translations and documentation are welcome.

1. Fork this repository and clone your fork.
2. Create a focused feature branch: `git switch -c feature/short-description`.
3. Install JDK 17 and Android SDK 36. Configure `ANDROID_HOME` or untracked `local.properties`.
4. Build and validate with `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`.
5. Run `./gradlew connectedDebugAndroidTest` on Android 14+ when changing persistence, WebView or UI behavior. Follow the manual matrix in docs/VALIDATION.md; never test automated real follows against unrelated people.
6. Make small, focused commits and open a pull request describing behavior, validation and any unverified device behavior.

Use Kotlin conventions, four-space indentation, English code comments and documentation, and string resources in English and Turkish. Keep production dependencies minimal. Use one Activity and inline controls; no application modals. Follow AGENTS.md’s architecture and preservation requirements.

Backward compatibility matters: account identities, immutable source backups, folder associations, completed operation history, queue order and confirmed results must survive changes. Add explicit SQLite migrations rather than deleting databases. JSON export changes must preserve existing source properties and pageId values. Do not strip display-name text or reset progress on import.

Do not include credentials, signing files, cookies, user backups, real account lists or private diagnostics in commits or reports. Report security vulnerabilities privately as described in SECURITY.md. Maintainers review contributions through PRs; contributor write access is not automatic.
