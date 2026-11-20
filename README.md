# tactical-core

Spring Boot port of the Tactical knowledge core: ingest documents out of Elasticsearch into
vector knowledge cores, search them with BM25, kNN or hybrid RRF, and synthesise cited
answers through an OpenAI-compatible Ollama endpoint. Chat takes an optional persona,
imported from a JSON file, so one body switches the voice of the answer.

Two local services are all it needs: Ollama (OpenAI-compatible API on port 11434) and
Elasticsearch (REST on port 9200). No cloud account, no vendor SDK.

## Requirements

    JDK 21 or newer (17 also builds: lower java.version in pom.xml)
    Maven 3.9 or newer
    Ollama with one chat model and one embedding model pulled
    Elasticsearch 8.x holding at least one source index

## Start the dependencies

    ollama serve
    ollama pull llama3
    ollama pull nomic-embed-text

Elasticsearch, if you do not already run one:

    docker run --rm -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false docker.elastic.co/elasticsearch/elasticsearch:8.15.0

## Build

    mvn -q clean package

The jar lands in target/tactical-core-0.1.0.jar.

## Run

    java -jar target/tactical-core-0.1.0.jar

or during development, with hot reload of static resources:

    mvn spring-boot:run

The API answers on http://localhost:8080.

## Configure

Every setting has an environment variable with a localhost default in
src/main/resources/application.yml:

    OLLAMA_BASE_URL      http://localhost:11434/v1
    OLLAMA_API_KEY       ollama
    OLLAMA_CHAT_MODEL    llama3:latest
    OLLAMA_EMBED_MODEL   nomic-embed-text
    ES_ACTIVE            local
    ES_URL               http://localhost:9200
    ES_URL_2             http://localhost:9201
    ES_USERNAME          (blank)
    ES_PASSWORD          (blank)
    ES_INDEX_PREFIX      knowledge_core_
    PERSONA_SAMPLE_FILE  classpath:sample-data/personas.json
    PERSONA_AUTO_IMPORT  true
    PERSONA_DEFAULT      (blank)
    SERVER_PORT          8080

More clusters: append entries under tactical.elastic.endpoints and pick one with ES_ACTIVE.

## Verify the installation

One script runs every pipeline below in order and prints PASS or FAIL with the HTTP status:

    scripts/verify.sh                                  # http://localhost:8080, core "verify"
    scripts/verify.sh http://host:8080 mycore
    VERIFY_QUERY="ocean" scripts/verify.sh

scripts/test.sh is the same pipeline as plain curl calls: it counts nothing and prints every
response as it arrives, so one step can be read on its own or piped into jq:

    scripts/test.sh                                    # http://localhost:8080, core "verify"
    scripts/test.sh http://host:8080 mycore
    TEST_QUERY="ocean" scripts/test.sh

The same pipelines by hand, in the order they should pass:

    # 1  service up, both dependencies reachable
    curl -s http://localhost:8080/api/health
    curl -s http://localhost:8080/api/models

    # 2  personas: import the shipped sample, then read the registry
    curl -s -X POST http://localhost:8080/api/personas/import -H "Content-Type: application/json" \
      -d '{"path":"classpath:sample-data/personas.json"}'
    curl -s http://localhost:8080/api/personas

    # 3  a complete answer, then a streamed one - with and without a persona
    curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
      -d '{"prompt":"Reply with the single word: ready"}'
    curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
      -d '{"prompt":"Reply with the single word: ready","persona":"sentinel"}'
    curl -sN -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" \
      -d '{"prompt":"Say hello in one short sentence."}'
    curl -sN -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" \
      -d '{"prompt":"Say hello in one short sentence.","persona":"oracle"}'

    # 4  no cores yet on a fresh install
    curl -s http://localhost:8080/api/cores

    # 5  ingest a core: expand, wide net, tag, embed, bulk index
    curl -s -X POST http://localhost:8080/api/ingest -H "Content-Type: application/json" \
      -d '{"queries":["ocean"],"coreName":"verify","embed":true}'

    # 6  the core exists, and its vector field is readable
    curl -s http://localhost:8080/api/cores/verify/mapping
    #    repair a core whose mapping predates embeddings
    curl -s -X POST http://localhost:8080/api/cores/verify/mapping

    # 7  BM25, kNN and hybrid - one body, three modes
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"query":"ocean","mode":"text","k":5}'
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"query":"ocean","mode":"knn","k":5}'
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"query":"ocean","mode":"hybrid","k":5}'

    # 8  tag scoped kNN, then the tag counts behind the navigation
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"tags":["ocean"],"cores":["knowledge_core_verify"]}'
    curl -s -X POST http://localhost:8080/api/search/tags -H "Content-Type: application/json" \
      -d '{"cores":["knowledge_core_verify"]}'

    # 9  RAG - paste the hits array of the hybrid response into the body
    curl -s -X POST http://localhost:8080/api/rag/synthesize -H "Content-Type: application/json" \
      -d '{"query":"ocean","hits":[]}'

    # 10 drop the test core
    curl -s -X DELETE http://localhost:8080/api/cores/knowledge_core_verify

Modes are text (BM25), knn (dense vectors) and hybrid (both, fused with RRF); a search body
carrying tags becomes the tag-scoped kNN search. Each stream frame is an SSE data line with
either a delta or a final done event carrying the token count.

## Endpoints

    GET    /api/health
    GET    /api/models
    GET    /api/personas
    GET    /api/personas/{id}
    POST   /api/personas
    POST   /api/personas/import
    DELETE /api/personas/{id}
    POST   /api/chat
    POST   /api/chat/stream
    POST   /api/search
    POST   /api/search/tags
    GET    /api/cores
    GET    /api/cores/{name}/mapping
    POST   /api/cores/{name}/mapping
    DELETE /api/cores/{name}
    POST   /api/ingest
    POST   /api/rag/synthesize
    POST   /api/rag/chat
    POST   /api/ingest/jobs
    GET    /api/ingest/jobs
    GET    /api/ingest/jobs/{id}
    DELETE /api/ingest/jobs/{id}
    GET    /api/export/cores/{name}?format=ndjson|csv
    GET    /api/reports/overview

## Automation, export and reporting

Ingestion can run as a background job, so a long core never holds a request open. The submit
answers 202 with a job id, and the job reports queued, running, then done or failed with the
counts and the log lines it produced.

    curl -s -X POST http://localhost:8080/api/ingest/jobs -H "Content-Type: application/json" \
      -d '{"queries":["ocean"],"coreName":"verify","embed":true}'
    curl -s http://localhost:8080/api/ingest/jobs
    curl -s http://localhost:8080/api/ingest/jobs/{id}
    curl -s -X DELETE http://localhost:8080/api/ingest/jobs/{id}

The same job path runs on a timer. Switch it on with INGEST_AUTO_ENABLED=true, name the core
with INGEST_AUTO_CORE, list its topics under tactical.ingest.auto.queries in application.yml,
and every tactical.ingest.auto.delay-ms milliseconds a job is queued.

One call can also carry the whole RAG turn - search, resolve the originals, answer with
citations - and the installation reports itself in one response:

    curl -s -X POST http://localhost:8080/api/rag/chat -H "Content-Type: application/json" \
      -d '{"query":"ocean","mode":"hybrid","k":5,"persona":"sentinel"}'

    curl -s http://localhost:8080/api/reports/overview

A core exports as NDJSON (every field, the vector left out) or CSV (the columns a spreadsheet
reads), limited by ?limit= and capped at 10000 documents:

    curl -s "http://localhost:8080/api/export/cores/verify?format=ndjson&limit=100" > core.ndjson
    curl -s "http://localhost:8080/api/export/cores/verify?format=csv&limit=100" > core.csv

## Personas

A persona is a named directive that leads the message list of a chat turn: who it is, what it
was told to do, how it should sound and what it knows most about. The project ships sample data
in sample-data/personas.json, and the pom declares that directory as a resource, so the file
ends up inside the jar as classpath:sample-data/personas.json.

The sample is imported at startup (PERSONA_AUTO_IMPORT, on by default), which is why the first
chat body can already name a persona. Point PERSONA_SAMPLE_FILE at your own export to load that
instead, or turn auto-import off and drive the registry through the endpoints. The registry is
in memory, so the startup import is what fills it after every restart:

    # create personas from the shipped sample file
    curl -s -X POST http://localhost:8080/api/personas/import -H "Content-Type: application/json" \
      -d '{"path":"classpath:sample-data/personas.json"}'

    # any path works the same way, and a prompt-hub export (Elasticsearch hits, each persona
    # under _source) imports unchanged:  -d '{"path":"/home/me/personas.json"}'
    curl -s http://localhost:8080/api/personas
    curl -s http://localhost:8080/api/personas/sentinel

    # chat as one of them: the id, or the display name, goes in the body
    curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
      -d '{"prompt":"Give me the next action.","persona":"sentinel"}'

    curl -sN -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" \
      -d '{"prompt":"Give me the next action.","persona":"oracle"}'

    # or define one inline, without a file
    curl -s -X POST http://localhost:8080/api/personas -H "Content-Type: application/json" \
      -d '{"name":"Reviewer","instructions":"List the three weakest points of the proposal.","tone":"Blunt"}'

    # a body that names no persona falls back to PERSONA_DEFAULT, so the whole service can run
    # in one voice:  PERSONA_DEFAULT=sentinel java -jar target/tactical-core-0.1.0.jar

The same import is one command, and it prints the registry so the ids are visible:

    scripts/import-personas.sh                                 # the shipped sample
    scripts/import-personas.sh ~/personas.json                 # your own export
    scripts/import-personas.sh ~/personas.json http://host:8080

Importing the same file twice updates the entries in place, so re-running the import is safe.
The chat response names the persona it used, and the done frame of a stream carries it too.
GET /api/health reports how many personas are loaded, so a caller can tell an empty registry
from an unknown id before sending a chat.

## Troubleshooting

- "Unknown persona 'x'": nothing was imported in this run, or the id is wrong |
  POST /api/personas/import, then check the ids with GET /api/personas
- "No core in scope has a vector field": the core was ingested with embed false, or its
  mapping predates embeddings. POST /api/cores/{name}/mapping adds the field, then re-ingest.
- "Query vector is 768d but the cores store 384d": the embedding model changed. Delete the
  core and ingest it again with the current model.
- 502 naming the Elasticsearch URL: the cluster is unreachable, or it is secured and
  ES_USERNAME / ES_PASSWORD are empty.
- "Text fields are not optimised for aggregations": the tags aggregation retries on
  tags.keyword automatically; a core built by an old version may need a reindex.

## Works for me :)

One sequence from a cold machine: free the port, build, start the server in the background,
import the personas, then verify and test against it.

    killall -9 java;
    mvn -q clean package;
    mvn spring-boot:run &
    sleep 5 ;

    bash scripts/import-personas.sh ;

    bash scripts/verify.sh &

    bash scripts/test.sh
