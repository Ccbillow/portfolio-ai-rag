# Engineering Decisions & Tradeoffs

> This is a personal portfolio project — no revenue, no SLA. Cost is a first-class constraint alongside quality.

---

## Vector Store: Qdrant

**Problem:** Need to store both a dense vector (semantic) and a sparse BM25 vector per chunk in the same record, and filter by company label at query time — all without running a separate Elasticsearch cluster.

| Option | Multi-vector | Self-hosted | Per-query cost | Verdict |
|--------|-------------|-------------|----------------|---------|
| Qdrant | ✅ named vectors | ✅ | $0 | **Chosen** |
| Elasticsearch | ❌ (separate index) | ✅ | $0 | Too heavy for solo deploy |
| Pinecone | ❌ (one namespace) | ❌ | Per query | Paid cloud, overkill |
| Weaviate | Partial | ✅ | $0 | More complex config |

Qdrant was the only option that stored dense + sparse vectors in one point natively — which meant hybrid search needed no extra infrastructure.

**Accepted:** Single-node only; no horizontal scale. Fine — the knowledge base is one person's resume, not a multi-tenant product.

---

## Embedding: OpenAI text-embedding-3-small

**Problem:** Need high-quality embeddings for technical English resume text. Self-hosting a model (SBERT, e5) would require a GPU instance that costs more per month than the OpenAI API calls for this project's entire lifetime.

**Cost-first:** This is a portfolio project. OpenAI `text-embedding-3-small` costs $0.02/1M tokens. Embedding a full resume takes ~10K tokens = $0.0002. The API is fast and needs zero infra to maintain.

| Option | Cost | Quality | Infra |
|--------|------|---------|-------|
| text-embedding-3-small | $0.02/1M | Good | None |
| text-embedding-3-large | $0.13/1M | Better | None |
| Self-hosted (e5-large) | GPU ~$50/mo | Similar | GPU server |

`text-embedding-3-small` covers technical vocabulary well enough. The reranker handles precision — embedding doesn't need to be perfect, just good enough to surface the right candidates.

**Accepted:** External API dependency — Qdrant ingestion fails if OpenAI is down. Mitigated by dedup check (no re-embedding on re-upload).

---

## LLM: Claude Haiku

**Problem:** Need an LLM that follows strict grounding instructions ("don't invent facts") reliably and streams fast. GPT-4o is better at reasoning but 10× more expensive per token.

**Cost-first:** Each chat turn sends ~1200 input tokens + ~300 output tokens. Claude Haiku costs $0.25/1M input + $1.25/1M output ≈ $0.0007 per question. At 20 questions/day limit per IP, the daily API cost is negligible.

| Option | Input cost/1M | Output cost/1M | Instruction following | Latency |
|--------|--------------|----------------|----------------------|---------|
| Claude Haiku | $0.25 | $1.25 | Strong | Fast |
| Claude Sonnet | $3 | $15 | Stronger | Medium |
| GPT-4o mini | $0.15 | $0.60 | Good | Fast |
| GPT-4o | $2.50 | $10 | Best | Slower |

Haiku was chosen over GPT-4o mini because Anthropic's models are more reliable at refusing to answer when evidence is absent — which is the core anti-hallucination requirement here.

**Accepted:** Slightly weaker at complex multi-hop reasoning than Sonnet. Acceptable for single-company factual Q&A.

---

## Reranker: Cohere rerank-v3.5

**Problem:** Qdrant hybrid search retrieves 10 candidates by vector similarity. But vector similarity ≠ answer quality — irrelevant chunks sneak in and pollute the LLM context, causing hallucinated or confused answers.

Fix: use a cross-encoder reranker that reads question + each chunk together and scores actual relevance. Then keep only the top 5.

**Configuration evolution:**

| Phase | minRerankScore | topN | Why changed |
|-------|---------------|------|-------------|
| Phase 5.1 | 0.1 | 3 | Initial setup |
| Tune b75d961 | 0.18 | 3 | Score 0.1 still let irrelevant chunks through |
| Phase 8.6 | 0.18 | 5 | topN=3 missed content when question spans multiple companies |

**Why minRerankScore=0.18:** Reranker scores cluster in two bands — relevant chunks score 0.4+, noise scores below 0.15. Setting 0.18 cuts the noise band cleanly. The code always keeps at least 1 chunk so valid questions never get an empty context.

**Cost-first note:** Cohere rerank API charges per 1K docs ranked. At ≤10 candidates per query, cost is tiny. The benefit (fewer hallucinations, tighter context) is worth the +150ms latency.

**Accepted:** External API dependency; graceful fallback to cosine-score ordering if Cohere is unavailable.

---

## Hybrid Search: Dense + BM25 Sparse

**Problem:** Pure dense (semantic) search missed exact technical keywords. A recruiter asking "did you use MAT?" got no results because "MAT" (Memory Analyzer Tool) embeds near general Java/memory terms, not near the exact chunk containing "MAT". BM25 keyword matching catches these cases directly.

**How it's implemented:**
- Both vector types are stored in the same Qdrant point at ingestion (no extra storage system)
- Qdrant's Query API fuses them with RRF (Reciprocal Rank Fusion) in one call
- The BM25 vectorizer runs in-process (no Elasticsearch)

| Option | Exact keyword | Semantic | Extra infra |
|--------|--------------|----------|-------------|
| Dense only | ❌ | ✅ | None |
| BM25 only | ✅ | ❌ | None |
| Hybrid (this) | ✅ | ✅ | None (Qdrant handles fusion) |
| Elasticsearch hybrid | ✅ | ✅ | Separate ES cluster |

**Accepted:** Sparse vectors make each Qdrant point larger. BM25 without IDF is a simplification (no corpus-level term weighting), but for a single-person knowledge base the relative term frequency is enough.

---

## Chunk Size: 1000 chars / 150 overlap

**Problem:** Small chunks (512 chars) fragmented project descriptions. A single work experience block turned into 3 disconnected chunks. Retrieval returned pieces instead of coherent paragraphs — the LLM got partial context and produced incomplete answers.

| Phase | chunkSize | overlap | Problem / change |
|-------|-----------|---------|-----------------|
| Phase 2 | ~512 | ~50 | LangChain4j default, too small |
| Phase 3 | ~512 | ~50 | Added neighbor expansion (+1 chunk) to compensate |
| b75d961 | 1000 | 150 | Larger chunks → removed neighbor expansion |

**Why remove neighbor expansion:** Fetching ±1 neighbor chunk pulled in adjacent content from different companies (context pollution). At 1000 chars the chunk already contains enough context — expansion added noise without improving answers.

---

## Embedding: min_score: 0.25

**Problem:** Initial min_score=0.5 caused empty retrieval on valid resume questions. Resume text paraphrases the same fact in many ways ("worked for 18 months", "joined in early 2022") — the cosine similarity between a question and its answer can be surprisingly low.

| Version | min_score | Result |
|---------|-----------|--------|
| Initial | 0.5 | Empty context on ~30% of factual questions |
| Mid-dev | 0.3 | Better recall, occasional irrelevant chunks |
| Current | 0.25 | Good recall; reranker handles noise filtering |

**Note:** min_score only applies to the dense-only fallback path (when BM25 sparse is empty). In the normal hybrid path, Qdrant returns RRF scores — those aren't comparable to cosine similarity, so no threshold is applied. The reranker handles quality filtering instead.

---

## Multi-Query Expansion: Disabled

**Problem it was solving:** Single query sometimes missed relevant chunks when phrasing didn't match the resume text well. Generating 2–3 query variants improved recall.

**Why disabled (Phase 9.2):** It required one extra Claude API call per question (query generation). That doubled the LLM cost — from ~$0.0007 to ~$0.0014 per question — with no measurable recall improvement in testing.

**Cost-first:** This is the clearest cost-first decision in the project. The `withSubProjectQueries()` method replaced it for the one case where it actually mattered (Deloitte → OCBC/Sanofi), using string replacement instead of an LLM call.

---

## Rate Limiting: Bucket4j In-Process

**Problem:** The chat endpoint calls OpenAI, Cohere, and Anthropic APIs. Without rate limiting, a single bad actor could run up significant API costs in minutes.

**Why in-process (Bucket4j) over Redis-backed:**
- Single-host deployment — no shared state needed across instances
- Zero-latency check (no network hop to Redis)
- Redis already handles session history; rate limit doesn't need to add another dependency

**Limits chosen:**
- 5 req/min per IP — stops rapid-fire spam; a real interviewer never types 5 questions per minute
- 20 req/day per IP — plenty for a full interview session; protects daily API budget

**Accepted:** Not distributed — if ever scaled to multiple app instances, switch to Redis-backed Bucket4j.

---

## AI Framework: LangChain4j over Spring AI

**Problem:** Needed fine-grained control over the Qdrant point structure (named dense + sparse vectors, custom payload fields). Spring AI's `VectorStore` abstraction hid that structure and had no stable Qdrant multi-vector support when this project started.

| Option | Java-native | Qdrant multi-vector | Control level |
|--------|-------------|---------------------|---------------|
| LangChain4j | ✅ | Partial (used REST directly) | High |
| Spring AI | ✅ | ❌ at project start | Low (abstracted) |
| LangChain Python | ❌ | ✅ | High |

LangChain4j was used for what it does well (embedding model client, document splitter). Qdrant upsert and search were implemented with direct REST calls via Spring WebClient.

**Accepted:** LangChain4j has a smaller community than LangChain Python. Some Qdrant features needed raw REST anyway, so this was never a blocker.

---

## Auth: JWT Stateless

**Problem:** The chat UI is public — interviewers shouldn't need to create accounts. But document upload must be admin-only to prevent anyone from poisoning the knowledge base.

**Solution:** Split into two paths:
- `/api/chat/**` → `permitAll()` — no token required
- `/api/documents/**` → `ROLE_ADMIN` only

JWT is stateless (no server-side session table). The 24h expiry is fine for an admin-only use case. Conversation state lives in Redis (keyed by sessionId), not in the Spring Security session.

---

## Trade-offs (decided not to implement)

### Ollama fallback for offline / cost control

Considered running a local Ollama model as a fallback when Anthropic API was unavailable.

**Why skipped:** Local models (Llama, Mistral) are noticeably weaker at following strict grounding rules — the hallucination rate went up in quick tests. The infra cost (GPU or CPU memory for a 7B model) exceeded the API cost it was meant to reduce. Interviewers don't perceive the model switch.

### Dual-index / multi-retrieval path

Considered running separate retrieval pipelines for dense and sparse, then re-fusing results in Java.

**Why skipped:** Qdrant's Query API already handles RRF fusion in-database with one request. Building a second retrieval path in Java would double Qdrant round-trips and add complexity for no recall improvement. The in-database approach is simpler and faster.