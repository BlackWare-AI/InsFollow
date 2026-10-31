# Changelog

## 0.1 — Unreleased

Fixed workflow stalls on responsive profiles with unreadable facts or Follow controls. Filter/review skips preserve follow codes, configured waiting intervals and persistent queue order; security and unresponsive-browser stops remain enforced.

Initial assisted-workflow implementation: Favorites import and normalization, SQLite operation history, persistent unique shuffled queues, one-screen AMOLED Views interface, English/Turkish resources, a single expandable System WebView, conservative read-only inspection, user action and refresh verification, schedules, recovery, JSON/TXT and annotated exports, local backup/restore, tests and GitHub workflows.

Added optional scan-first (default) and direct filtered workflows, persistent scan plans/history and review states independent of follow codes, conservative English/Turkish follower parsing, privacy/follower filters, cache/rescan policies, eligible queue selection without order loss, scan JSON exports and schema 1→2 migration/backward backup restore. Added inline Home/Browser views sharing one uninterrupted WebView instance, genuine document-input handling, weekly multiple windows, daily/session targets, persisted breaks and daily reports. Follow actions remain user controlled; physical-device and live Instagram validation of these additions is pending.

Production release is gated on signing, supported-device lifecycle checks, live Instagram verification and target InstaPro import compatibility. See docs/VALIDATION.md. No automatic Follow execution or background/collapsed processing guarantee is included.
