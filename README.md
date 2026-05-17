# Portfolio AI RAG

A production-deployed interview assistant that answers questions about my engineering background using Retrieval-Augmented Generation. Interviewers can ask free-form questions; the system retrieves relevant context from my resume and project documents, then generates grounded answers via Claude Haiku.

---

## Architecture

```
Browser ──HTTPS──► Nginx ──► Spring Boot API
                                │
                    ┌───────────┼───────────────┐
                    ▼           ▼               ▼
                 Qdrant       MySQL           Redis
               (vectors)   (metadata,      (sessions,
                           chat history)   ingest status)
                    │
          ┌─────────┼──────────┐
          ▼         ▼          ▼
      OpenAI     Cohere     Anthropic
    (embedding) (rerank)   (Claude Haiku)
```

**Retrieval pipeline:**

```
Question → Classifier (rule-based, 0 LLM cost)
  → Company focus extraction
  → Query rewrite (pronoun resolution from history)
  → Hybrid search: dense + BM25 sparse → Qdrant RRF fusion
  → Cohere cross-encoder rerank (10 candidates → 3)
  → Company scope filter
  → Prompt assembly → Claude Haiku (SSE streaming)
```

**Ingestion pipeline:**

```
Upload → Tika parse → Character split (1000/150)
  → OpenAI embed (dense) + BM25 vectorize (sparse)
  → Company tagging → Qdrant upsert
```

---

## Key Design Decisions

**Hybrid search (dense + BM25)** — [details](docs/design/retrieval-strategy.md)
Pure dense search misses exact technical terms ("SofaBoot", "MAT heap dump"). BM25 sparse vectors stored in the same Qdrant point give exact-match recall without a separate Elasticsearch cluster. Qdrant fuses both via RRF in-database.

**Cohere reranking** — [details](docs/design/retrieval-strategy.md)
Retrieves 10 candidate chunks from Qdrant, reranks via cross-encoder (query+document scored jointly), keeps top 5. Cuts prompt noise vs. passing all candidates to the LLM, with better precision than cosine-score ordering.

**Rule-based agent layer** — [sequence diagram](docs/sequence-diagrams/question-classifier-flow.mmd)
Classifies questions into INVALID / OUT_OF_SCOPE / STRATEGIC / FACTUAL / TECHNICAL / BEHAVIORAL before any vector search. Handles common cases (small talk, out-of-domain, salary questions) with zero API cost. The type also determines which prompt hint is injected downstream.

**Company isolation** — [details](docs/design/company-isolation.md)
Each chunk carries a `companies[]` payload tag. At query time, Qdrant filters to the focus company; a post-rerank allowlist filter handles multi-label chunks and the Deloitte→OCBC/Sanofi sub-project hierarchy.

**Prompt design** — [details](docs/design/prompt-design.md)
All prompts live in MySQL and are hot-reloadable without redeployment. Type-specific hints (FACTUAL / TECHNICAL / BEHAVIORAL) are injected per request based on classifier output, giving each question type its own length, format, and grounding rules.

**Anti-hallucination** — 
Hard refusal rule in the prompt: if evidence is absent from the retrieved context, the model must say so. Company-scoped retrieval ensures cross-company facts don't leak into unrelated answers.

---

## Tech Stack

| Layer | Choice |
|-------|--------|
| Backend | Spring Boot 4, Java 21 |
| AI integration | LangChain4j |
| Vector store | Qdrant (named dense + sparse vectors) |
| Embedding | OpenAI text-embedding-3-small (1536d) |
| Sparse vectorizer | Custom BM25 (in-process, no Elasticsearch) |
| Reranker | Cohere rerank-v3.5 |
| LLM | Claude Haiku (Anthropic) |
| Document parsing | Apache Tika |
| Database | MySQL (metadata + chat history) |
| Cache / sessions | Redis |
| Rate limiting | Bucket4j (per-IP token bucket, in-process) |
| Auth | Spring Security + JWT (stateless, RBAC) |
| Deployment | Docker Compose, Nginx |

---

## Project Structure

```
src/main/java/com/simon/rag/
├── config/          # Spring beans: Qdrant, embedding, security, RAG properties
├── controller/      # REST endpoints: auth, chat (SSE), documents, admin
├── service/
│   └── impl/
│       ├── ChatServiceImpl.java       # Retrieval orchestration
│       ├── IngestionRunner.java       # Async ingestion pipeline
│       ├── QdrantSearchService.java   # Dense + hybrid search, upsert
│       ├── SparseVectorizer.java      # BM25 sparse vector computation
│       ├── CohereRerankService.java   # Cross-encoder reranking
│       ├── QuestionClassifier.java    # Rule-based question gate
│       ├── PromptBuilder.java         # Context + history assembly
│       └── MultiQueryExpander.java    # (disabled in prod)
├── security/        # JWT filter, UserDetails
└── comm/            # Enums, interceptors (rate limit), exceptions
docs/
├── design/          # Architecture, pipelines, technical decisions
├── engineering/     # Debugging cases, performance notes, dev plan
├── interview/       # Interview prep (storytelling)
├── sequence-diagrams/
└── test/
```

---

## Local Development

**Prerequisites:** Docker, Java 21, Maven

```bash
# Copy and fill in API keys
cp .env.example .env

# Start infrastructure (Qdrant, MySQL, Redis)
docker compose up -d qdrant mysql redis

# Run the app
./mvnw spring-boot:run
```

**API:** `http://localhost:8080`
**Qdrant console:** `http://localhost:6333/dashboard`

**Create Qdrant collection** (one-time, run after startup):
```bash
curl -X PUT http://localhost:6333/collections/rag_knowledge \
  -H 'Content-Type: application/json' \
  -d '{
    "vectors": {
      "dense": { "size": 1536, "distance": "Cosine" }
    },
    "sparse_vectors": {
      "sparse": {}
    }
  }'
```

---

## Environment Variables

```env
# Required
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
JWT_SECRET=<random 64-char string>

# Optional
COHERE_API_KEY=...        # reranker (graceful fallback if absent)
RAG_UPLOAD_DIR=/tmp/rag-uploads
```

---

## Deployment (Docker Compose)

```bash
docker compose -f docker-compose.prod.yml up -d
```

Nginx handles SSL termination and proxies to the Spring Boot container on port 8080.

---

## Documentation

**Design** — system architecture and technical decisions

| Doc | What it covers |
|-----|---------------|
| [architecture.md](docs/design/architecture.md) | Component diagram, data flow, DB schema |
| [ingestion-pipeline.md](docs/design/ingestion-pipeline.md) | Full ingestion sequence with config |
| [retrieval-strategy.md](docs/design/retrieval-strategy.md) | Hybrid search, reranking, company filtering |
| [company-isolation.md](docs/design/company-isolation.md) | Company tagging, focus extraction, allowlist filter, parent-child config |
| [prompt-design.md](docs/design/prompt-design.md) | Prompt structure, type hints, evolution from monolithic to DB-managed |
| [tradeoffs.md](docs/design/tradeoffs.md) | Why Qdrant, why Haiku, chunk size evolution, all config decisions |

**Engineering** — how it was built

| Doc | What it covers |
|-----|---------------|
| [debugging-cases.md](docs/engineering/debugging-cases.md) | Real bugs: symptom → root cause → fix |
| [performance-notes.md](docs/engineering/performance-notes.md) | Latency, token cost, retrieval quality |
| [dev-plan.md](docs/engineering/dev-plan.md) | Development phases 1–9 (completed) and 10–14 (planned) |

**Interview**

| Doc | What it covers |
|-----|---------------|
| [storytelling.md](docs/interview/storytelling.md) | Interview prep: hardest parts, debugging approach, scalability |
