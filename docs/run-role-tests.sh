#!/usr/bin/env bash
# Runs against synthetic data in a temporary local PostgreSQL cluster, never Supabase.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
pg_bin="${MAKAO_PG_BIN:-/usr/lib/postgresql/15/bin}"
test_port="${MAKAO_ROLE_TEST_PORT:-56439}"
if [[ ! "$test_port" =~ ^[0-9]+$ ]] || ((test_port < 1024 || test_port > 65535)); then
    echo 'MAKAO_ROLE_TEST_PORT must be between 1024 and 65535.' >&2
    exit 1
fi
test_root="$(mktemp -d /tmp/makao-role-tests.XXXXXX)"
test_user="$(id -un)"
cleanup() {
    if [[ -f "$test_root/data/postmaster.pid" ]]; then
        "$pg_bin/pg_ctl" -D "$test_root/data" -m fast -w stop
    fi
    printf 'Synthetic test database and logs retained at: %s\n' "$test_root"
}
trap cleanup EXIT
"$pg_bin/initdb" -D "$test_root/data" -A trust -E UTF8 --no-locale
"$pg_bin/pg_ctl" -D "$test_root/data" -l "$test_root/server.log" \
    -o "-h 127.0.0.1 -p $test_port -k $test_root" -w start
"$pg_bin/createdb" -h 127.0.0.1 -p "$test_port" -U "$test_user" makao_role_test
cd "$project_dir"
MAKAO_ROLE_TEST_DB_URL="jdbc:postgresql://127.0.0.1:$test_port/makao_role_test" \
MAKAO_ROLE_TEST_DB_USER="$test_user" MAKAO_ROLE_TEST_DB_PASSWORD='' \
    ./mvnw -o '-Dtest=*Test' test
