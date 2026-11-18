# spring-boot-cores

Spring Boot port of the Tactical knowledge core: ingest documents out of Elasticsearch into
vector knowledge cores, search them with BM25, kNN or hybrid RRF, and synthesise cited
answers through an OpenAI-compatible Ollama endpoint.

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
    SERVER_PORT          8080

More clusters: append entries under tactical.elastic.endpoints and pick one with ES_ACTIVE.

## Verify the installation

One script runs every pipeline below in order and prints PASS or FAIL with the HTTP status:

    scripts/verify.sh                                  # http://localhost:8080, core "verify"
    scripts/verify.sh http://host:8080 mycore
    VERIFY_QUERY="ransomware" scripts/verify.sh

The same pipelines by hand, in the order they should pass:

    # 1  service up, both dependencies reachable
    curl -s http://localhost:8080/api/health
    curl -s http://localhost:8080/api/models

    # 2  a complete answer, then a streamed one
    curl -s -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
      -d '{"prompt":"Reply with the single word: ready"}'
    curl -sN -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" \
      -d '{"prompt":"Say hello in one short sentence."}'

    # 3  no cores yet on a fresh install
    curl -s http://localhost:8080/api/cores

    # 4  ingest a core: expand, wide net, tag, embed, bulk index
    curl -s -X POST http://localhost:8080/api/ingest -H "Content-Type: application/json" \
      -d '{"queries":["malware analysis"],"coreName":"verify","embed":true}'

    # 5  the core exists, and its vector field is readable
    curl -s http://localhost:8080/api/cores/verify/mapping
    #    repair a core whose mapping predates embeddings
    curl -s -X POST http://localhost:8080/api/cores/verify/mapping

    # 6  BM25, kNN and hybrid - one body, three modes
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"query":"ransomware behaviour","mode":"text","k":5}'
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"query":"ransomware behaviour","mode":"knn","k":5}'
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"query":"ransomware behaviour","mode":"hybrid","k":5}'

    # 7  tag scoped kNN, then the tag counts behind the navigation
    curl -s -X POST http://localhost:8080/api/search -H "Content-Type: application/json" \
      -d '{"tags":["malware analysis"],"cores":["knowledge_core_verify"]}'
    curl -s -X POST http://localhost:8080/api/search/tags -H "Content-Type: application/json" \
      -d '{"cores":["knowledge_core_verify"]}'

    # 8  RAG - paste the hits array of the hybrid response into the body
    curl -s -X POST http://localhost:8080/api/rag/synthesize -H "Content-Type: application/json" \
      -d '{"query":"ransomware behaviour","hits":[]}'

    # 9  drop the test core
    curl -s -X DELETE http://localhost:8080/api/cores/knowledge_core_verify

Modes are text (BM25), knn (dense vectors) and hybrid (both, fused with RRF); a search body
carrying tags becomes the tag-scoped kNN search. Each stream frame is an SSE data line with
either a delta or a final done event carrying the token count.

## Endpoints

    GET    /api/health
    GET    /api/models
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

## Troubleshooting

- "No core in scope has a vector field": the core was ingested with embed false, or its
  mapping predates embeddings. POST /api/cores/{name}/mapping adds the field, then re-ingest.
- "Query vector is 768d but the cores store 384d": the embedding model changed. Delete the
  core and ingest it again with the current model.
- 502 naming the Elasticsearch URL: the cluster is unreachable, or it is secured and
  ES_USERNAME / ES_PASSWORD are empty.
- "Text fields are not optimised for aggregations": the tags aggregation retries on
  tags.keyword automatically; a core built by an old version may need a reindex.
