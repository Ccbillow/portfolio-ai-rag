# Company Isolation

## Why This Exists

The knowledge base contains documents from multiple employers. Without isolation, an OCBC question can pull in Sanofi context (same Deloitte engagement), or a Deloitte question can miss sub-project content entirely. Company isolation ensures each question retrieves only the chunks that belong to its scope.

The mechanism is fully config-driven — no company names or relationships are hardcoded in Java. A new user can replace the company list and subgroup config in `application.yml` and the same logic applies to their knowledge base.

---

## Stage 1: Tagging at Ingestion

When a document is uploaded, each chunk is tagged with a `companies[]` list before being stored in Qdrant.

**How tags are derived:**

```
companies = union(
  extractCompaniesFromText(fileName),   // scan the filename
  extractCompaniesFromText(chunkText)   // scan the chunk body
)
```

Both scans check against the configured company list (case-insensitive substring match). A chunk gets tagged with every company name that appears in either the filename or its text content.

**Example:**

| File | Chunk text mentions | Stored `companies[]` |
|------|---------------------|----------------------|
| `Deloitte-OCBC-project.pdf` | "OCBC Banking Portal" | `["Deloitte", "OCBC"]` |
| `Deloitte-overview.pdf` | "OCBC and Sanofi projects" | `["Deloitte", "OCBC", "Sanofi"]` |
| `Alipay-experience.pdf` | "distributed cache" (no company keyword) | `["Alipay"]` |

Chunks with an empty `companies[]` are treated as shared/general content and are always included in retrieval.

**Configuration:**

```yaml
rag:
  companies:
    - Alipay
    - Sinosig
    - NetEase
    - Deloitte
    - OCBC
    - Sanofi
```

Adding a new company requires only adding it here — no code change.

---

## Stage 2: Focus Company Extraction at Query Time

Before retrieval, the system identifies which company the question is about.

**Scan order (stops at first match):**

1. Question text itself
2. Recent conversation history — Q lines (most recent first)
3. Recent conversation history — A lines (most recent first)

The scan checks the same company list from config. If no match is found, `focusCompany` is null and no company filter is applied.

**Why scan history:** Follow-up questions often drop the company name. "What was the tech stack?" after discussing Alipay should still scope to Alipay.

---

## Stage 3: Retrieval Filtering

Company filtering happens in two places.

### Pre-filter (Qdrant)

Qdrant applies a `should` filter before vector search:

```json
{ "should": [
    { "key": "companies", "match": { "any": ["OCBC"] } },
    { "is_empty": { "key": "companies" } }
]}
```

This narrows the candidate pool to chunks that mention the focus company OR have no company tag (shared content). This runs inside Qdrant before RRF fusion — it's fast and reduces noise before reranking.

### Post-filter (Java, after rerank)

After Cohere reranking, a stricter allowlist filter runs in Java. A chunk is kept only if **all** of its company labels fall within `allowedCompanies`.

```
allowedCompanies = {focusCompany} ∪ siblings ∪ parent
```

**Why this second filter is needed:** The Qdrant pre-filter keeps any chunk that contains the focus company. But a Deloitte overview chunk tagged `["Deloitte", "OCBC", "Sanofi"]` passes the OCBC pre-filter (it contains "OCBC"). The post-filter checks all labels — Sanofi is not in `allowedCompanies` for an OCBC query, so the chunk is dropped.

---

## Stage 4: Parent-Child Company Hierarchy

Some companies are sub-projects of a parent. The hierarchy is declared in config:

```yaml
rag:
  company-subgroups:
    Deloitte:
      - OCBC
      - Sanofi
```

This drives how `allowedCompanies` is computed for each query:

| Question scope | `allowedCompanies` | Effect |
|---------------|-------------------|--------|
| OCBC | `{OCBC, Deloitte}` | Includes Deloitte-tagged overview chunks; excludes Sanofi |
| Sanofi | `{Sanofi, Deloitte}` | Includes Deloitte-tagged overview chunks; excludes OCBC |
| Deloitte | `{Deloitte, OCBC, Sanofi}` | Includes all sub-project chunks |
| Alipay | `{Alipay}` | No parent/sibling; strict single-company scope |

**Adding a new parent-child relationship:**

```yaml
rag:
  company-subgroups:
    NewParent:
      - SubA
      - SubB
```

No Java code changes required.

---

## Multi-Tenant Design Intent

The system is designed so that another user can run it against their own knowledge base by changing only the config:

1. Upload their own documents
2. Set their company list in `rag.companies`
3. Declare any parent-child relationships in `rag.company-subgroups`

The tagging logic, Qdrant filters, and post-rerank allowlist all read from config at runtime. The word "company" in the config is just a label — it can represent any entity boundary (employer, client, product line, department).
