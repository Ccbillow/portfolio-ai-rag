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

---

## Ingestion Performance

| Step | Estimate |
|------|---------|
| Tika parse (DOCX ~50KB) | < 1s |
| Character split (1000/150) | < 10ms |
| OpenAI embedding (batch=100, ~20 chunks) | 500ms–1s |
| Qdrant upsert (20 points) | 50–100ms |
| **Total per file** | **~2–3s** |

Dense embedding dominates ingestion time. Batch size of 100 keeps API calls to 1 per file in most cases.

---

## Memory Usage (Docker)

| Service | Heap/RSS |
|---------|----------|
| Spring Boot | ~400MB heap |
| Qdrant | ~150MB (small collection) |
| MySQL | ~200MB |
| Redis | < 50MB |

Total: ~1GB RAM for full stack, fits comfortably on a 2GB VPS.

---

## LLM Token Usage

### Config comparison

**Old config** (chunk 512/100, neighbor expansion ±1, topN=3, multi-query enabled):

| Component | Tokens |
|-----------|--------|
| System prompt + type hint | ~400 |
| History (2 turns) | ~200 |
| Question | ~30 |
| Context: topN=3 × neighbor expand → ~7 chunks × ~102 tokens | ~715 |
| **Main call input** | **~1,345** |
| Multi-query extra Claude call (input + output) | ~+300 |
| **Total LLM tokens per question** | **~1,645** |

**New config** (chunk 1000/150, no expansion, topN=5, multi-query disabled):

| Component | Tokens |
|-----------|--------|
| System prompt + type hint | ~400 |
| History (2 turns) | ~200 |
| Question | ~30 |
| Context: topN=5 × ~200 tokens avg | ~1,000 |
| **Total input** | **~1,630** |
| **Answer output** | **~200–500** |

Token consumption nearly identical, but new config eliminates the extra Claude call entirely.

---


## Known Performance Bottlenecks

1. **Cohere rerank latency (150–300ms):** Biggest non-LLM latency. Could be reduced by cutting candidateK from 10 to 6, accepting slightly lower recall.

2. **OpenAI embedding per query:** ~300ms synchronous. Can't be cached without query normalization (near-duplicate queries vary slightly). Future: batch embedding with a short queue window.

3. **Redis conversation history:** Stored as a formatted string, not JSON. Parsing is fast but adds a string processing step; not a current bottleneck.

4. **SseEmitter timeout (90s):** Conservative for Claude Haiku (typical stream < 10s). Could reduce to 30s to free server resources faster on stuck connections.
