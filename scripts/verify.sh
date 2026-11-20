#!/usr/bin/env bash
# End-to-end verification of a running tactical-core: installation, persona import, chat with
# and without a persona, ingest, the three search modes, tag navigation, RAG synthesis, then
# the background ingest job, the overview report, one RAG chat turn and a core export.
#
#   scripts/verify.sh                                  # http://localhost:8080, core "verify"
#   scripts/verify.sh http://host:8080 mycore
#   VERIFY_QUERY="ocean" scripts/verify.sh
#
# Every pipeline prints PASS or FAIL with its HTTP status, and a failure echoes the cluster's
# own error message underneath. The exit code is the number of failed pipelines.
set -u

BASE="${1:-http://localhost:8080}"
CORE="${2:-verify}"
QUERY="${VERIFY_QUERY:-ocean}"
CORE_INDEX="knowledge_core_$CORE"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

say() { printf '\n== %s ==\n' "$*"; }

check() { # check <label> <status> <detail>
  if [ "$2" = "200" ]; then
    pass=$((pass + 1))
    printf '  PASS  %-16s %s\n' "$1" "$3"
  else
    fail=$((fail + 1))
    printf '  FAIL  %-16s %s (HTTP %s)\n' "$1" "$3" "$2"
    sed -n '1,6p' "$TMP/$1.out" 2>/dev/null | sed 's/^/          /'
  fi
}

get() { # get <label> <path>
  local code
  code=$(curl -sS -o "$TMP/$1.out" -w '%{http_code}' "$BASE$2")
  check "$1" "$code" "$2"
}

post() { # post <label> <path> <json>
  local code
  code=$(curl -sS -o "$TMP/$1.out" -w '%{http_code}' -X POST "$BASE$2" \
    -H 'Content-Type: application/json' -d "$3")
  check "$1" "$code" "$2"
}

post_file() { # post_file <label> <path> <file>
  local code
  code=$(curl -sS -o "$TMP/$1.out" -w '%{http_code}' -X POST "$BASE$2" \
    -H 'Content-Type: application/json' -d @"$3")
  check "$1" "$code" "$2"
}

say "1  Installation - the service and both dependencies"
get health /api/health
get models /api/models

say "2  Personas - import the shipped sample, then read the registry"
post personas-import /api/personas/import '{"path":"classpath:sample-data/personas.json"}'
get personas /api/personas
get persona /api/personas/sentinel

say "3  Chat - one complete answer, then a streamed one, each with and without a persona"
post chat /api/chat '{"prompt":"Reply with the single word: ready"}'
post chat-persona /api/chat '{"prompt":"Reply with the single word: ready","persona":"sentinel"}'
code=$(curl -sS -N -o "$TMP/stream.out" -w '%{http_code}' -X POST "$BASE/api/chat/stream" \
  -H 'Content-Type: application/json' -d '{"prompt":"Say hello in one short sentence."}')
check stream "$code" "/api/chat/stream"
if grep -q '"done":true' "$TMP/stream.out" 2>/dev/null; then
  pass=$((pass + 1))
  printf '  PASS  %-16s done frame received\n' "stream frames"
else
  fail=$((fail + 1))
  printf '  FAIL  %-16s no done frame in the stream\n' "stream frames"
fi
code=$(curl -sS -N -o "$TMP/stream-persona.out" -w '%{http_code}' -X POST "$BASE/api/chat/stream" \
  -H 'Content-Type: application/json' -d '{"prompt":"Say hello in one short sentence.","persona":"oracle"}')
check stream-persona "$code" "/api/chat/stream with persona oracle"
if grep -q '"persona":"oracle"' "$TMP/stream-persona.out" 2>/dev/null; then
  pass=$((pass + 1))
  printf '  PASS  %-16s the done frame names the persona\n' "stream persona"
else
  fail=$((fail + 1))
  printf '  FAIL  %-16s the done frame did not name the persona\n' "stream persona"
fi

say "4  Cores - the list the UI shows"
get cores /api/cores

say "5  Ingest - expand, wide net, tag, embed, bulk index"
post ingest /api/ingest "{\"queries\":[\"$QUERY\"],\"coreName\":\"$CORE\",\"embed\":true}"
get mapping "/api/cores/$CORE/mapping"
post mapping-repair "/api/cores/$CORE/mapping" '{}'

say "6  Search - BM25, kNN, hybrid, tag scoped, tag counts"
post search-text /api/search "{\"query\":\"$QUERY\",\"mode\":\"text\",\"k\":5}"
post search-knn /api/search "{\"query\":\"$QUERY\",\"mode\":\"knn\",\"k\":5}"
post search-hybrid /api/search "{\"query\":\"$QUERY\",\"mode\":\"hybrid\",\"k\":5}"
post search-tag /api/search "{\"tags\":[\"$QUERY\"],\"k\":5,\"cores\":[\"$CORE_INDEX\"]}"
post tags /api/search/tags "{\"cores\":[\"$CORE_INDEX\"]}"

# Lifts the hits array out of a search response, with whichever JSON tool the box has.
hits_body() { # hits_body <search.json> <query> <out.json>
  if command -v jq >/dev/null 2>&1; then
    jq -c --arg q "$2" '{query:$q, hits:.hits}' "$1" > "$3"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); json.dump({"query":sys.argv[2],"hits":d.get("hits",[])}, open(sys.argv[3],"w"))' "$1" "$2" "$3"
  else
    return 1
  fi
}

say "7  RAG - the hybrid hits resolved to their originals"
if hits_body "$TMP/search-hybrid.out" "$QUERY" "$TMP/rag.json"; then
  post_file rag /api/rag/synthesize "$TMP/rag.json"
else
  printf '  SKIP  %-16s no jq or python3 to lift the hits array\n' "rag"
  printf '          run it by hand: POST %s/api/rag/synthesize with\n' "$BASE"
  printf '          {"query":"%s","hits":[...]} where the array is from /api/search\n' "$QUERY"
fi

say "8  Automation, export and reporting"
post job /api/ingest/jobs "{\"queries\":[\"$QUERY\"],\"coreName\":\"$CORE\",\"embed\":true}"
get jobs /api/ingest/jobs
get report /api/reports/overview
post rag-chat /api/rag/chat "{\"query\":\"$QUERY\",\"mode\":\"hybrid\",\"k\":5,\"cores\":[\"$CORE_INDEX\"]}"
code=$(curl -sS -o "$TMP/export.out" -w '%{http_code}' "$BASE/api/export/cores/$CORE?limit=5")
check export "$code" "/api/export/cores/$CORE?limit=5"

say "9  Cleanup - not run, it would drop the documents"
printf '  curl -X DELETE %s/api/cores/%s\n' "$BASE" "$CORE_INDEX"

say "Result"
printf '  %s passed, %s failed\n' "$pass" "$fail"
exit "$fail"
