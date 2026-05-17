# System Architecture

## Overview

Portfolio AI RAG is a production-deployed interview assistant that answers questions about my engineering background using Retrieval-Augmented Generation. Interviewers interact via a chat UI; the system retrieves relevant resume/project chunks from a vector store and generates grounded answers via Claude Haiku.

## Component Diagram

```
┌──────────────────────────────────────────────────────┐
│                     Interviewer Browser               │
└───────────────────────┬──────────────────────────────┘
                        │ HTTPS
                        ▼
              ┌─────────────────┐
              │   Nginx (443)   │  Reverse proxy, SSL termination
              └────────┬────────┘
                       │ HTTP :8080
                       ▼
        ┌──────────────────────────────┐
        │     Spring Boot API          │
        │  ┌────────────────────────┐  │
        │  │  QuestionClassifier    │  │  Rule-based gate (0 LLM cost)
        │  │  ChatServiceImpl       │  │  Orchestration
        │  │  QdrantSearchService   │  │  Dense + hybrid search
        │  │  SparseVectorizer      │  │  BM25 in-process
        │  │  CohereRerankService   │  │  Cross-encoder reranking
        │  │  PromptBuilder         │  │  Template + context assembly
        │  │  RateLimitInterceptor  │  │  Bucket4j per-IP
        │  └────────────────────────┘  │
        └──┬───────────┬───────────────┘
           │           │           │
           ▼           ▼           ▼
      ┌─────────┐ ┌─────────┐ ┌─────────────┐
      │ Qdrant  │ │  MySQL  │ │    Redis     │
      │ :6333   │ │ :3306   │ │   :6379      │
      │ (vector)│ │(metadata│ │(session hist,│
      └─────────┘ │+ history│ │ ingest status│
                  └─────────┘ │ rate limit)  │
                              └─────────────┘
           │                       │
           ▼                       ▼
  ┌──────────────────┐   ┌──────────────────┐
  │ OpenAI Embedding │   │  Cohere Rerank   │
  │ text-embedding-  │   │  rerank-v3.5     │
  │ 3-small (1536d)  │   └──────────────────┘
  └──────────────────┘
           │
           ▼
  ┌──────────────────┐
  │  Anthropic API   │
  │  claude-haiku-   │
  │  4-5-20251001    │
  └──────────────────┘
```

## Tech Stack

| Layer | Choice | Why |
|-------|--------|-----|
| Backend framework | Spring Boot 4 + Java | Strong async support, familiar ecosystem |
| AI integration | LangChain4j | Lightweight vs. LangChain Python; native Java types |
| Vector store | Qdrant | Named multi-vector support (dense+sparse in one point); fast ANN; simple REST API |
| Embedding | OpenAI text-embedding-3-small | Best cost/quality ratio; 1536-dim, strong semantic coverage |
| Sparse vectorizer | Custom BM25 (SparseVectorizer.java) | No external dependency; tunable stop words for resume domain |
| Reranker | Cohere rerank-v3.5 | Cross-encoder quality; faster than running a local model |
| LLM | Claude Haiku | Low latency; cost-effective for short factual answers; good instruction following |
| Document parsing | Apache Tika | Handles PDF/DOCX/TXT uniformly; no format-specific logic |
| Metadata store | MySQL | Structured document + chat history; audit trail |
| Session store | Redis | Conversation history (per session); ingestion status polling |
| Rate limiting | Bucket4j (in-process) | Zero infra overhead; per-IP token bucket; resets handled by scheduled eviction |
| Auth | Spring Security + JWT | Stateless; RBAC (ADMIN / INTERVIEWER / public) |
| Reverse proxy | Nginx | SSL termination; 60s proxy timeout for SSE |
| Deployment | Docker Compose | Single-host prod; reproducible environment |

## Data Flow Summary

**Ingestion (admin only):**
```
Upload → Disk save → MySQL (PENDING) → Async: Tika parse
  → Character split (1000/150) → OpenAI embed (dense)
  → BM25 vectorize (sparse) → Qdrant upsert (both vectors)
  → MySQL (COMPLETED, chunk_count)
```

**Chat (public):**
```
Question → Rule-based classifier → Company focus extraction
  → Query rewrite (pronoun resolution) → Hybrid search (RRF)
  → Cohere rerank → Company scope filter → Prompt assembly
  → Claude Haiku (SSE streaming) → Session history (Redis)
```

## Database Schema (MySQL)

- `sys_user` — users with `ROLE_ADMIN` or `ROLE_INTERVIEWER`
- `rag_document` — upload metadata, ingestion status, chunk count
- `rag_chat_history` — every Q&A pair, model used, latency, source doc IDs
- `prompt_template` - build prompt

## Qdrant Collection Schema

Each point contains:

- `vectors.dense` — float[1536] from OpenAI text-embedding-3-small
- `vectors.sparse` — BM25 sparse vector (indices + values)
- `payload.text_segment` — raw chunk text
- `payload.docId`, `fileName`, `category`, `chunkIndex`, `chunkTotal`
- `payload.companies` — list of company labels for company-scoped filtering

## Security

- Chat API: fully public (no auth required) — designed for interviewers without accounts
- Document upload/management: `ROLE_ADMIN` only
- JWT stateless (24h expiry); BCrypt password hashing
- Rate limiting: 5 req/min + 20 req/day per IP (Bucket4j, evicted on schedule)
- Session limit: 30 turns per session (Redis counter)
