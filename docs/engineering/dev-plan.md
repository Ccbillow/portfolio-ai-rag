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
- **Rule-based  Intent Router** `QuestionClassifier`: INVALID / OUT_OF_SCOPE / STRATEGIC / FACTUAL / TECHNICAL / BEHAVIORAL — zero LLM cost
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

## ✅ Phase 11 — Auto Evaluation Framework

- Locked Eval Set: Prevent benchmark overfitting, isolate the evaluation set from the tuning process.

- Baseline Diff Evaluation: Java-based diff runner that highlights only changed responses and severity regressions, making prompt and RAG tuning faster and easier to validate.

- LLM-as-Judge: Uses DeepSeek to semantically evaluate regression candidates that pass keyword checks but degrade in answer quality or factual consistency.

---

## ✅ Phase 12 — Ingestion Optimization (Intent-Aligned Chunking)

Evolved chunking from naive fixed-size -> simple text splitting -> retrieval-oriented segmentation aligned with interview question intent.

- Metadata-aware Chunks: Added structured metadata (company, project, skills, behavioral tags) to improve retrieval precision beyond embeddings.

- Query Intent Classification: Introduced mapping from natural language questions to structured intents (e.g. leadership, production issue, system design).

- Retrieval Filtering: Combined vector search with metadata filters in Qdrant to reduce noise and improve relevance.

- Context Expansion: Expanded retrieved chunks with parent/adjacent context to ensure completeness for STAR-style answers.

---

## ✅ Phase 13 — Retrieval Optimization (Multi-Company Coverage)

- Multi-Company Coverage: type_hint_multi_company_summary/type_hint_multi_company_behavioral

- Prompt Mode Resolution: Derived answer format from existing QuestionType + retrieval strategy

---

## 🔜 Phase 14 — Engineering Hardening

- **Stream cancellation**: stop the LLM stream when the client disconnects (no more wasted tokens)
- **SSE newlines**: fix `\n\n` replacement breaking when the pair is split across tokens
- **Chat history persistence**: write Q&A, model, latency, source doc IDs to `rag_chat_history` (schema exists, write path missing)
- **Flyway**: versioned schema + prompt-template migrations (init scripts only run on first volume creation, so later changes never reach prod)
- **Core unit tests**: `RetrievalStrategySelector`, the 4 strategies, `CompanyScopeResolver`, `QuestionClassifier`
- **CI fix**: trigger on push / PR, use the `test` profile, correct the build job condition
- **Cleanup**: unused dependencies (MiniLM; resilience4j to confirm), `resources/mapper/*.java`, move `RerankedHit` out of `model.eval`
- **Doc alignment**: judge model, Haiku pricing, rename "RAPTOR" → document-level summary and "agent layer" → rule-based router
- Verify: `mvn test` green; CI runs automatically on PR

---

## 🔜 Phase 15 — Eval Quantification

- **Gold chunk labels**: add expected chunks to the 50 eval cases
- **Retrieval metrics**: Recall@k, MRR, hit rate per strategy
- **Ablation table**: dense → hybrid → +rerank → +intent boost; published in README
- **Generation metrics**: judge scores groundedness / completeness on all cases, not only diff candidates
- **CI gate**: unit tests + classifier eval on PR (no Qdrant data or paid API calls needed); block merge on regression
- **Full eval stays local**: scenario eval + judge run via Makefile against the local index; baseline promoted manually
- Verify: README has an ablation table with numbers

---

## 🔜 Phase 16 — Observability

- **Structured JSON logging**: `traceId`, `sessionId`, `questionType`, `strategy`, `numHits`, `rerankScores`, `latencyMs`
- **Metrics**: Micrometer — per-stage latency p50/p95, retrieval hit rate, rerank score distribution
- **Prod: zero new containers** — push metrics via `micrometer-registry-otlp` to Grafana Cloud (free tier); no Prometheus or agent on the server
- **Local: optional compose profile** with Prometheus + Grafana for development and screenshots; off by default
- **Token & cost tracking**: per day and per call type (chat / enrichment / summary), stored in MySQL
- **LLM tracing**: Langfuse Cloud (free tier, not self-hosted); one trace per request, one span per stage; metadata only, no question / answer text
- Verify: a Grafana panel shows which stage made a single request slow; prod server memory unchanged

---

## 🔜 Phase 17 — Resilience & Guardrails

- **`LlmThrottle` redesign**: fixed 3s gap → token bucket with separate interactive / background budgets (prerequisite for Phase 19)
- **Resilience4j**: timeout / retry / circuit breaker on Cohere, OpenAI, Anthropic; Cohere open-circuit falls back to RRF order
- **Redis-backed rate limiting**: Bucket4j on Redis, ready for multi-instance
- **Global daily budget cap**: token / cost ceiling across all IPs (per-IP limits don't bound total spend); over budget → friendly refusal
- **Prompt structure**: split system / user messages; delimit context and question
- **Injection defense**: rule-based detection + adversarial cases added to the eval set
- Verify: answers still served with Cohere down; all adversarial cases pass; requests refused once the daily cap is hit

---

## 🔜 Phase 18 — Answer Quality & Routing

- **Failure triage**: label bad answers as routing / retrieval / generation using Phase 15 data before tuning
- **Conceptual questions**: new `CONCEPTUAL` type (`what is / explain / difference between`, no "you/your"); general knowledge allowed, facts about me must come from Context
- **Hybrid routing**: rules first; when no rule matches, classify via LLM structured output; default type is no longer BEHAVIORAL
- **Prompt slimming**: replace rule lists / word counts / blacklists with 2–3 few-shot examples per type; keep only changes that don't regress on eval
- **Model upgrade**: Haiku 4.5 → Haiku 5.5; compare on eval set before switching
- Verify: classifier eval and scenario eval ≥ baseline; conceptual cases pass

---

## 🔜 Phase 19 — Agentic RAG (minimal)

- **Routing**: simple questions keep the existing workflow; multi-hop / comparison questions go to a tool-calling agent; falls back to the workflow on error
- **Tools**: `search_experience(query, company?, intent?)` (hybrid + rerank), `get_company_summary(company)`, `list_companies_timeline()` (MySQL)
- **Agent loop**: LangChain4j `@Tool` + Claude Haiku; max 4 tool calls, token budget
- **Tracing**: reuse Phase 16 Langfuse; per-step tool args, latency, tokens
- **Evaluation**: add multi-hop cases; Workflow vs Agent table on quality / latency / cost
- **Optional**: expose the same tools as an MCP server
- Verify: comparison table in README, concluding which questions justify an agent

---

## Backlog

- [Admin Dashboard] Document list / detail / delete / retry; prompt template view + inline edit
- [Admin Dashboard] Baseline eval runner (persist runs in MySQL) and eval diff UI
- [Ingestion] Document versioning: re-upload replaces old chunks automatically
- [Ingestion] Duration queries: extract tenure dates from MySQL structured fields, append to context
- [Retrieval] Semantic answer caching (embedding similarity, not MD5)
- [Generation] Multi-company comparison: detect "compare X vs Y", run two scoped retrieval pipelines, merge contexts
- [Evaluation] Thumbs up/down feedback; stored in MySQL for retrieval quality analysis
- [Frontend] Source chunk expander: click source chip to view full chunk text

**Not planned:**

- [Retrieval] HyDE: LLM generates a hypothetical answer first, embed that for retrieval
- [Retrieval] Dual index / multi-path recall: Qdrant RRF already covers this
- [Retrieval] GraphRAG, multi-agent, fine-tuning, Kubernetes: no measurable benefit at this corpus size
- [Cost] Prompt caching: ~1.6K tokens per request is likely below the minimum cacheable length
