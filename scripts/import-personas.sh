#!/usr/bin/env bash
# Imports a persona file into a running tactical-core and prints the registry afterwards, so
# the ids a chat body can name are visible straight away.
#
#   scripts/import-personas.sh                                  # the shipped sample
#   scripts/import-personas.sh sample-data/personas.json
#   scripts/import-personas.sh ~/personas.json http://host:8080
#   PERSONA_BASE_URL=http://host:8080 scripts/import-personas.sh
#
# The file is read by the server, not by this script, so a path has to be readable on the
# machine running the jar. A relative path is made absolute here, because the server's working
# directory is not necessarily yours; a classpath: path is left alone and resolved inside the jar.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILE="${1:-classpath:sample-data/personas.json}"
BASE="${2:-${PERSONA_BASE_URL:-http://localhost:8080}}"
BASE="${BASE%/}"

case "$FILE" in
  classpath:*) ;;
  *)
    [ -f "$FILE" ] || FILE="$ROOT/$FILE"
    if [ ! -f "$FILE" ]; then
      printf 'No persona file at %s\n' "$FILE" >&2
      exit 1
    fi
    FILE="$(cd "$(dirname "$FILE")" && pwd)/$(basename "$FILE")"
    ;;
esac

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

printf 'Importing %s\ninto      %s\n\n' "$FILE" "$BASE"

STATUS="$(curl -sS -o "$TMP" -w '%{http_code}' -X POST "$BASE/api/personas/import" \
  -H 'Content-Type: application/json' -d "{\"path\":\"$FILE\"}")"
cat "$TMP"
printf '\n'

if [ "$STATUS" != "200" ]; then
  printf '\nImport failed (HTTP %s). Is the service running at %s?\n' "$STATUS" "$BASE" >&2
  exit 1
fi

printf '\nRegistry at %s/api/personas:\n' "$BASE"
curl -sS "$BASE/api/personas"
printf '\n'
