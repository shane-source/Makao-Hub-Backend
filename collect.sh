#!/usr/bin/env bash
# Usage: ./collect.sh <1|2|3|4> > batchN.txt
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

case "${1:-}" in
  1) pat='(User|Role|AccountStatus|Audit)[^/]*\.java$|db/migration/.*\.sql$' ;;
  2) pat='(SecurityConfig|Jwt|RefreshToken|Auth|Register|Login|Google|PasswordEncoder)[^/]*\.java$' ;;
  3) pat='(Profile|Avatar|Phone|Payout|Preference|Roommate)[^/]*\.java$' ;;
  4) pat='(^|/)pom\.xml$|application[^/]*\.(yml|yaml|properties)$|src/test/.*\.java$' ;;
  *) echo "usage: $0 <1|2|3|4>" >&2; exit 1 ;;
esac

redact() {
  sed -E \
    -e '/-----BEGIN [A-Z ]*PRIVATE KEY-----/,/-----END [A-Z ]*PRIVATE KEY-----/c\[REDACTED PRIVATE KEY]' \
    -e 's/((password|passwd|secret|token|api[-_]?key|client[-_]?secret|private[-_]?key|credential)[A-Za-z_.-]*[[:space:]]*[=:][[:space:]]*).+/\1<REDACTED>/I' \
    -e 's/eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*/<JWT>/g' \
    -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/<email>/g' \
    -e 's/(\+?254|0)[17][0-9]{8}/<phone>/g'
}

git ls-files \
  | grep -Ei "$pat" \
  | grep -Eiv '\.(env|pem|key|p12|jks)$|backend\.env' \
  | sort \
  | while read -r f; do
      printf '\n===== %s =====\n' "$f"
      redact < "$f"
    done