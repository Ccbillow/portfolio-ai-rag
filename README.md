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
Question → Rule-based gate (QuestionClassifier, 0 LLM cost)
  → Company focus extraction + pronoun rewrite (from history) + sub-project expansion
  → Strategy select (RetrievalStrategySelector) → one of:
      • BEHAVIORAL        intent-aware hybrid + Cohere rerank + company allowlist
      • FACTUAL_NO_RERANK hybrid only (BM25 wins on dates/logistics)
      • FACTUAL_EXPERIENCE hybrid + rerank (no score cutoff)
      • MULTI_COMPANY     per-company search + diversity (no global rerank)
  → Prompt assembly (format = f(type, strategy)) → Claude Haiku (SSE streaming)
```

**Ingestion pipeline:**

```
Upload → Tika parse
	→ Chunk split (markdown-heading → semantic fallback, 1000/150)
	→ Metadata enrichment (Claude tags: intent/behavioral/type/project/skills)
	→ Document-level summary: one Claude summary chunk per document
	→ [Contextual Retrieval: context prefix per chunk — implemented, disabled by default]
	→ OpenAI embed (dense) + BM25-IDF vectorize (sparse) + company tagging
	→ Qdrant upsert → IDF corpus update
```

---

## Key Design Decisions

**Hybrid search (dense + BM25-IDF)** — [details](docs/design/retrieval-strategy.md)
Pure dense search misses exact technical terms ("SofaBoot", "MAT heap dump"). BM25 sparse vectors stored in the same Qdrant point give exact-match recall without Elasticsearch. IDF weights are computed across the corpus and persisted to `idf_corpus.json`, so rare technical terms score higher than common ones. Qdrant fuses both via RRF in-database.

**Per-question retrieval strategy** — [details](docs/design/retrieval-strategy.md)
Instead of one fixed pipeline, a `RetrievalStrategySelector` routes each question to the strategy that fits it — factual questions lean on BM25 with no rerank cutoff, behavioral questions use intent-boosted hybrid + strict rerank, and career-spanning questions do a per-company search so every employer is represented (a global rerank would just favor the richest-vocabulary company). Each strategy is a small, independently testable unit.

**Intent-aware retrieval (metadata enrichment)** — [details](docs/design/ingestion-pipeline.md)
At ingest, Claude tags each chunk with the interview intents it answers (architecture, leadership, production_issue…) plus behavioral tags, chunk type, project, and skills — done once, amortized over every query. At query time the question is classified into the same intents, which boost matching chunks in Qdrant (`should` clause).

**Contextual Retrieval** *(implemented, disabled by default)* — [details](docs/design/ingestion-pipeline.md)
At index time Claude can generate a 1-2 sentence context prefix per chunk (company, project, time) prepended to the embedding text. Off by default — the extra per-chunk Claude call isn't justified at the current corpus size.

**Document-level summary** — [details](docs/design/ingestion-pipeline.md)
One Claude-generated summary chunk per document is added to the Qdrant collection at ingest time (a single summary level — not a recursive RAPTOR tree). Broad questions ("tell me about your Alipay experience") hit this summary chunk instead of needing to assemble context from many smaller chunks.

**Cohere reranking** — [details](docs/design/retrieval-strategy.md)
Retrieves 10 candidate chunks from Qdrant, reranks via cross-encoder (query+document scored jointly), keeps top 5. Cuts prompt noise vs. passing all candidates to the LLM, with better precision than cosine-score ordering.

**Rule-based router** — [sequence diagram](docs/sequence-diagrams/question-classifier-flow.mmd)
Classifies questions (INVALID / OUT_OF_SCOPE / STRATEGIC / CONTACT_REDIRECT / PERSONAL / FACTUAL / TECHNICAL / BEHAVIORAL) before any vector search. Gate types (small talk, out-of-domain, salary, contact, personal) short-circuit with zero API cost; the rest reach retrieval. The type drives both the retrieval strategy and the prompt hint injected downstream.

**Company isolation** — [details](docs/design/company-isolation.md)
Each chunk carries a `companies[]` tag (heading/filename/text + parent derivation). At query time Qdrant filters to the focus company; a post-rerank Java allowlist keeps only chunks whose labels fall within `{focus} ∪ siblings ∪ parent` (siblings under the same Deloitte engagement are allowed, unrelated employers dropped); finally a prompt-level guard tells Claude to use only the focus company's facts.

**Prompt design** — [details](docs/design/prompt-design.md)
All prompts live in MySQL and are hot-reloadable without redeployment. Type-specific hints (FACTUAL / TECHNICAL / BEHAVIORAL) are injected per request based on classifier output, giving each question type its own length, format, and grounding rules.

**Anti-hallucination** — 
Hard refusal rule in the prompt: if evidence is absent from the retrieved context, the model must say so. Company-scoped retrieval ensures cross-company facts don't leak into unrelated answers.

---

## Tech Stack

| Layer | Choice |
|-------|--------|
| Backend | Spring Boot 3.2, Java 17 |
| AI integration | LangChain4j |
| Vector store | Qdrant (named dense + sparse vectors) |
| Embedding | OpenAI text-embedding-3-small (1536d) |
| Sparse vectorizer | Custom BM25-IDF (in-process, corpus stats persisted to disk) |
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
src/main/java/com/simon/rag/   # package-by-feature
├── config/          # Spring beans: Qdrant, embedding, security, RAG properties, executors
├── controller/      # REST endpoints: auth, chat (SSE), documents, admin
├── service/
│   ├── impl/                 # ChatServiceImpl, DocumentServiceImpl, Auth/Eval
│   ├── retrieval/            # RetrievalService, strategy/, query/ (classifiers), rerank/
│   ├── ingestion/           # IngestionRunner + SRP stages (split, enrich, summarize, assemble)
│   ├── vectorstore/         # QdrantSearchService, HybridSearcher, SparseVectorizer, Embedding
│   ├── prompt/              # PromptBuilder, AnswerFormatResolver, PromptTemplateService
│   └── cache/               # Redis session/history
├── llm/             # LlmClient + LlmThrottle (shared Claude rate timeline)
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

**Prerequisites:** Docker, Java 17, Maven

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
| [ingestion-pipeline.md](docs/design/ingestion-pipeline.md) | Ingestion stages: chunking, metadata enrichment, document-level summary, Contextual Retrieval |
| [retrieval-strategy.md](docs/design/retrieval-strategy.md) | Strategy pattern, intent-aware hybrid search, reranking, company filtering |
| [company-isolation.md](docs/design/company-isolation.md) | Company tagging, focus extraction, allowlist filter, parent-child config |
| [prompt-design.md](docs/design/prompt-design.md) | Prompt structure, type hints, evolution from monolithic to DB-managed |
| [tradeoffs.md](docs/design/tradeoffs.md) | Why Qdrant, why Haiku, chunk size evolution, all config decisions |

**Engineering** — how it was built

| Doc | What it covers |
|-----|---------------|
| [debugging-cases.md](docs/engineering/debugging-cases.md) | Real bugs: symptom → root cause → fix |
| [performance-notes.md](docs/engineering/performance-notes.md) | Latency, token cost, retrieval quality |
| [dev-plan.md](docs/engineering/dev-plan.md) | Development phases (completed through Phase 14 engineering hardening) |

**Interview**

| Doc | What it covers |
|-----|---------------|
| [storytelling.md](docs/interview/storytelling.md) | Interview prep: hardest parts, debugging approach, scalability |
