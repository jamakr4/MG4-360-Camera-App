#!/usr/bin/env bash
# Permission-drift gate.
# Fails when a manifest under app/src declares a permission that is not in
# permission-allowlist.txt. This app runs as the system user on the head unit, so a
# new permission is a new capability on the car and must be reviewed, not slipped in.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALLOWLIST="$ROOT/.github/security/permission-allowlist.txt"

declare -A allow_set=()
while IFS= read -r p; do
  allow_set["$p"]=1
done < <(grep -vE '^[[:space:]]*(#|$)' "$ALLOWLIST" | tr -d '\r' | sed 's/[[:space:]]*$//')

fail=0
while IFS= read -r -d '' manifest; do
  while read -r perm; do
    [ -z "$perm" ] && continue
    if [ -z "${allow_set[$perm]:-}" ]; then
      echo "::error file=${manifest#"$ROOT/"}::Permission not in the allowlist: $perm (add it to .github/security/permission-allowlist.txt with a reason after review)"
      fail=1
    fi
  done < <(grep -oE 'android:name="[^"]+"' "$manifest" \
            | sed -E 's/.*"(.*)"/\1/' \
            | grep -E '^[a-z][a-z0-9_]*(\.[A-Za-z0-9_]+)*\.permission\.' || true)
done < <(find "$ROOT/app/src" -name AndroidManifest.xml -print0)

if [ "$fail" -ne 0 ]; then
  echo "Permission-drift gate: at least one permission is not allowlisted."
  exit 1
fi
echo "Permission-drift gate: every declared permission is allowlisted."
