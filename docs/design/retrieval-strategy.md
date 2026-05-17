# Retrieval Strategy

## Full Pipeline

```
Question (max 300 chars)
  │
  ├─ QuestionClassifier (rule-based, zero LLM cost)
  │    ├─ INVALID → short-circuit message
  │    ├─ OUT_OF_SCOPE → short-circuit message
  │    ├─ STRATEGIC → short-circuit message
  │    ├─ CONTACT_REDIRECT → redirect to resume
  │    ├─ FACTUAL
  │    ├─ TECHNICAL
  │    └─ BEHAVIORAL
  │
  ├─ Session limit check (Redis counter, 30 turns/session)
  │
  ├─ extractFocusCompany(question, history)
  │    └─ Scan question → history Q lines → history A lines (in order)
  │
  ├─ resolveRetrievalQuery(question, history, focusCompany)
  │    └─ If ambiguous pronouns (it/that/this/there/they/those):
  │         prefix with last answer snippet (up to 120 chars)
  │         + focusCompany if not in snippet
  │
  ├─ MultiQueryExpander.expand() [disabled in prod]
  │    └─ withSubProjectQueries(): for Deloitte → add OCBC + Sanofi variants
  │
  ├─ Hybrid Search (per query):
  │    ├─ Dense: OpenAI embed(query) → Qdrant dense vector search
  │    ├─ Sparse: BM25 vectorize(query) → Qdrant sparse vector search
  │    └─ Fuse: Qdrant Query API with RRF (Reciprocal Rank Fusion)
  │         fetchLimit = candidateK=10
  │
  ├─ Deduplicate by (docId:chunkIndex) across all queries
  │
  ├─ Cohere Rerank (rerank-v3.5)
  │    ├─ Input: up to 10 candidate chunks
  │    ├─ Output: top-N=5 by relevance score
  │    └─ minRerankScore=0.18 (always keep at least 1)
  │
  ├─ Company scope filter (post-rerank)
  │    ├─ If focusCompany set: keep chunks where companies[] contains focusCompany
  │    │   AND all labels are within allowedCompanies
  │    └─ allowedCompanies = {focusCompany} ∪ siblings ∪ parent
  │
  └─ Prompt assembly → Claude Haiku (SSE streaming)
```

## Configuration

```yaml
rag:
  embedding:
    top-k: 4           # dense-only fallback fetch limit
    min-score: 0.25    # used only in dense-only fallback path
  hybrid-search:
    enabled: true
  multi-query:
    enabled: false     # disabled: 1 Claude call per question instead of 2
  reranker:
    enabled: true
    candidate-k: 10    # chunks fetched from Qdrant before reranking
    top-n: 5           # final chunks fed to LLM
    model: rerank-v3.5
    min-rerank-score: 0.18
```

## Hybrid Search Detail

Qdrant Query API (`/points/query`) is used for hybrid fusion:
```json
{
  "prefetch": [
    { "query": <dense_vector>, "using": "dense", "limit": 20 },
    { "query": {"indices": [...], "values": [...]}, "using": "sparse", "limit": 20 }
  ],
  "query": { "fusion": "rrf" },
  "limit": 10,
  "filter": <company_filter>
}
```

RRF score = Σ 1/(rank_i + 60). This is computed by Qdrant — no post-processing needed.

The company filter uses Qdrant's `should` condition:
```json
{ "should": [
    { "key": "companies", "match": { "any": ["Alipay"] } },
    { "is_empty": { "key": "companies" } }
]}
```

Empty-company chunks (shared background info) are always included.

## BM25 Sparse Vectorizer

Custom implementation (no IDF — single-document scoring):
- Vocab size: 65,536 (hash(term) & MAX_VALUE % 65536)
- k1=1.2, b=0.75
- avgLen = chunkSize / 5 (≈200 tokens for 1000-char chunks)
- Stop words: 50+ common English words filtered
- CJK: each character is a token; Latin: split on non-alphanumeric

Why custom instead of BM25s/Elasticsearch:
- No external Elasticsearch dependency
- Tunable stop words for the resume domain
- Integrates directly with Qdrant sparse vector format

## Question Classifier (Rule-Based)

Priority order: INVALID > OUT_OF_SCOPE > STRATEGIC > CONTACT_REDIRECT > FACTUAL > TECHNICAL > BEHAVIORAL

Short-circuits before any vector search:
- INVALID: too short or bare interrogative
- OUT_OF_SCOPE: weather, sports, recipes, news, etc. + "give me questions" prompts
- STRATEGIC: salary, weaknesses, conflicts with manager
- CONTACT_REDIRECT: phone/email requests → "download my resume"

Types that reach retrieval: FACTUAL, TECHNICAL, BEHAVIORAL
Each type gets a different type_hint injected into the prompt template.

## Conversation History

Redis key: `conversation:{sessionId}` (list of "Q: ...\nA: ..." lines, last 2 turns).

Used for:
1. Pronoun resolution in retrieval query
2. Company focus tracking across turns
3. Injected into prompt as "Recent conversation" section

History is scanned in reverse (most recent first) to find the current company focus.

## Multi-Query (Disabled in Prod)

Phase 3 added multi-query expansion (Claude generates 2 query variants). Phase 9.2 disabled it:
- Each question was costing 2 Claude API calls instead of 1
- ~50% cost reduction with no measurable recall drop
- The `withSubProjectQueries()` method provides targeted expansion for the Deloitte→OCBC/Sanofi case without LLM overhead

## Streaming

Chat responses use Spring's `SseEmitter` (timeout: 90s).

Token normalization before sending:
- `\n\n` → `\\n\\n` (frontend renders as paragraph break)
- `\n` → ` ` (prevents word concatenation in frontend HTML)

Sources are sent as a separate SSE event (`event:sources`) before the first token, so the UI can render source chips immediately.
