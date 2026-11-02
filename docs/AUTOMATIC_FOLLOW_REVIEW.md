# Automatic Follow review and remediation — 2026-11-02

Historical review (before the remediation below). Reviewed application source: `617bfe5` (main at review start). Test source: `f919c50`.
Production application code was not changed by this review. No real Instagram pages,
authenticated sessions or follow operations were used.

## Results

The existing main CI run [41386743305](https://github.com/BlackWare-AI/InsFollow/actions/runs/41386743305)
passed. Downloaded reports contain 72 instrumentation tests on each of API 34 and 36.
Those tests cover fake-browser automatic-attempt/verification behavior but did not exercise
the actual click script against changing or ambiguous DOM states.

The review adds nine tests executing the bundled `click_follow.js` in actual Android
System WebView against local fictional HTML. In
[review run 41387760073](https://github.com/BlackWare-AI/InsFollow/actions/runs/41387760073),
downloaded JUnit reports from **both API 34 and 36 contain 81 tests: 78 passed, 3 failed,
zero errors/skips**. The overall workflow failed. The API 34 job was subsequently marked
cancelled by matrix fail-fast; its uploaded XML nevertheless contains the complete 81-test
report with the same three assertion failures. It is not described as a passing job.

Local compilation of debug, unsigned release and instrumentation APKs, JVM tests and lint
passed. The added class is present in the local test APK's classes2.dex. CI build validation
also passed. Compilation and passing pre-existing tests do not resolve the failures below.

## Confirmed dispatch-time guard gaps

All three failures returned `{"status":"CLICKED","clicks":1,"trusted":false}` where
no click was expected:

| Fixture / failing test | Observed issue |
| --- | --- |
| `testInlineRestrictionDoesNotClick` | A visible "Try again later / We restrict certain activity" notice does not prevent dispatch. |
| `testLoginOverlayDoesNotClick` | A password/login form on the profile URL does not prevent dispatch. |
| `testConflictingFollowingStateDoesNotClick` | Coexisting Following and Follow controls do not prevent dispatch. |

`click_follow.js` verifies URL, identity and a unique enabled visible Follow label, but does
not recheck login/restriction markers or competing Following/Requested state. The workflow's
earlier read-only inspection normally stops known restrictions; that observation can become
stale before the separate JavaScript click executes. These fixtures establish the click
routine's missing final guards, not that the entire workflow always ignores a restriction,
and not that a real account was followed under one.

English/Turkish normal Follow fixtures each dispatched exactly one event. Wrong URL,
wrong identity, duplicate Follow controls and a disabled control were rejected. The six
passing script fixtures and existing workflow tests provide useful partial evidence only.

## Limits and release conclusion

The event is synthetic (`isTrusted=false`). This proves dispatch in these local fixtures;
it does not establish whether Instagram accepts the event or changes the relationship.
A click callback alone is not success; the existing refreshed verification remains necessary.
No physical device is attached and no live Instagram account was tested. Account challenges,
real layouts, platform authorization/compliance and production signing remain unverified.

Automatic Follow cannot be certified as reliable from the current evidence. Dispatch-time
security/state checks and their failing regressions need resolution before that claim.
The review PR remains a draft with reproducible failing tests; no production fix or APK
release is included.


## Remediation

The dispatch script now checks login/restriction markers and competing relationship states immediately before clicking, revalidates after scrolling, and requires the identical button node. Both English and Turkish restriction casing are recognized. Immediate login/restriction results raised by a click are also surfaced. Exceptions after a possible dispatch return uncertainty.

ProfileBrowser returns typed FollowAttempt outcomes. Only NOT_DISPATCHED permits manual fallback. LOGIN_REQUIRED and RESTRICTED stop with their existing reason codes; UNCERTAIN stops as U / FOLLOW_ACTION_UNCERTAIN without retry or manual-action prompting. Existing refreshed relationship verification is unchanged. No Follow click or acknowledgement alone establishes S.

The original failing fixtures remain, with additional cases for Turkish restrictions, Requested conflicts, Following-only state, DOM changes during scroll and exceptions after dispatch. Workflow fixtures cover propagation of login/restriction/uncertain outcomes, and JVM tests cover conservative script-result mapping. Remediation validation is pending the updated build and CI. No physical-device or live Instagram compatibility is claimed.
