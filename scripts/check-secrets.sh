#!/usr/bin/env bash
# Fails if anything that looks like a secret, a Firebase config file, or PII is tracked/staged.
# Usage: scripts/check-secrets.sh            (scan files that git tracks or has staged)
#        scripts/check-secrets.sh --history  (also scan every commit reachable from HEAD)
# Matches are reported as file:line only; the matching text is never printed.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Patterns (extended regex). Keep in sync with README "Secret hygiene".
patterns=(
  'aind_[0-9a-fA-F]{16,}'                                   # ainder API key
  'AIza[0-9A-Za-z_-]{35}'                                   # Google API key (google-services / plist)
  '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}'  # UUIDv4 (App Check debug token)
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  '"private_key_id"'
  'client_secret'
  'sk-[A-Za-z0-9]{20,}'
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9]{30,}'
  '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'         # e-mail address (PII)
  '01[016789][-. ]?[0-9]{3,4}[-. ]?[0-9]{4}'                # KR mobile number (PII)
)
# E-mail addresses that are allowed to appear (placeholders / license text).
allow_email='(example\.(com|org)|apache\.org|noreply@anthropic\.com)'
forbidden_files='(^|/)(google-services\.json|GoogleService-Info\.plist|local\.properties|secrets\.properties|.*\.(jks|keystore|p12|mobileprovision))$'

fail=0
files=$( { git ls-files; git diff --cached --name-only --diff-filter=AM; } | sort -u)
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if [[ "$f" =~ $forbidden_files ]]; then echo "FORBIDDEN FILE: $f"; fail=1; fi
done <<< "$files"


if [ -n "$files" ]; then
  # shellcheck disable=SC2086
  out=$(while IFS= read -r f; do [ -f "$f" ] && grep -nIH -E '.' "$f" 2>/dev/null; done <<< "$files" | grep -v '^scripts/check-secrets.sh:' || true)
  for p in "${patterns[@]}"; do
    hits=$(grep -E -e "$p" <<< "$out" || true)
    [[ "$p" == *'@'* ]] && hits=$(grep -Ev "$allow_email" <<< "$hits" || true)
    [ -z "$hits" ] && continue
    cut -d: -f1,2 <<< "$hits" | sed "s|^|MATCH [$p] at |"
    fail=1
  done
fi

if [ "${1:-}" = "--history" ] && git rev-parse -q --verify HEAD >/dev/null; then
  for p in "${patterns[@]}"; do
    hits=$(git log -p --all --extended-regexp -G"$p" --format='commit %h' -- . ':!scripts/check-secrets.sh' | grep -E -e "^\+.*$p" || true)
    [[ "$p" == *'@'* ]] && hits=$(grep -Ev "$allow_email" <<< "$hits" || true)
    [ -n "$hits" ] && { echo "HISTORY MATCH [$p]: $(wc -l <<< "$hits" | tr -d ' ') line(s)"; fail=1; }
  done
fi

if [ "$fail" -ne 0 ]; then echo "check-secrets: FAILED"; exit 1; fi
echo "check-secrets: clean ($(wc -l <<< "$files" | tr -d ' ') files${1:+, history})"
