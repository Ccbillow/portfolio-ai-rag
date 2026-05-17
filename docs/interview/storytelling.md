# Project Storytelling

Prepared answers for common interview questions about this project.

---

## 1. Why did you build this?

I wanted a way for interviewers to explore my experience interactively instead of reading a static resume. The real goal was to build something production-deployable that forced me to solve actual RAG problems — retrieval quality, hallucination control, cost management — not just run a LangChain tutorial.

The project went through 9 major phases and is deployed on a public server. That forced every decision to have real consequences: bad retrieval means wrong answers about my own career, which is immediately obvious.

---

## 2. What was the hardest part?

Company isolation. I worked at Deloitte on two client projects — OCBC and Sanofi — and that created a three-way problem.

1. Asking about OCBC would pull in Sanofi context, because some chunks are tagged with both (e.g. a Deloitte overview that mentions both clients).
2. Asking about Deloitte directly would sometimes miss OCBC or Sanofi content entirely. 
3. Avoid hardcode — adding a new subsidiary shouldn't touch Java code.

The fix was three layers working together: 

* a Qdrant tag filter narrows candidates by company list; 
* a post-rerank allowlist in Java drops chunks whose tags don't match the query scope; 
* a `company-subgroups` config declares the parent-child relationships, so the allowlist knows OCBC and Sanofi are siblings under Deloitte. A Deloitte query includes both; an OCBC query excludes Sanofi. New relationships are just config changes.

It took 4 commits because each fix exposed a new edge case.

---

## 3. How did you debug retrieval quality?

I started with Postman — send questions, read answers, tweak the prompt or chunk config, repeat. That broke down quickly.

Four problems hit me: reading 20+ answers by hand was slow; the question set I tuned against would pass, but rephrasing anything broke it; every change forced a full re-run even if most questions couldn't be affected; and fixing one thing silently broke something that was working before.

 Then I built a **structured test**:

- **Smoke tests (8 questions)** — run after every change, catches obvious regressions in 2 minutes
- **Baseline tests (40 questions)** — run only after significant changes (chunk size, prompt overhaul, retrieval config); results saved as timestamped files and diffed against the previous run

Scoring is automated with LLM-as-Judge: Claude evaluates each answer against the retrieved context across a few dimensions (accuracy, groundedness, completeness) and flags contradictions. That replaced most of the manual reading.

The diff report is what made it useful — I could see exactly which questions improved and which regressed, not just "it feels better."

---

## 4. How did you reduce hallucination?

Three mechanisms:

**1. Hard refusal rule in the prompt:**
> "If the evidence is not found in the Context, you MUST say you don't have that information."

Without this, Claude would extrapolate from its training data about common backend systems and make up plausible-sounding facts.

**2. Company scope filtering:**
Even if Claude gets the right kind of answer, company-scoped retrieval ensures the context only contains facts from the relevant company. This prevents "at Alipay we used Redis clusters" appearing in an answer about Sinosig.

**3. Type-aware prompting:**

* FACTUAL questions get a stricter instruction ("cite only what the Context explicitly states"). 
* TECHNICAL questions get more latitude ("explain the approach, but stay grounded in Context"). 
* BEHAVIORAL questions get a template structure to guide the narrative.

---

## 5. Why not use LangChain's default RAG pipeline?

LangChain4j's default `EmbeddingStore.addAll()` stores only a single dense vector per chunk. I needed to store both a dense vector (OpenAI) and a sparse BM25 vector in the same Qdrant point to enable hybrid search.

LangChain4j's high-level APIs would have abstracted away the Qdrant point structure, making it impossible to add the sparse vector or the custom payload fields (docId, companies, chunkIndex) I needed for filtering.

So I used LangChain4j only for what it does well (embedding model client, document splitter) and called Qdrant's REST API directly for upsert and search.

More broadly: the default RAG pipeline (embed → store → retrieve → prompt) doesn't include reranking, company filtering, question classification, or ambiguity resolution. Every production-quality RAG system needs at least a few of these, and building them yourself means you understand each component's failure modes.

---

## 6. Why hybrid search (dense + BM25)?

Dense-only search encodes semantic meaning but struggles with rare technical terms. BM25 is exact-match keyword search — it finds documents containing precisely the query terms.

For a resume knowledge base, both failure modes matter:
- Semantic failure: "Did you handle high concurrency?" → low cosine similarity with "Used Redis distributed locks to prevent overselling under 10K QPS" because the vocabulary is different
- Keyword success: "SofaBoot" or "MAT heap dump" → pure keyword match, no semantic reasoning needed

Hybrid search with RRF fusion covers both. The Qdrant Query API handles the fusion in-database, so there's no additional round trip. The BM25 vectorizer is implemented in-process (no Elasticsearch), so there's no extra infra.

---

## 7. How would you scale this if you had 1000 users?

**Current bottlenecks at scale:**

1. **Rate limiting in-process** — move to Redis-backed rate limiting (Bucket4j supports Redis)
2. **Conversation history in Redis per-session** — already scales horizontally; no change needed
3. **Cohere API** — already external, but would need circuit breaker + fallback (dense-order) under sustained load
4. **Qdrant single-node** — Qdrant supports distributed mode; for this data size, a replica is sufficient; sharding only needed at millions of points
5. **Spring Boot single instance** — add load balancer + 2+ instances; stateless design (JWT + Redis) already supports this

**What I'd add for production at scale:**
- OpenTelemetry tracing (trace per request through classifier → retrieval → rerank → LLM)
- Prometheus metrics: retrieval hit rate, reranker score distribution, LLM latency p50/p95
- A/B testing framework for prompt changes (currently tested manually with baseline scripts)
- Async embedding queue: batch multiple ingestion requests, reduce OpenAI calls

The core architecture is horizontally scalable; it just needs observability and a load balancer in front.
