# Performance Notes

## End-to-End Latency (Observed)

| Component | Typical | Notes |
|-----------|---------|-------|
| QuestionClassifier | < 1ms | Pure in-memory regex |
| OpenAI embedding (query) | 200–400ms | Single short text, text-embedding-3-small |
| Qdrant hybrid search | 10–30ms | RRF over dense+sparse, ~6333 REST |
| Cohere rerank | 150–300ms | 10 candidates, rerank-v3.5 API |
| Company scope filter | < 1ms | In-memory list scan |
| Claude Haiku TTFT | 600–1200ms | First token via streaming |
| Claude Haiku total stream | 3–8s | Depends on answer length |
| Redis history read/write | < 5ms | Local Docker Redis |

**Typical p50 latency to first token:** ~1.5–2.5s
**Typical p50 full answer:** ~5–10s

## Retrieval Quality Observations

### Hybrid vs Dense-Only

From phase8 test results, hybrid search improved recall on exact keyword queries:
- "SofaBoot" — dense alone: 1 hit at 0.28 score; hybrid: 3 hits (BM25 exact match)
- "reverse-match" — dense alone: missed; hybrid: retrieved via sparse
- General question types ("tell me about your experience at Alipay") — no difference

### Reranker Impact

candidateK=10 → topN=5 selection:
- Typical score range: 0.15–0.75
- Bimodal distribution: relevant chunks cluster 0.4+, noise chunks cluster < 0.15
- minRerankScore=0.18 sits in the gap between clusters

Reranker occasionally re-orders results significantly:
- Qdrant RRF rank 1 ≠ Cohere rank 1 in ~30% of queries
- RRF favors keyword overlap; Cohere favors semantic relevance

### Bad Retrieval Cases Identified

1. **Duration questions without explicit dates in chunks:** "How long at Sinosig?" — chunks often say "1.5 years" without anchoring to start/end dates, so answer is accurate but incomplete.

2. **Multi-entity questions:** "Compare your work at Alipay vs Deloitte" — company filter can only apply one focus company, so the query defaults to no filter and may pull in lower-quality chunks from both.

3. **Very specific implementation questions:** "What was the exact queue depth threshold you used?" — if the resume doesn't state the exact number, context is absent and the system correctly refuses. Can feel frustrating to the interviewer.

## LLM Token Usage

| Config | Input tokens (est.) | Output tokens (est.) |
|--------|---------------------|----------------------|
| System prompt + type hint | ~400 | — |
| Context (5 chunks × ~200 tokens avg) | ~1000 | — |
| History (2 turns) | ~200 | — |
| Question | ~30 | — |
| **Total input** | **~1630** | — |
| **Answer** | — | **~200–500** |

`maxTokens=800` (set in production config). Haiku pricing: $0.25/1M input, $1.25/1M output.

Estimated cost per question: ~$0.001 (< 0.1 cent).

## Ingestion Performance

| Step | Estimate |
|------|---------|
| Tika parse (DOCX ~50KB) | < 1s |
| Character split (1000/150) | < 10ms |
| OpenAI embedding (batch=100, ~20 chunks) | 500ms–1s |
| Qdrant upsert (20 points) | 50–100ms |
| **Total per file** | **~2–3s** |

Dense embedding dominates ingestion time. Batch size of 100 keeps API calls to 1 per file in most cases.

## Rate Limiting Effectiveness

Limits observed in prod logs:
- Per-minute: 5 req/min per IP catches rapid-fire F5 refreshes
- Per-day: 20 req/day per IP sufficient for a genuine interviewer session
- No legitimate users have hit daily limit based on session turn logs

## Memory Usage (Docker)

| Service | Heap/RSS |
|---------|----------|
| Spring Boot | ~400MB heap |
| Qdrant | ~150MB (small collection) |
| MySQL | ~200MB |
| Redis | < 50MB |

Total: ~1GB RAM for full stack, fits comfortably on a 2GB VPS.

## Known Performance Bottlenecks

1. **Cohere rerank latency (150–300ms):** Biggest non-LLM latency. Could be reduced by cutting candidateK from 10 to 6, accepting slightly lower recall.

2. **OpenAI embedding per query:** ~300ms synchronous. Can't be cached without query normalization (near-duplicate queries vary slightly). Future: batch embedding with a short queue window.

3. **Redis conversation history:** Stored as a formatted string, not JSON. Parsing is fast but adds a string processing step; not a current bottleneck.

4. **SseEmitter timeout (90s):** Conservative for Claude Haiku (typical stream < 10s). Could reduce to 30s to free server resources faster on stuck connections.
