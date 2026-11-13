# System Architecture

## Overview

Portfolio AI RAG is a production-deployed interview assistant that answers questions about my
engineering background using Retrieval-Augmented Generation. Interviewers chat via a public UI; the
system retrieves relevant resume/project chunks from a vector store and generates grounded answers via
Claude Haiku. The backend is organized **package-by-feature** with a **Strategy pattern** at the
retrieval core and a shared **LLM gateway** in front of Anthropic.

## Component Diagram

```
Interviewer Browser
      │ HTTPS
      ▼
  Nginx (443)  — SSL termination, SSE-friendly proxy timeout
      │ HTTP :8080
      ▼
┌───────────────────────────── Spring Boot API ─────────────────────────────┐
│ comm         RateLimitInterceptor (Bucket4j per-IP) · JWT filter · gate     │
│ chat         ChatServiceImpl (SSE) · PromptBuilder + AnswerFormatResolver    │
│ retrieval    RetrievalService                                                │
│                ├─ QuestionClassifier (rule gate) · QueryIntentClassifier     │
│                ├─ CompanyScopeResolver · QuerySignalClassifier               │
│                └─ RetrievalStrategySelector → { MULTI_COMPANY · BEHAVIORAL ·  │
│                       FACTUAL_NO_RERANK · FACTUAL_EXPERIENCE }               │
│              CandidateRetriever · HybridSearcher · CohereRerankService        │
│ vectorstore  QdrantSearchService · SparseVectorizer (BM25-IDF) · Embedding   │
│ ingestion    IngestionRunner → TextExtractor · ChunkSplitter ·               │
│                ChunkMetadataEnricher · DocumentSummarizer · PointAssembler   │
│ llm          LlmClient + LlmThrottle  (one shared Claude rate timeline)       │
└──┬─────────────────┬─────────────────┬─────────────────────────────────────┘
   ▼                 ▼                 ▼
 Qdrant :6333     MySQL :3306       Redis :6379
 (dense+sparse)   (docs, chat,      (session history,
                   prompt_template)  ingest status, rate limit)

 external APIs:  OpenAI text-embedding-3-small (1536d)  ·  Cohere rerank-v3.5
                 Anthropic claude-haiku-4-5-20251001 (chat, enrichment, document-level summary)
```

## Tech Stack

| Layer | Choice | Why |
|-------|--------|-----|
| Backend | Spring Boot 3.2 · Java 17 | Async support, familiar ecosystem |
| AI integration | LangChain4j | Native Java types; lighter than LangChain Python |
| Vector store | Qdrant | Named multi-vector (dense+sparse in one point); RRF fusion; REST |
| Embedding | OpenAI text-embedding-3-small | Best cost/quality; 1536-dim |
| Sparse | Custom BM25-IDF (`SparseVectorizer`) | Corpus IDF persisted to disk; no ES; tunable stop words |
| Reranker | Cohere rerank-v3.5 | Cross-encoder quality without a local model |
| LLM | Claude Haiku | Low latency; strong grounding/refusal; cheap for short answers |
| Doc parsing | Apache Tika | PDF/DOCX/TXT uniformly |
| Metadata | MySQL | Documents, chat history, prompt templates |
| Session | Redis | Conversation history, ingest status, rate-limit counters |
| Rate limit | Bucket4j (in-process) | Per-IP token bucket, zero infra |
| Auth | Spring Security + JWT | Stateless; RBAC (ADMIN / INTERVIEWER / public) |
| Deploy | Docker Compose | Single-host prod |

## Data Flow

**Ingestion (admin)** — `IngestionRunner`, `@Async`:
```
Upload → dedup check → disk save → MySQL PENDING
  → Tika parse → ChunkSplitter (markdown-heading → semantic fallback, 1000/150)
  → base metadata → ChunkMetadataEnricher (Claude: intent/behavioral/type/project/skills)
  → document-level summary chunk (Claude) → [contextual prefix — disabled]
  → OpenAI embed (dense) + BM25-IDF (sparse) + company tags
  → Qdrant upsert → IDF corpus update → MySQL COMPLETED
```

**Chat (public)** — `RetrievalService` + `ChatServiceImpl`, SSE:
```
Question → rule gate (QuestionClassifier) → focus company + pronoun rewrite + sub-project expansion
  → strategy select → { intent-aware hybrid + rerank + company allowlist  (behavioral)
                      | per-company search + diversity                    (multi-company)
                      | hybrid, no rerank                                  (factual temporal/profile)
                      | hybrid + rerank, no cutoff                         (factual experience) }
  → PromptBuilder (mode = f(type, strategy)) → Claude Haiku (streaming) → Redis history
```

## Qdrant Collection Schema

Each point:
- `vectors.dense` — float[1536] (OpenAI) · `vectors.sparse` — BM25-IDF (indices + values)
- `payload.text_segment` — raw chunk text
- `payload.docId / fileName / category / chunkIndex / chunkTotal`
- `payload.companies` — company labels for scoped filtering
- `payload.chunk_type` — `document_summary` for document-level summary chunks
- `payload.intent_tags / behavioral_tags / project / skills` — enrichment tags (drive intent routing)

## Database (MySQL)

- `sys_user` — accounts with `ROLE_ADMIN` / `ROLE_INTERVIEWER`
- `rag_document` — upload metadata, ingestion status, chunk count
- `rag_chat_history` — one row per answered question: Q&A, model used, latency, source doc IDs, rerank scores (written asynchronously; gate replies are stored as `rule-based`)
- `prompt_template` — hot-reloadable prompt templates
- Schema and prompt templates are versioned with Flyway (`src/main/resources/db/migration`)

## Security

- Chat API public (`permitAll`) — interviewers need no account
- Document upload/management: `ROLE_ADMIN` only
- JWT stateless (24h expiry); BCrypt hashing
- Rate limit: 5 req/min + 20 req/day per IP (Bucket4j, scheduled eviction)
- Session limit: 15 turns per session (Redis counter)
