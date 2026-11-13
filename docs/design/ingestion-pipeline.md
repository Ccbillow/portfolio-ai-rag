# Ingestion Pipeline

Async document ingestion, orchestrated by `IngestionRunner`. Each stage is a dedicated
SRP component under `service.ingestion`; the runner only wires the pipeline and manages
status/persistence.

## Sequence

```
POST /api/documents/upload (ROLE_ADMIN)
  │  DocumentServiceImpl.upload — synchronous, returns immediately
  ├─ Dedup check (MySQL: same fileName, status != FAILED)  → skip if exists (no re-embed cost)
  ├─ UploadStorage.save → uploadDir/{taskId}                ← decouple file from HTTP lifetime
  ├─ MySQL INSERT (status=PENDING, taskId=UUID)
  ├─ Redis SET ingest:task:{taskId} = PENDING (TTL 24h)
  └─ Return 200 { taskId }  (poll via GET /api/documents/task/{taskId})
       │
       └─ @Async("taskExecutor")  IngestionRunner.ingest
            ├─ Redis/MySQL: PROCESSING
            ├─ TextExtractor        — Apache Tika → plain text (streamed)
            ├─ ChunkSplitterSelector
            │     ├─ MarkdownHeadingChunkSplitter (## / ### headings; stamps `heading` meta)  ← preferred
            │     └─ SemanticChunkSplitter (paragraph-aware)                                    ← fallback
            │     chunkSize≈1000, overlap 150; oversized chunks split at sentence boundaries
            ├─ Stamp base metadata: docId, fileName, category, chunkIndex, chunkTotal
            ├─ ChunkMetadataEnricher — Claude tags each chunk (batches of 5, parallel)          [enabled]
            │     → intent_tags, behavioral_tags, chunk_type, project, skills, confidence
            ├─ DocumentSummarizer (document-level summary) — Claude, 1 call → summary chunk at index 0          [enabled]
            ├─ ContextualRetrievalEnricher — per-chunk context prefix before embedding          [disabled]
            ├─ EmbeddingService — OpenAI text-embedding-3-small, batches of 100 → float[1536]
            ├─ QdrantPointAssembler
            │     ├─ SparseVectorizer — BM25-IDF sparse vector per chunk (in-process)
            │     ├─ CompanyTagger — company labels (heading → filename → text) + parent derivation
            │     └─ payload: text, docId/fileName/category/chunkIndex, companies, enrichment tags
            ├─ Qdrant upsert points { dense + sparse vectors, payload }
            ├─ Redis/MySQL: COMPLETED, chunk_count = N
            ├─ SparseVectorizer.addChunksToCorpus → persist idf_corpus.json
            └─ finally: delete temp file from disk
```

## Feature Toggles (deployed)

| Stage | Enabled | Notes |
|-------|---------|-------|
| Metadata enrichment | ✅ | `concurrency=2`, `batch-size=5`, `rate-limit-ms=1500` |
| Document-level summary (`rag.raptor.enabled`) | ✅ | 1 Claude call/doc, `max-doc-chars=12000` |
| Contextual Retrieval | ❌ | off by default — cost/latency not justified at current corpus size |

```yaml
rag:
  embedding: { chunk-size: 1000, chunk-overlap: 150 }
  metadata-enrichment: { enabled: true, concurrency: 2, batch-size: 5, rate-limit-ms: 1500 }
  raptor:               { enabled: true, max-doc-chars: 12000 }
  contextual-retrieval: { enabled: false, concurrency: 3, rate-limit-ms: 6000 }
  companies: [Alipay, Sinosig, NetEase, Deloitte, OCBC, Sanofi]
  company-subgroups: { Deloitte: [OCBC, Sanofi] }
langchain4j.open-ai.embedding-model-name: text-embedding-3-small
```

## Key Designs

### Metadata Enrichment → intent routing
At ingest, Claude classifies each chunk into the same taxonomy the query side uses:
`intent_tags` (architecture, production_issue, leadership, delivery, technical_depth,
ambiguity, conflict, growth), `behavioral_tags`, `chunk_type`, `project`, `skills`.
These tags are stored in the Qdrant payload and drive **intent-aware retrieval**
(query intents boost matching chunks — see `retrieval-strategy.md`).

Chunks are enriched in **batches of 5 per Claude call** (each object echoes its `index`
so a dropped item can't shift the batch). A global `Semaphore(2)` caps concurrent calls
across all uploads; rate limiting is delegated to the shared Claude timeline (`LlmThrottle`).

### Document-Level Summary
Broad questions ("tell me about your Alipay experience") need many small chunks pieced
together, which is unreliable. So one 4–6 sentence document summary is generated per doc,
stored as a normal point tagged `chunk_type=document_summary` at `chunkIndex="summary"`.
Broad questions score high against it; specific questions score higher against fine-grained
chunks — same collection, no routing logic. Prompt: `raptor_document_summary`.

### Contextual Retrieval (disabled)
Optionally prepends a Claude-generated 1–2 sentence context prefix (company/project/time)
to a chunk's **embedding text**, so vectors carry context that raw chunks lack. Bounded by
`Semaphore(3)` and a 6s gap to stay under the token/min limit. Off by default.

### Company Tagging
`CompanyTagger.tag` merges signals with priority: markdown **heading** (most reliable for the
chunk) → **filename** (only if heading empty) → always scan **chunk text**. It then
**reverse-derives parent companies** from `company-subgroups` (a chunk tagged `[OCBC]` also gets
`[Deloitte]`), so parent-company searches find sub-project chunks without query expansion.

Example: `Deloitte-OCBC-project.pdf`, chunk mentions "OCBC Banking Portal"
→ `companies: [OCBC, Deloitte]`.

### BM25-IDF Sparse Vectors
Custom in-process vectorizer (no Elasticsearch). The IDF corpus (`idf_corpus.json`) is updated
after each ingestion so rare technical terms (SofaBoot, MAT, SPEL) dominate keyword scores.
Full formula in `retrieval-strategy.md`.

## Why Apache Tika
One dependency handles PDF/DOCX/TXT/future formats. Tika can concatenate words across stripped
newlines, so text is normalized (single `\n` → space, `\n\n\n+` → paragraph break) before chunking.

## Deduplication
Before insert, checks MySQL for a same-filename record with status != FAILED. If found, returns
it immediately — no re-embedding, no API cost, no duplicate vectors. FAILED records are skipped,
so re-uploading a previously failed file works.

## Async Thread Pool
```yaml
spring.task.execution.pool: { core-size: 4, max-size: 8, queue-capacity: 100 }
```
Upload returns immediately; ingestion runs on `taskExecutor`. Status polled via Redis (fast) →
MySQL fallback (covers Redis restart / TTL expiry).

## Error Handling
On any failure: MySQL + Redis status = FAILED (error_message truncated to 500 chars). The temp
file is always removed in the `finally` block. Dedup skips FAILED records so re-upload succeeds.
