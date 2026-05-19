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
            ├─ semanticSplit(): paragraph-aware chunking (chunkSize=1000, overlap=150)
            │    ├─ Split on \n\n (paragraph boundaries from normalizeText)
            │    └─ Merge short paragraphs until near target size
            │
            ├─ Metadata attachment per chunk:
            │    docId, fileName, category, chunkIndex, chunkTotal
            │
            ├─ [RAPTOR] generateDocumentSummary() → Claude (1 call)
            │    └─ 4-6 sentence summary: company, role, achievements, tech stack
            │         prepended as chunkIndex="summary", chunkType="document_summary"
            │
            ├─ [Contextual Retrieval] Claude context prefix per chunk (if enabled)
            │    ├─ Parallel: CompletableFuture.supplyAsync + Semaphore(concurrency=3)
            │    └─ 1-2 sentence prefix: company, project, time period
            │
            ├─ Dense embedding: OpenAI text-embedding-3-small (batch=100)
            │
            ├─ Sparse vectorization: BM25-IDF in-process (SparseVectorizer)
            │
            ├─ Company tagging:
            │    fileCompanies = extractCompaniesFromText(fileName)
            │    chunkCompanies = extractCompaniesFromText(chunkText)
            │    companies = union(fileCompanies, chunkCompanies)
            │
            ├─ Qdrant upsert: PUT /collections/{name}/points
            │    { id: UUID, vectors: {dense, sparse}, payload: {..., companies, chunkType} }
            │
            ├─ MySQL UPDATE: status=COMPLETED, chunk_count=N
            │
            ├─ SparseVectorizer.addChunksToCorpus() → update + persist idf_corpus.json
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
    dir: /tmp/rag-uploads  # idf_corpus.json also persisted here
  contextual-retrieval:
    enabled: true
    max-doc-chars: 12000   # chars of full doc sent to Claude for context
    concurrency: 3         # max parallel Claude calls (Semaphore)
  raptor:
    enabled: true
    max-doc-chars: 12000
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

| Phase | chunkSize | overlap | Splitter |
|-------|-----------|---------|---------|
| Phase 2 | ~512 | ~50 | LangChain4j default (character) |
| Phase 3 | ~512 | ~50 | Character; added sentence window expansion (+1 neighbor) |
| Phase 5+ | 1000 | 150 | Character; removed neighbor expansion |
| Phase 10 | 1000 | 150 | Semantic (paragraph → sentence fallback) |

**Why 1000/150:**
Larger chunks carry more context per retrieval hit. 150-char overlap prevents information loss at boundaries.

**Why semantic chunking (Phase 10):**
Fixed character splitting cuts mid-sentence, producing incomplete facts in chunks.

## Contextual Retrieval

Problem: chunks often lack context — a chunk saying "reduced API latency by 40%" doesn't specify which company or project.

Fix: at index time, Claude generates a 1-2 sentence prefix per chunk:
> "This chunk is from the OCBC API gateway redesign project at Deloitte (2022–2024). It describes..."

The prefix is prepended to the **embedding text** so the dense vector carries company/project/time context. 

## RAPTOR Document Summary

Problem: broad questions ("tell me about your overall experience at Alipay") require piecing together many small chunks, which is unreliable.

Fix: at ingest time, Claude generates one 4-6 sentence summary per document covering role, responsibilities, key achievements, and tech stack. This summary is stored as a regular Qdrant point tagged `chunkType=document_summary` and retrieved like any other chunk.

Broad questions naturally score high against the summary chunk. Specific questions score higher against the fine-grained chunks. Both are in the same collection with no routing logic needed.

Prompt: stored in `prompt_template` as `raptor_document_summary`.

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

Each chunk gets a `companies` list by scanning both the filename and the chunk text for configured company names (case-insensitive). 

Example:

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
