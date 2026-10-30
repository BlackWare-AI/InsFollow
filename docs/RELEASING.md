# Signing and release

Do not create or publish v0.1 until the acceptance gates in VALIDATION.md are complete. The initial implementation is unreleased. A debug APK is for development only; a locally built unsigned release APK is not a production release.

Configure repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 of the private release keystore.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_PASSWORD`: private-key password.

Keep these values out of logs, source files and artifacts. Retain a secure backup of the signing key for future compatible updates. The release workflow uses environment-based apksigner passwords and deletes temporary material when the job exits.

After recording device, live Instagram and target InstaPro validation evidence, maintainers set repository variable `RELEASE_VALIDATION_APPROVED=true`. Missing credentials or validation approval fail the release job before publishing. This is a release gate, not a claim that tests were run.

Tag the reviewed commit `v0.1` and push the tag only after all requirements pass. The release workflow rebuilds/tests/lints, aligns and signs the APK, verifies its signature, package `ai.blackware.insfollow`, versionName `0.1` and versionCode `1`, records byte size and SHA-256, and attaches the signed APK and checksum to a GitHub Release with notes. It never publishes an unsigned APK.
