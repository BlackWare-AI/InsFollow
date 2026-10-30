# Physical-device validation for scan-first 0.1

No physical-device result is claimed by this document. Record Android build, device model,
System WebView provider/version, app commit, locale and test date in a private test report.
Use only owned/authorized test accounts. Never attach real Favorites lists, cookies,
authentication screenshots or account tokens to public issues.

1. Export an InsFollow app backup before installing a differently signed build. A signing-key
   mismatch may require uninstalling; never clear app data without preserving that backup.
2. Install the reviewed debug APK on Android 14+; restore the backup and verify existing
   result codes, queue order, source membership and settings after schema migration.
3. Sign in on the genuine Instagram page. Complete any verification manually. InsFollow
   must pause on login/security restrictions and must not solve or repeatedly retry them.
4. Import a small authorized fixture including a duplicate across folders, a public account,
   a private account, an unavailable username, and known above/below-threshold accounts.
5. Leave scan-first/default filters enabled. Start, switch Home/Browser repeatedly and verify
   the same page/session remains visible, no reload is caused by the switch, and account
   navigation continues. Log timings; a spinner alone is not evidence of usable DOM state.
6. Check exact and compact displayed counts against the live page. `3K` at a 3,000 threshold
   must require review; exact 3,000 may pass only with reliable public-profile evidence.
   Unknown or conflicting privacy must never enter eligible processing. Scanning must not
   activate any Follow control.
7. Pause, stop, Home/app-switch, lock/unlock, rotate, force-stop/relaunch, reboot and exercise
   memory/renderer pressure. Completed scans/results must survive; interrupted scans resume
   their persisted position without resetting the queue. An uncertain follow operation must
   not be blindly replayed. Foreground loss requires manual resume.
8. Finish the scan with auto-transition disabled, then start eligible processing explicitly.
   Repeat with opt-in auto-transition. Both paths must use the same login. An unfinished
   stopped scan may supply only completed, verified eligible records. Only the user presses
   Follow; refreshed Following and Requested remain distinct outcomes.
9. Change threshold/privacy filters; verify recalculated eligibility and intact observation
   dates/history/follow results. Exercise each cache/rescan policy and checked-account list.
10. Test disabled weekdays, multiple windows, overnight endings, daily target reset, session
    target/break persistence and timezone/date changes. No work starts outside the schedule;
    missed targets are not added to the next day. Resume only in the foreground.
11. Exercise genuine file-input handling only when needed for a legitimate account challenge.
    The system picker must return only explicitly chosen documents to the same WebView;
    cancellation must be safe. Activity destruction cancels a pending chooser callback.
12. Export all reports and app backup; check counts, follower intervals, privacy, timestamps,
    eligibility/reasons and separate follow codes. Verify the original Favorites bytes remain
    unchanged. Test annotated copies in the exact target InstaPro version separately.

If a collapsed WebView stops delivering observations on a device, record the provider/version
and reproduction. Do not classify that lack of activity as follow success. Keep the browser
expanded for manual testing and report the collapsed mode as unsupported on that configuration
until the lifecycle behavior is fixed and verified.
