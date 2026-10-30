#!/usr/bin/env bash
# Run with an owner credential that has GitHub repository administration permissions.
set -euo pipefail
repo='BlackWare-AI/InsFollow'
gh api --method PATCH "repos/$repo" --input - <<'JSON'
{"description":"Open-source Android companion for Instagram follow workflows, Favorites JSON integration, WebView verification, and progress tracking.","has_issues":true,"has_discussions":true,"security_and_analysis":{"secret_scanning":{"status":"enabled"},"secret_scanning_push_protection":{"status":"enabled"}}}
JSON
gh api --method PUT "repos/$repo/topics" --input - <<'JSON'
{"names":["android","kotlin","instagram","webview","open-source","favorites","json","follow-manager","amoled"]}
JSON
gh api --method PUT "repos/$repo/vulnerability-alerts"
gh api --method PUT "repos/$repo/automated-security-fixes"
gh api --method PUT "repos/$repo/private-vulnerability-reporting"
