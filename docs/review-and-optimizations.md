# Project Review & Optimization Backlog

## Phase 1 & 2 Summary

### What's Working

| Area | Status |
|------|--------|
| Document upload (multipart → Tika → OpenAI embed → Qdrant) | ✅ Working |
| Async ingestion with `@Async` via separate `IngestionRunner` bean | ✅ Working |
| Task status tracking (Redis + MySQL) | ✅ Working |
| Deduplication by filename (prevents repeat embedding cost) | ✅ Working |
| Disk-first file persistence (decouples from HTTP, enables restart recovery) | ✅ Working |
| Qdrant vector search via WebClient (clean, no SDK dependency issues) | ✅ Working |
| SSE streaming chat (embed → search → Claude stream → frontend) | ✅ Working |
| Frontend chatbot widget (floating button, SSE parsing, stream rendering) | ✅ Working |
| CORS config for local dev + github.io prod | ✅ Working |
| Global exception handler with consistent `Result<T>` responses | ✅ Working |
| Spring profiles: dev (Ollama free) / local (real APIs) / prod | ✅ Working |
| Soft delete on Document entity via `@TableLogic` | ✅ Working |

### Architecture Overview

```
Frontend (Parcel SPA)
    └── POST /api/chat/stream  ──→  ChatController
                                        └── ChatServiceImpl
                                                ├── OpenAI (embed question)
                                                ├── QdrantSearchService (WebClient REST)
                                                └── AnthropicStreamingChatModel (SSE tokens)

    └── POST /api/documents/upload ──→  DocumentController
                                            └── DocumentServiceImpl (sync: save + return)
                                                └── IngestionRunner @Async (background)
                                                        ├── Tika (parse)
                                                        ├── OpenAI (embed chunks)
                                                        └── QdrantEmbeddingStore (gRPC write)
```

---

## Optimization Backlog

Items are grouped by type. **None of these are changed yet** — pick and prioritize.

---

### 🔴 Bugs / Correctness Issues

**1. `documentId` is always `null` in chat sources**
- Location: `QdrantSearchService.toSearchHit()` + `Vos.SourceChunk`
- Problem: Qdrant payload stores `docId` as a string key, but `SearchHit` record doesn't map it. `SourceChunk.documentId` is always `null`.
- Fix: Add `String docId` to `SearchHit`, parse it in `toSearchHit()`, populate `SourceChunk.documentId`.

**2. `getTaskStatus()` has no MySQL fallback**
- Location: `DocumentServiceImpl.getTaskStatus()`
- Problem: Status is read only from Redis. If Redis restarts (TTL 24h), status returns `"NOT_FOUND"` even though MySQL has the real status.
- Fix: If Redis returns null, fall back to `documentMapper.selectById()`.

**3. Inconsistent `minScore` default**
- Location: `ChatServiceImpl` `@Value("${rag.embedding.min-score:0.5}")` vs `application.yml` `min-score: 0.3`
- Problem: The hardcoded default in `@Value` (0.5) is used if the yml key is missing, but the yml says 0.3. Confusing and easy to misread.
- Fix: Remove the default from `@Value` (use `${rag.embedding.min-score}`) and ensure yml always has the value.

---

### 🟡 Code Quality / Refactoring

**4. Magic status strings should be an enum**
- Location: `DocumentServiceImpl`, `IngestionRunner`, `Vos.IngestTaskResponse`, `Document.status`
- Problem: `"PENDING"`, `"PROCESSING"`, `"COMPLETED"`, `"FAILED"` appear as raw strings in 4+ files. A typo would cause a silent bug.
- Fix: Create `enum IngestionStatus { PENDING, PROCESSING, COMPLETED, FAILED }` and use it everywhere.

**5. `new Tika()` instantiated per ingestion**
- Location: `IngestionRunner.ingest()` line 71
- Problem: `Tika` is a heavyweight object. Creating a new instance for every upload wastes initialization time.
- Fix: Declare `private final Tika tika = new Tika()` as a field in `IngestionRunner`.

**6. `DocumentByCharacterSplitter` instantiated per ingestion**
- Location: `IngestionRunner.ingest()` line 75–77
- Problem: Same issue — instantiated fresh per call, but `chunkSize` and `chunkOverlap` are fixed config values.
- Fix: Initialize once as a field (inject `@Value` in constructor/`@PostConstruct`).

**7. Hardcoded model name in `ChatServiceImpl` response**
- Location: `ChatServiceImpl.ask()` line 59, and askStream fallback
- Problem: `modelUsed: "claude-haiku-4-5-20251001"` is hardcoded as a string literal, not read from config.
- Fix: Inject `@Value("${langchain4j.anthropic.chat-model-name}")` and use it in the response.

**8. `@Value("${rag.upload.dir:...}")` duplicated**
- Location: Both `DocumentServiceImpl` and `IngestionRunner` declare the same `@Value` independently.
- Fix: Extract to a `@ConfigurationProperties` class (e.g., `RagProperties`) and inject once.

**9. `ChatServiceImpl` mixes three concerns**
- Location: `ChatServiceImpl` — embedding, search, prompt building, and streaming all in one class
- Problem: Hard to test individual steps, hard to swap search strategy.
- Fix (optional, medium effort): Extract `RagPipeline` or `ContextAssembler` component. Not urgent for a portfolio project.

---

### 🟡 Configuration

**10. CORS allowed origins hardcoded in Java**
- Location: `SecurityConfig.corsConfigurationSource()`
- Problem: To add a new allowed origin (e.g., a custom domain), you must recompile.
- Fix: `config.setAllowedOriginPatterns(List.of(corsOrigins))` where `corsOrigins` comes from `@Value("${cors.allowed-origins}")` in yml.

**11. `maxTokens: 1024` hardcoded in `ChatModelConfig`**
- Location: `ChatModelConfig.claudeChatModel()` and `claudeStreamingChatModel()`
- Problem: Not tunable from yml. For short answers (current prompt asks for 2-4 sentences), 1024 is wasteful.
- Fix: Add `langchain4j.anthropic.max-tokens: 512` to yml and wire it in.

**12. No timeout on OpenAI embedding calls**
- Location: `EmbeddingConfig.openAiEmbeddingModel()` — `timeout(Duration.ofSeconds(30))` is set, but no retry.
- Problem: A transient OpenAI failure will fail the entire ingestion with no retry.
- Fix: Add `.maxRetries(2)` to the OpenAI builder (supported by langchain4j).

---

### 🟢 Missing Features (future phases)

**13. `delete()` doesn't clean up Qdrant vectors**
- Location: `DocumentServiceImpl.delete()`
- Problem: Deleting a document from MySQL leaves its vectors in Qdrant. Over time this causes stale search results.
- Fix: Use Qdrant's filter-delete API: `DELETE /collections/{name}/points` with `{ "filter": { "must": [{ "key": "docId", "match": { "value": "123" } }] } }`.

**14. No restart-recovery job for PENDING documents**
- Location: Mentioned in `IngestionRunner` Javadoc but not implemented.
- Problem: If the server restarts mid-ingestion, PENDING records remain stuck.
- Fix: Add a `@Scheduled` job on startup that scans MySQL for `status = PENDING OR PROCESSING` and re-queues them via `IngestionRunner.ingest()`.

**15. Frontend `API_BASE` hardcoded**
- Location: `simplefolio/src/scripts/chatbot.js` line 2
- Problem: `http://localhost:8080` won't work when the portfolio is deployed to github.io.
- Fix: Replace with a relative path (`/api/...`) once backend is deployed, or use an environment variable via Parcel's `process.env.API_BASE`.

**16. `listAll()` has no pagination**
- Location: `DocumentServiceImpl.listAll()`
- Problem: `selectList(null)` returns all records — fine now (small dataset) but will degrade at scale.
- Fix: Add `Page<Document>` support via MyBatis-Plus `IPage`.

---

## Suggested Priority Order

| Priority | Item | Effort |
|----------|------|--------|
| Fix now | #1 (documentId null), #2 (Redis fallback), #3 (minScore default) | Small |
| Next sprint | #4 (enum), #5 (Tika singleton), #6 (Splitter singleton), #7 (model name) | Small |
| Nice to have | #10 (CORS yml), #11 (maxTokens yml), #13 (Qdrant delete) | Small–Medium |
| Future | #8 (ConfigProperties), #9 (RagPipeline), #14 (restart recovery), #15 (API_BASE), #16 (pagination) | Medium |
