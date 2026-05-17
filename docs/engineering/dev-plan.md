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

- **Phase 5.1:** Cohere rerank-v3.5 — retrieve 10 candidates, keep top 5 by cross-encoder score
- **Phase 5.2:** Custom BM25 `SparseVectorizer` (in-process, no Elasticsearch); Qdrant Query API with RRF fusion; dense + sparse vectors in one point

---

## ✅ Phase 6 — Config & Deployment Hardening

- MySQL tuning (`my.cnf`), Docker Compose prod profile
- Nginx reverse proxy with SSL termination, 60s SSE timeout
- `application-prod.yml` with externalized secrets

---

## ✅ Phase 7 — Prompt Management

- `prompt_template` MySQL table; hot-reload via admin API (no redeploy needed)
- Rule-based `QuestionClassifier`: INVALID / OUT_OF_SCOPE / STRATEGIC / FACTUAL / TECHNICAL / BEHAVIORAL — zero LLM cost
- Type-specific prompt hints injected at runtime by classifier output

---

## ✅ Phase 8 — Company Isolation + Quality Hardening

- `companies[]` array payload on every Qdrant point; config-driven extraction from filename + chunk text
- Qdrant pre-filter (`should: match.any`) + post-rerank Java allowlist filter
- `company-subgroups` config for parent-child hierarchy (Deloitte → OCBC / Sanofi)
- Session turn limit (30/session), question length cap (300 chars)
- Frontend integration: SSE streaming, source chips, conversation history display
- Multiple prompt tuning rounds driven by manual baseline tests

---

## ✅ Phase 9 — Cost Optimization + Refinement

- Disabled multi-query expansion: 2 Claude calls → 1 per question (~50% cost reduction)
- Removed sentence window expansion; increased chunk size 512 → 1000 chars to compensate
- Reduced conversation history 3 turns → 2
- Tightened classifier patterns; added `CONTACT_REDIRECT` type
- Prompt artifact fixes: role accuracy rule, mandatory "At [CompanyName]" opening, output constraint banning word-count annotations

---

## 🔜 Phase 10 — Evaluation Framework

- Automated RAG evaluation (faithfulness, context recall, answer relevance)
- Golden test set: 40–50 Q&A pairs across all question types and companies
- Eval report generated on every config change; stored in `docs/eval/`

---

## 🔜 Phase 11 — Observability

- Structured JSON logging: `sessionId`, `questionType`, `focusCompany`, `numHits`, `rerankScores`, `latencyMs`
- Prometheus metrics: retrieval hits, rerank score distribution, LLM latency p50/p95
- OpenTelemetry traces: span per pipeline stage
- Grafana dashboard

---

## 🔜 Phase 12 — Answer Quality Improvements

- Duration queries: extract tenure dates from MySQL structured fields, append to context
- Multi-company comparison: detect "compare X vs Y", run two scoped retrieval pipelines, merge contexts
- Extend conversation history to 3–4 turns for single-company sessions

---

## 🔜 Phase 13 — Frontend Polish

- Loading skeleton during stream startup
- Source chunk expander (full chunk text on click)
- Question suggestions on first load
- Copy answer button, session export (PDF / markdown)

---

## 🔜 Phase 14 — Admin Dashboard

- Ingestion status table with chunk count and error messages
- Per-document chunk preview with company tags
- Manual reprocess (re-embed without re-upload)
- Rate limit usage monitoring per IP

---

## Backlog

- Redis-backed rate limiting (required for multi-instance scale-out)
- Semantic answer caching (embedding similarity, not MD5)
- Thumbs up/down feedback; stored in MySQL for retrieval quality analysis
- Chinese question support (classifier + prompts currently English-only)
- Document versioning: re-upload replaces old chunks automatically
