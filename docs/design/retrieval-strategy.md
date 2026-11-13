# Retrieval Strategy

Pre-LLM retrieval is orchestrated by `RetrievalService.retrieve()` and shared by the sync,
streaming, and eval paths. The core design is a **Strategy pattern**: the question is classified,
a `RetrievalStrategy` is selected, and only that strategy's retrieve logic runs.

## Pipeline

```
Question
  │
  ├─ 1 · Agent gate — QuestionClassifier (rule-based, zero LLM cost)
  │      earlyReturn types short-circuit before any vector search:
  │      INVALID · OUT_OF_SCOPE · STRATEGIC · CONTACT_REDIRECT · PERSONAL
  │
  ├─ 2 · Query prep
  │      ├─ extractFocusCompany(question, history)   — subsidiary → company → history fallback
  │      ├─ resolveRetrievalQuery()                  — pronoun resolution via last-answer snippet
  │      └─ withSubProjectQueries()                  — Deloitte → also query as OCBC / Sanofi
  │
  ├─ 3 · RetrievalStrategySelector.select(ctx)  → one of:
  │      ┌───────────────────────────────────────────────────────────────────────────┐
  │      │ MULTI_COMPANY        career-spanning, no focus company                       │
  │      │   per-company search (2 hits each) → enforce diversity (≥1 per company)      │
  │      │   NO global rerank (biases toward richest-vocabulary company)                │
  │      ├───────────────────────────────────────────────────────────────────────────┤
  │      │ BEHAVIORAL           BEHAVIORAL / TECHNICAL / STRATEGIC                       │
  │      │   QueryIntentClassifier → intent_tags → intent-aware hybrid search           │
  │      │   → Cohere rerank (minScore cutoff) → company allowlist filter               │
  │      ├───────────────────────────────────────────────────────────────────────────┤
  │      │ FACTUAL_NO_RERANK    FACTUAL + temporal/profile signals                       │
  │      │   hybrid search only — BM25 keyword match wins on dates/logistics; no rerank │
  │      ├───────────────────────────────────────────────────────────────────────────┤
  │      │ FACTUAL_EXPERIENCE   FACTUAL, everything else ("role at OCBC", "tech stack")  │
  │      │   hybrid → rerank (NO minScore, always top-N) → company allowlist filter     │
  │      └───────────────────────────────────────────────────────────────────────────┘
  │
  └─ 4 · Context assembly → RetrievalResult → prompt → Claude Haiku (SSE)
```

## Two-Phase Classification

**Phase 1 — `QuestionClassifier`** (rule-based regex, first match wins):

```
INVALID → OUT_OF_SCOPE → STRATEGIC → CONTACT_REDIRECT → PERSONAL
        → FACTUAL → BEHAVIORAL_PRE → TECHNICAL → BEHAVIORAL (default)
```

The first five are **gate** types — they return a canned message with no retrieval. The rest
reach retrieval and inject a type-specific hint into the prompt. `BEHAVIORAL_PRE`
("tell me about a time…") is matched before `TECHNICAL` so story questions aren't misrouted.

**Phase 2 — `QueryIntentClassifier`** (BEHAVIORAL / TECHNICAL / STRATEGIC only):
rule-based keyword match across 8 intent groups → **LLM fallback** (Claude) only when no rule
matches. Returns 1–3 tags; never throws (empty list ⇒ unfiltered retrieval).

```
architecture · production_issue · leadership · delivery
technical_depth · ambiguity · conflict · growth
```

These are the **same tags** stamped onto chunks at ingest, which is what makes intent routing work.

## Intent-Aware Hybrid Search

Qdrant Query API (`/points/query`) fuses dense + BM25 sparse with RRF. Intent tags are injected
as a **scoring boost, not a hard filter**:

```
must   → companies[] contains focusCompany     (hard scope)
should → intent_tags matches any query intent  (boost, not filter)
```

- `must` (company) uses `any` containment — chunks often carry multiple labels (`[OCBC, Deloitte]`).
- `should` (intents) boosts relevant chunks while broad questions still reach all chunks
  (important for RAPTOR summary hits — no `must_not` on `document_summary`).
- **Fallback**: if the intent-filtered query returns 0 hits (e.g. legacy chunks without tags),
  it retries plain hybrid search to guarantee results.
- prefetch limit = `candidateK × 2` per vector; fused output capped at `candidateK=10`.

Empty-company chunks (shared background) always pass the company filter.

## Company Scoping (`CompanyScopeResolver`)

- **Focus detection**: subsidiary named in question → any company in question → most recent
  company in history (questions authoritative, answers secondary).
- **Sub-project query expansion**: for a Deloitte question, add OCBC/Sanofi query variants so both
  client projects' chunks are retrievable despite different vocabulary.
- **Allowlist post-filter** (BEHAVIORAL & FACTUAL_EXPERIENCE): keep a chunk if it has no company,
  else it must contain focusCompany AND all its labels ∈ `{focus} ∪ siblings ∪ parent`.
  Mixed-label chunks (`[OCBC, Deloitte]`) survive; cross-company bleed is dropped.

## Configuration (prod)

```yaml
rag:
  hybrid-search: { enabled: true }
  reranker: { enabled: true, candidate-k: 10, top-n: 5, model: rerank-v3.5, min-rerank-score: 0.18 }
  multi-query: { enabled: false }   # sub-project expansion covers the Deloitte case w/o LLM cost
  embedding: { top-k: 4, min-score: 0.25 }   # dense-only fallback path
```

Session limit: **15 turns/session** (Redis counter). Streaming via `SseEmitter` (90s timeout);
`event:sources` is sent before the first token so the UI renders source chips immediately.

## BM25-IDF Sparse Vectorizer

Custom implementation with corpus IDF (no Elasticsearch dependency):

- `score(t,d) = IDF(t) · (k1+1)·TF / (k1·(1−b + b·|d|/avgLen) + TF)`, `k1=1.2`, `b=0.75`
- `IDF(t) = log(1 + (N+1)/(df(t)+1))`, N = chunks indexed, df = chunks containing the term
- Vocab 65,536 (`hash(term) & MAX_VALUE % 65536`); avgLen ≈ chunkSize/5 (~200 tokens)
- 50+ English stop words filtered; CJK tokenized per-character, Latin split on non-alphanumeric
- Corpus persisted to `{uploadDir}/idf_corpus.json`, updated after each ingest;
  falls back to IDF=1 (TF-only) when no corpus data exists

Why it matters: without IDF, "project" and "SofaBoot" score equally at similar TF; with IDF the
rare technical terms dominate, as intended for resume retrieval.

## Conversation History

Redis `conversation:{sessionId}` — last 2 turns as `"Q: …\nA: …"`, TTL 60 min. Used for pronoun
resolution, cross-turn company focus (scanned newest-first), and the prompt's recent-conversation
section.
