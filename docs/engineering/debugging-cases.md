# Debugging Cases

---

## Integration

### LangChain4j + Qdrant dimension mismatch

**Problem:** `QdrantEmbeddingStore.findRelevant()` threw a dimension mismatch error on first query after switching embedding models.

**Cause:** LangChain4j's default Qdrant store was initialized with 384 dimensions (a previous local model). Switching to OpenAI `text-embedding-3-small` produces 1536-dim vectors, but the collection schema wasn't recreated.

**Fix:** Dropped and recreated the Qdrant collection with the correct `"size": 1536` config. Also stopped using LangChain4j's high-level store — moved to direct Qdrant REST calls to keep full control over the point schema.

---

## Ingestion

### Chunk too small → partial answers

**Problem:** Questions about a specific project only got half the answer. E.g. "What did you do at NetEase?" returned the role but missed the tech stack and outcome.

**Cause:** Default chunk size was ~512 chars. A single work experience block split across 3 chunks. Retrieval only surfaced one.

**Fix:** Increased chunk size to 1000 chars / 150 overlap. Each chunk now covers a complete project description without relying on neighbor expansion.

### Sentence window expansion vs chunk size trade-off

**Problem:** Added ±1 neighbor chunk expansion to compensate for small chunks. After company isolation was added, OCBC questions started returning Sanofi content — the neighbor chunk happened to be from the same Deloitte overview document but tagged to Sanofi.

**Cause:** Expansion fetched by chunkIndex adjacency, ignoring company tags.

**Fix:** Removed expansion entirely. Larger chunks (1000 chars) carry enough context on their own. Expansion added noise and made company isolation harder.

---

## Retrieval

### Agent layer: all questions went to the LLM

**Problem:** Questions like "What's the weather?", "Can you tell me your salary expectation?", or "Give me some interview questions" all reached retrieval and Claude — burning API cost and returning garbage responses.

**Cause:** No pre-retrieval gate. Every input was treated as a valid RAG question.

**Fix:** Added `QuestionClassifier` — a rule-based classifier with zero LLM cost. It runs before any vector search and short-circuits immediately for INVALID, OUT_OF_SCOPE, STRATEGIC, and CONTACT_REDIRECT types. Only FACTUAL, TECHNICAL, and BEHAVIORAL questions proceed to retrieval. This also drove the type-hint system: classifier output determines which prompt hint is injected.

### min-score too high → empty results

**Problem:** "How long did you work at Alipay?" returned "I don't have that information" despite the answer being in the knowledge base.

**Cause:** `min_score=0.5` was too strict. Resume text expresses the same fact in varied ways ("18 months", "joined in early 2022", "worked on...") — cosine similarity between a natural-language question and those phrasings was consistently below 0.5.

**Fix:** Dropped min_score gradually: 0.5 → 0.3 → 0.25. Added Cohere reranker as a second quality gate so loosening the threshold doesn't let noise reach the LLM.

### Reranking: top Qdrant results weren't actually relevant

**Problem:** Even with a reasonable min_score, Qdrant's top-ranked chunks were sometimes off-topic. The LLM received polluted context and produced confused or partially wrong answers.

**Cause:** Vector similarity (cosine distance) measures how close two embeddings are — not how useful a chunk is for answering the question. A chunk could rank high because it shares vocabulary with the question without containing the actual answer.

**Fix:** Added Cohere `rerank-v3.5` after Qdrant search. It fetches 10 candidates, then the cross-encoder scores each (question, chunk) pair jointly — capturing relevance that bi-encoder embeddings miss. Only the top 5 above `minRerankScore=0.18` proceed to the LLM. In testing, Cohere's rank 1 differed from Qdrant's rank 1 in ~30% of queries.

### Hybrid search: exact technical terms not found

**Problem:** Queries like "Did you use MAT?", "SofaBoot experience?", or "reverse-match refactor" returned zero or irrelevant results from dense-only search.

**Cause:** Dense embeddings encode semantic meaning — "MAT" (Memory Analyzer Tool) embeds near general Java/JVM/memory terms, not near the specific chunk that says "analyzed heap dumps with MAT". Exact keyword matching is a different problem.

**Fix:** Added custom BM25 `SparseVectorizer` (in-process, no Elasticsearch). Both dense and sparse vectors are stored in the same Qdrant point at ingestion. At query time, Qdrant's Query API fuses them with RRF (Reciprocal Rank Fusion) in a single request. Hybrid search improved recall on exact-term queries without any extra infrastructure.

### Company isolation: OCBC pulling in Sanofi content

**Problem:** OCBC questions returned Sanofi project details. Deloitte questions missed OCBC content entirely.

**Cause:** Two bugs combined. 

- the company filter checked `companies == ["OCBC"]` exactly — a Deloitte overview chunk tagged `["Deloitte","OCBC","Sanofi"]` was excluded even though it was relevant. 

- sibling companies (OCBC and Sanofi both under Deloitte) shared a parent label, so chunks from one leaked into the other.

**Fix:** 

- Qdrant `should` filter (match.any on companies[], OR is_empty)
- post-rerank Java allowlist that checks all chunk labels fall within `{focusCompany} ∪ siblings ∪ parent`
-  a `company-subgroups` config to declare the hierarchy. Details in `company-isolation.md`

### Multi-query expansion doubled API cost

**Problem:** API cost was 2× expected. Every question triggered two Claude calls instead of one.

**Cause:** Multi-query expansion (Phase 3) called Claude to generate 2–3 query variants before retrieval.

**Fix:** Disabled in prod (`rag.multi-query.enabled: false`). Replaced with `withSubProjectQueries()` — a zero-cost string replacement that handles the one case that mattered (Deloitte → OCBC/Sanofi variants). ~50% Claude API cost reduction.

### Classifier gaps: "suggest some questions" reached LLM

**Problem:** "Suggest some questions I can ask about your background" went to retrieval and Claude, instead of being rejected as out-of-scope.

**Cause:** Classifier patterns didn't cover prompt-injection style inputs that ask the system to generate content rather than answer about Simon.

**Fix:** Extended `OUT_OF_SCOPE` patterns to cover "give me questions", "list interview questions", "suggest questions" variants. Added `CONTACT_REDIRECT` type for phone/email requests.

---

## Prompt

### Claude invented project details

**Problem:** Claude described a project as using Elasticsearch when the actual system used MySQL. Nothing in the uploaded docs mentioned Elasticsearch.

**Cause:** Early system prompt said "answer based on the context" but had no hard fallback rule. When context was thin, Claude filled gaps from training data.

**Fix:** Added explicit refusal rule: "If the evidence is not found in the Context, output exactly: I do not have that detail in my notes. Do NOT infer or invent."


### Behavioral answers kept going after the result

**Problem:** Behavioral answers had a 4th or 5th sentence: "This taught me...", "Going forward I now...", "My manager noted..."

**Cause:** No explicit stop signal after the Result sentence.

**Fix:** Added `HARD STOP` rule to behavioral hint with an explicit blacklist of forbidden trailing phrases. Version evolved from v3 → v5 across multiple test rounds before the pattern was reliably suppressed.

### Factual scope too broad

**Problem:** "How long have you been in Australia?" returned duration + visa status + location details.

**Cause:** The length rule (≤12 words) was word-count-based, not topic-based. Claude included adjacent context it deemed relevant.

**Fix:** Added `ENTITY` scope rule with concrete examples: "How long in Australia? → duration only, not visa/family/location." Examples in the hint outperformed abstract scope descriptions.

### Answers sounded robotic

**Problem:** Early answers were dense, fragmented, and read like a formatted CV dump.

**Cause:** "Very concise. Prefer fragments over full sentences" was over-applied — the model stripped natural language structure entirely.

**Fix:** Loosened style rules: "Fragments allowed if clear" instead of "prefer fragments." Behavioral hint now allows full sentences. Compression rules kept but scoped to "drop background and transitions" rather than "compress everything."

---


