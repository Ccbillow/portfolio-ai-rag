# Ingestion Pipeline

## Sequence

```
POST /api/documents/upload
  │
  ├─ Dedup check (MySQL: fileName != FAILED already exists?) → skip if yes
  │
  ├─ Save file to disk: uploadDir/{taskId}  ← decouple from HTTP lifetime
  │
  ├─ MySQL INSERT (status=PENDING, taskId=UUID)
  │
  ├─ Redis SET ingest:task:{taskId} = PENDING  TTL 24h
  │
  └─ Return 200 immediately (taskId for polling)
       │
       └─ @Async thread (rag-async-*)
            │
            ├─ Redis: PROCESSING
            │
            ├─ Files.readAllBytes
            │
            ├─ Apache Tika: parseToString()  → plain text
            │    └─ normalizeText(): \r\n → \n; single \n → space; 3+ \n → \n\n
            │
            ├─ DocumentByCharacterSplitter: chunkSize=1000, overlap=150
            │
            ├─ Metadata attachment per chunk:
            │    docId, fileName, category, chunkIndex, chunkTotal
            │
            ├─ Dense embedding: OpenAI text-embedding-3-small (batch=100)
            │
            ├─ Sparse vectorization: BM25 in-process (SparseVectorizer)
            │
            ├─ Company tagging:
            │    fileCompanies = extractCompaniesFromText(fileName)
            │    chunkCompanies = extractCompaniesFromText(chunkText)
            │    companies = union(fileCompanies, chunkCompanies)
            │
            ├─ Qdrant upsert: PUT /collections/{name}/points
            │    { id: UUID, vectors: {dense, sparse}, payload: {..., companies} }
            │
            ├─ MySQL UPDATE: status=COMPLETED, chunk_count=N
            │
            └─ Files.deleteIfExists(diskPath)  ← temp file cleanup
```

## Key Configuration

```yaml
rag:
  embedding:
    chunk-size: 1000      # chars
    chunk-overlap: 150    # chars
  upload:
    dir: /tmp/rag-uploads
  companies:
    - Alipay
    - Sinosig
    - NetEase
    - Deloitte
    - OCBC
    - Sanofi
  company-subgroups:
    Deloitte:
      - OCBC
      - Sanofi

langchain4j:
  open-ai:
    embedding-model-name: text-embedding-3-small
```

## Chunk Size Decision

| Phase | chunkSize | overlap | Approach |
|-------|-----------|---------|---------|
| Phase 2 | ~512 | ~50 | LangChain4j default |
| Phase 3 | ~512 | ~50 | Added sentence window expansion (+1 neighbor) |
| Phase 5+ | 1000 | 150 | Larger chunks; removed neighbor expansion |

**Why 1000/150:**
Larger chunks carry more context per retrieval hit, avoiding the need for neighbor expansion (which added complexity and made company isolation harder). 150-char overlap ensures sentence-spanning context is captured at chunk boundaries.

## Why Apache Tika

Single dependency handles PDF, DOCX, TXT, and any future format without format-specific parsers. Tika's output sometimes concatenates words across paragraph boundaries when newlines are stripped, which is why `normalizeText()` converts single newlines to spaces.

## Deduplication

Before inserting, the pipeline checks MySQL for an existing record with the same filename and status != FAILED. If found, it returns the existing record immediately — no re-embedding, no API cost. This prevents accidental duplicate vectors in Qdrant.

## Async Thread Pool

```yaml
spring:
  task:
    execution:
      pool:
        core-size: 4
        max-size: 8
        queue-capacity: 100
      thread-name-prefix: rag-async-
```

Upload returns immediately; ingestion happens on the `rag-async-*` pool. Status is polled via `GET /api/documents/task/{taskId}` — Redis first, MySQL fallback.

## Company Tagging Logic

Each chunk gets a `companies` list by scanning both the filename and the chunk text for configured company names (case-insensitive). Example:

- File: `Deloitte-OCBC-project.pdf` → fileCompanies = `[Deloitte]`
- Chunk text mentions "OCBC Banking Portal" → chunkCompanies = `[OCBC]`
- Stored payload: `companies: ["Deloitte", "OCBC"]`

This tag is used at retrieval time for company-scoped filtering.

## Error Handling

If any step fails, the pipeline:
1. Sets MySQL status = FAILED with error_message
2. Sets Redis status = FAILED
3. Does NOT delete the temp file on disk (preserves it for manual investigation)

The dedup check skips FAILED records, so re-upload of a previously failed file works correctly.
