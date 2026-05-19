# Development Plan

## ✅ Phase 1 — Project Skeleton

- Spring Boot + Docker Compose (Qdrant, MySQL, Redis)
- Basic project structure: controller / service / config layers
- `.env`-driven config, multi-profile (local / prod)

---

## ✅ Phase 2 — Core RAG Pipeline

- Document upload: Apache Tika parse → character split → OpenAI embed → Qdrant upsert
- Chat: embed query → Qdrant dense search → Claude Haiku → SSE streaming
- MySQL schema: `rag_document` (upload metadata), `rag_chat_history`
- Async ingestion with Redis status polling

---

## ✅ Phase 3 — Retrieval Enhancements

- Redis conversation history (last N turns injected into prompt)
- Sentence window expansion: fetch ±1 neighbor chunk for more context
- Multi-query expansion: Claude generates 2–3 query variants before retrieval

---

## ✅ Phase 4 — Security & Rate Limiting

- JWT stateless auth (HMAC-SHA256, 24h expiry)
- RBAC: `ROLE_ADMIN` for document management; chat API public
- Bucket4j per-IP rate limiting (5 req/min, 20 req/day)

---

## ✅ Phase 5 — Reranker + Hybrid Search

- **Phase 5.1:** **Cohere rerank-v3.5** — retrieve 10 candidates, keep top 5 by cross-encoder score
- **Phase 5.2:** Custom **BM25** `SparseVectorizer` (in-process, no Elasticsearch); Qdrant Query API with RRF fusion; dense + sparse vectors in one point

---

## ✅ Phase 6 — Config & Deployment Hardening

- MySQL tuning (`my.cnf`), Docker Compose prod profile
- Nginx reverse proxy with SSL termination, 60s SSE timeout
- `application-prod.yml` with externalized secrets

---

## ✅ Phase 7 — Prompt Management

- MySQL table: `prompt_template`; hot-reload via admin API (no redeploy needed)
- **Rule-based Agent Layer** `QuestionClassifier`: INVALID / OUT_OF_SCOPE / STRATEGIC / FACTUAL / TECHNICAL / BEHAVIORAL — zero LLM cost
- Type-specific prompt hints injected at runtime by classifier output

---

## ✅ Phase 8 — Frontend Integration + Company Isolation

- Company Isolation
	- `companies[]` array payload on every Qdrant point; config-driven extraction from filename + chunk text
	- Qdrant pre-filter (`should: match.any`) + post-rerank Java allowlist filter
	- `company-subgroups` config for parent-child hierarchy (Deloitte → OCBC / Sanofi)
- Frontend integration: SSE streaming, source chips, conversation history display
- Session turn limit (30/session), question length cap (300 chars)
- Multiple prompt tuning rounds driven by manual baseline tests

---

## ✅ Phase 9 — Cost Optimization + Refinement

- Disabled multi-query expansion: 2 Claude calls → 1 per question (~50% cost reduction)
- Removed sentence window expansion; increased chunk size 512 → 1000 chars to compensate
- Reduced conversation history 3 turns → 2


---

## ✅ Phase 10 — Ingestion & Retrieval Optimization

Ingestion

- Contextual Retrieval: Claude-generated context prefix prepended to each chunk at index time; improves dense embedding quality;
- Contextual Retrieval parallelization: `CompletableFuture` + `Semaphore(3)` replaces sequential per-chunk Claude calls; reduces ingestion wall-clock time
- Semantic chunking: replaced fixed character splitter with paragraph-aware splitter; falls back to sentence boundaries for oversized paragraphs; prevents mid-sentence cuts
- **RAPTOR document summary**: one Claude-generated summary chunk per document at ingest time (`chunkType=document_summary`); improves recall for broad questions

Retrieval

- **BM25 IDF**: `SparseVectorizer` upgraded from TF-only to full BM25; corpus stats persisted to `idf_corpus.json`

---

## 🔜 Phase 11 — Auto Evaluation Framework

- Locked eval set: Prevent benchmark overfitting, isolate the evaluation set from the tuning process.
- Baseline Diff script: Surfaces regressions instantly by showing only changed cases, instead of reading the full report.
- LLM-as-Judge: Catches silent regressions that pass keyword checks but fail semantically.

---

## 🔜 Phase 12 — Observability

- Structured JSON logging: `sessionId`, `questionType`, `focusCompany`, `numHits`, `rerankScores`, `latencyMs`
- Prometheus + Grafana: retrieval hit rate, rerank score distribution, LLM latency p50/p95; add containers to existing Docker Compose

---

## 🔜 Phase 13 — Admin Dashboard

Knowledge Base
- Document list: upload, status (pending/processing/failed/completed), retry on failure
- Document detail: chunk count, company tags, file metadata
- Delete document

Prompt Management
- View and inline-edit all prompt templates (API already exists, UI only)
- Hot-reload without restart

Token Usage
- Per-day API token cost tracking (OpenAI + Cohere/Haiku); requires new MySQL table

---

## Backlog

- Redis-backed rate limiting (required for multi-instance scale-out)
- [Ingestion] Document versioning: re-upload replaces old chunks automatically
- [Ingestion] Duration queries: extract tenure dates from MySQL structured fields, append to context
- [Retrieval] Semantic answer caching (embedding similarity, not MD5)
- [Generation] Multi-company comparison: detect "compare X vs Y", run two scoped retrieval pipelines, merge contexts
- [Evaluation] Thumbs up/down feedback; stored in MySQL for retrieval quality analysis
- [Evaluation] CI gate: run smoke test on PR via GitHub Actions, block merge on failure
- [Evaluation] Baseline eval runner: trigger from `Admin Dashboard`, persist run results in MySQL
- [Evaluation] Eval diff UI: select two baseline runs, visualize regression/improvement in `Admin Dashboard`
- [Frontend] Source chunk expander: click source chip to view full chunk text

**High cost — skip for now:**

- [Retrieval] HyDE: LLM generates a hypothetical answer first, embed that for retrieval
- [Retrieval] Dual index / multi-path recall: separate dense + sparse indexes, merge client-side (Qdrant RRF already covers this)
- [Routing] Advanced routing: LLM Classifier / Ambiguity Detection
