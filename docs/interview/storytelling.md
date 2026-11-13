# Project Storytelling

Prepared answers for common interview questions about this project.

---

## 1. Why did you build this?

I wanted a way for interviewers to explore my experience interactively instead of reading a static resume. The real goal was to build something production-deployable that forced me to solve actual RAG problems — retrieval quality, hallucination control, cost management — not just run a LangChain tutorial.

The project went through a dozen iterative phases and is deployed on a public server. That forced every decision to have real consequences: bad retrieval means wrong answers about my own career, which is immediately obvious.

---

## 2. What was the hardest part?

Company isolation. I worked at Deloitte on two client projects — OCBC and Sanofi — and that created a three-way problem.

1. Asking about OCBC would pull in Sanofi context, because some chunks are tagged with both (e.g. a Deloitte overview that mentions both clients).
2. Asking about Deloitte directly would sometimes miss OCBC or Sanofi content entirely. 
3. Avoid hardcode — adding a new subsidiary shouldn't touch Java code.

The fix was four layers working together:

* **Tagging at ingest** — each chunk is tagged with the companies it mentions, and a `company-subgroups` config reverse-derives the parent (a chunk tagged `OCBC` also gets `Deloitte`).
* **Qdrant filter** — narrows candidates to chunks that carry the focus company (or no company at all).
* **Post-rerank allowlist in Java** — keeps a chunk only if *all* its labels fall within `{focus} ∪ siblings ∪ parent`. So a Deloitte overview tagged `[OCBC, Sanofi, Deloitte]` survives an OCBC query (siblings are in-scope, they share the same engagement), but a chunk that also mentions an unrelated employer like Alipay is dropped.
* **Prompt-level guard** — even for a surviving mixed chunk, the prompt tells Claude to use *only* the focus company's facts and ignore every other named company. That's what ultimately keeps Sanofi facts out of an OCBC answer.

The whole thing is config-driven — declaring a new parent/child relationship is a YAML change, no Java. It took several commits because each fix exposed a new edge case.

---

## 3. How did you debug retrieval quality?

I started with Postman — send questions, read answers, tweak the prompt or chunk config, repeat. That broke down quickly.

Four problems hit me: reading 20+ answers by hand was slow; the question set I tuned against would pass, but rephrasing anything broke it; every change forced a full re-run even if most questions couldn't be affected; and fixing one thing silently broke something that was working before.

 Then I built a **structured eval harness**:

- **Scenario eval set (~50 cases)** — covering factual, behavioral, technical, company-isolation, and multi-turn follow-ups. Each run writes a timestamped report and diffs against a promoted `baseline.json`, so I see exactly which cases improved or regressed — not just "it feels better."
- **Classifier eval set (~37 cases)** — a separate set that checks the rule-based question classifier routes each question to the right type/gate, since a misroute silently breaks retrieval downstream.

Scoring is automated with **LLM-as-Judge**: Claude scores each answer against the retrieved context on accuracy, groundedness, and completeness, and flags contradictions. That replaced most of the manual reading, and the baseline diff is what caught silent regressions where fixing one case broke another.

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

## 7. What retrieval improvements did you make after the initial build?

Three problems I found through testing:

**1. BM25 without IDF(Inverted Document Frequency).** My sparse vectorizer was using TF only — "SofaBoot" and "project" scored equally when they appeared with similar frequency. Added corpus IDF (`log(1 + (N+1)/(df+1))`), persisted to disk and updated on each ingestion. Now rare technical terms dominate BM25 scores as intended.

**2. Mid-sentence chunk cuts.** Fixed-character splitting would break a sentence in half. Switched to a structure-aware splitter: prefer markdown headings (each `##` section becomes a chunk), fall back to paragraph/sentence boundaries for oversized sections. Resume docs are naturally section- and paragraph-structured, so this matched the document instead of fighting it.

**3. Broad questions missed context.** "Tell me about your overall Alipay experience" needed to piece together 5-6 chunks, which was unreliable. Fixed with **RAPTOR**: one Claude-generated 4-6 sentence document summary per file, stored as a regular Qdrant chunk, so broad questions hit that summary directly while specific questions still hit the fine-grained chunks — same collection, no routing. I also implemented **Contextual Retrieval** (Claude prepends a 1-2 sentence company/project/time prefix to each chunk's embedding text) but left it disabled — at this corpus size the extra per-chunk Claude call wasn't worth it. Both are one-time ingest costs, never per-query.

**4. Intent-aware retrieval + per-question strategy.** The biggest structural change. At ingest, Claude tags each chunk with the interview intents it answers (architecture, leadership, production_issue…). At query time a classifier maps the question to the same intents, which boost matching chunks in Qdrant. On top of that, a strategy selector routes each question differently — factual questions lean on BM25 keyword match with no rerank cutoff, behavioral questions use intent-boosted hybrid + strict rerank, and career-spanning questions do a per-company search so every employer is represented (a global rerank would just favor the company with the richest vocabulary).

---

## 8. How would you scale this if you had 1000 users?

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
