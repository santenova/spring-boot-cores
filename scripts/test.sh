#!/usr/bin/env bash
# The verification logic of verify.sh as plain curl pipelines: every endpoint called in the
# order it should answer, each response printed as it arrives.
#
#   scripts/test.sh                                  # http://localhost:8080, core "verify"
#   scripts/test.sh http://host:8080 mycore
#   TEST_QUERY="ocean" scripts/test.sh
#
# verify.sh counts these calls and prints PASS or FAIL; this script is the pipeline itself, so
# a step can be read on its own, piped into jq, or copied out as a single curl command.
set -u

BASE="${1:-http://localhost:8080}"
CORE="${2:-verify}"
QUERY="${TEST_QUERY:-ocean}"
BASE="${BASE%/}"
CORE_INDEX="knowledge_core_$CORE"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say() { printf '\n== %s ==\n' "$*"; }

# Pretty-prints a JSON response when jq is available, and leaves anything else untouched.
pretty() {
  local payload
  payload="$(cat)"
  if command -v jq >/dev/null 2>&1 && printf '%s' "$payload" | jq . >/dev/null 2>&1; then
    printf '%s' "$payload" | jq .
  else
    printf '%s\n' "$payload"
  fi
}

get() { # get <path>
  printf '\nGET %s\n' "$1"
  curl -sS "$BASE$1" | pretty
}

post() { # post <path> <json>
  printf '\nPOST %s %s\n' "$1" "$2"
  curl -sS -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2" | pretty
}

stream() { # stream <path> <json>
  printf '\nPOST %s %s\n' "$1" "$2"
  curl -sS -N -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2"
  printf '\n'
}

say "1  Installation - the service and both dependencies"
get /api/health
get /api/models

say "2  Personas - import the shipped sample, then read the registry"
post /api/personas/import '{"path":"classpath:sample-data/personas.json"}'
get /api/personas
get /api/personas/sentinel

say "3  Chat - one complete answer, then a streamed one, each with and without a persona"
post /api/chat '{"prompt":"Reply with the single word: ready"}'
post /api/chat '{"prompt":"Reply with the single word: ready","persona":"sentinel"}'
stream /api/chat/stream '{"prompt":"Say hello in one short sentence."}'
stream /api/chat/stream '{"prompt":"Say hello in one short sentence.","persona":"oracle"}'

say "4  Cores - the list the UI shows"
get /api/cores

say "5  Ingest - expand, wide net, tag, embed, bulk index"
post /api/ingest "{\"queries\":[\"$QUERY\"],\"coreName\":\"$CORE\",\"embed\":true}"
get "/api/cores/$CORE/mapping"
post "/api/cores/$CORE/mapping" '{}'

say "6  Search - BM25, kNN, hybrid, tag scoped, tag counts"
post /api/search "{\"query\":\"$QUERY\",\"mode\":\"text\",\"k\":5}"
post /api/search "{\"query\":\"$QUERY\",\"mode\":\"knn\",\"k\":5}"
post /api/search "{\"query\":\"$QUERY\",\"mode\":\"hybrid\",\"k\":5}"
post /api/search "{\"tags\":[\"$QUERY\"],\"k\":5,\"cores\":[\"$CORE_INDEX\"]}"
post /api/search/tags "{\"cores\":[\"$CORE_INDEX\"]}"

say "7  RAG - the hybrid hits resolved to their originals"
curl -sS -X POST "$BASE/api/search" -H 'Content-Type: application/json' \
  -d "{\"query\":\"$QUERY\",\"mode\":\"hybrid\",\"k\":5}" > "$TMP/hits.json"
if command -v jq >/dev/null 2>&1; then
  jq -c --arg q "$QUERY" '{query:$q, hits:.hits}' "$TMP/hits.json" > "$TMP/rag.json"
elif command -v python3 >/dev/null 2>&1; then
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); json.dump({"query":sys.argv[2],"hits":d.get("hits",[])}, open(sys.argv[3],"w"))' "$TMP/hits.json" "$QUERY" "$TMP/rag.json"
fi
if [ -s "$TMP/rag.json" ]; then
  printf '\nPOST /api/rag/synthesize with the hits of the hybrid search\n'
  curl -sS -X POST "$BASE/api/rag/synthesize" -H 'Content-Type: application/json' \
    -d @"$TMP/rag.json" | pretty
else
  printf '  SKIP - no jq or python3 to lift the hits array out of the search response\n'
fi

say "8  Automation, export and reporting"
printf '\nPOST /api/ingest/jobs\n'
curl -sS -X POST "$BASE/api/ingest/jobs" -H 'Content-Type: application/json' \
  -d "{\"queries\":[\"$QUERY\"],\"coreName\":\"$CORE\",\"embed\":true}" | pretty
printf '\nGET /api/ingest/jobs\n'
curl -sS "$BASE/api/ingest/jobs" | pretty
printf '\nGET /api/reports/overview\n'
curl -sS "$BASE/api/reports/overview" | pretty
printf '\nPOST /api/rag/chat\n'
curl -sS -X POST "$BASE/api/rag/chat" -H 'Content-Type: application/json' \
  -d "{\"query\":\"$QUERY\",\"mode\":\"hybrid\",\"k\":5,\"cores\":[\"$CORE_INDEX\"]}" | pretty
printf '\nGET /api/export/cores/%s?limit=5\n' "$CORE"
curl -sS "$BASE/api/export/cores/$CORE?limit=5" | head -5

say "9  Cleanup - not run, it would drop the documents"
printf '  curl -X DELETE %s/api/cores/%s\n' "$BASE" "$CORE_INDEX"
