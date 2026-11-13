# Company Isolation

## Why This Exists

The knowledge base holds documents from multiple employers. Without isolation an OCBC question
can pull in unrelated context, or a Deloitte question can miss its sub-project content. Isolation
scopes each question to the chunks that belong to it.

The mechanism is **fully config-driven** — no company names or relationships are hardcoded in Java.
Swap the company list and subgroup config in `application.yml` and the same logic applies to any
knowledge base. "Company" is just a label; it can be any entity boundary (employer, client, product).

```yaml
rag:
  companies: [Alipay, Sinosig, NetEase, Deloitte, OCBC, Sanofi]
  company-subgroups:
    Deloitte: [OCBC, Sanofi]      # parent → sub-projects
```

Four layers enforce isolation: **tagging → focus detection → retrieval filtering → prompt-level guard.**

---

## Stage 1 · Tagging at Ingestion (`CompanyTagger`)

Each chunk gets a `companies[]` list before it is stored in Qdrant. Signals are merged by priority:

```
1. markdown heading   (most reliable per-chunk signal)
2. filename           (only if the heading yielded nothing)
3. chunk text         (always scanned, merged in)
4. parent derivation  (reverse-lookup company-subgroups)
```

All scans are case-insensitive substring matches against `rag.companies`. **Parent derivation** means a
chunk tagged `[OCBC]` automatically also gets `[Deloitte]`, so parent-company searches find sub-project
chunks without query expansion.

| File / signal | Stored `companies[]` |
|---------------|----------------------|
| `Deloitte-OCBC-project.pdf`, text "OCBC Banking Portal" | `[OCBC, Deloitte]` |
| text mentions "OCBC and Sanofi" | `[OCBC, Sanofi, Deloitte]` |
| `Alipay-experience.pdf`, no other company keyword | `[Alipay]` |

Chunks with empty `companies[]` are shared/general content.

---

## Stage 2 · Focus Company Detection (`CompanyScopeResolver.extractFocusCompany`)

Identifies which company the question is about (stops at first match):

```
1. subsidiary named in question   (OCBC/Sanofi beat their parent Deloitte)
2. any company named in question
3. history — Q lines, newest first
4. history — A lines, newest first
```

No match ⇒ `focusCompany = null`, no company filter applied. Scanning history handles follow-ups that
drop the name ("What was the tech stack?" after discussing Alipay stays scoped to Alipay). Questions are
authoritative; answers are only a fallback.

---

## Stage 3 · Retrieval Filtering (two layers)

### Pre-filter — inside Qdrant, before RRF fusion

The company clause shape depends on whether query intents are present:

- **No intents** (factual / dense-only): company is a soft `should` that also admits empty-company chunks.
  ```json
  { "should": [ {"key":"companies","match":{"any":["OCBC"]}}, {"is_empty":{"key":"companies"}} ] }
  ```
- **Intents present** (behavioral path): company is promoted to a hard `must`, and `intent_tags` become a
  `should` scoring boost.
  ```json
  { "must": [ {"key":"companies","match":{"any":["OCBC"]}} ],
    "should": [ {"key":"intent_tags","match":{"value":"leadership"}} ] }
  ```

### Post-filter — Java allowlist, after Cohere rerank

Applied by the **BEHAVIORAL** and **FACTUAL_EXPERIENCE** strategies. A chunk is kept if it has no company,
or it contains the focus company **and every one of its labels** is within:

```
allowedCompanies = {focusCompany} ∪ siblings ∪ parent
```

**Why a second filter:** the pre-filter keeps any chunk that merely *mentions* the focus company —
including a cross-company chunk tagged `[OCBC, Alipay]`. The post-filter drops it because `Alipay` is
outside the allowed set, while a legitimate `[OCBC, Sanofi, Deloitte]` overview chunk survives (all labels
allowed). Chunks that don't contain the focus company at all are also dropped.

> MULTI_COMPANY enforces per-company diversity instead of this filter; FACTUAL_NO_RERANK relies on the
> Qdrant pre-filter only.

---

## Stage 4 · Parent-Child Hierarchy

`company-subgroups` drives `allowedCompanies` — **siblings and parent are allowed, unrelated firms are not:**

| Question scope | `allowedCompanies` | Effect |
|----------------|--------------------|--------|
| OCBC | `{OCBC, Sanofi, Deloitte}` | sibling Sanofi + parent Deloitte allowed; Alipay/etc. dropped |
| Sanofi | `{Sanofi, OCBC, Deloitte}` | sibling OCBC + parent Deloitte allowed |
| Deloitte | `{Deloitte, OCBC, Sanofi}` | all sub-projects included |
| Alipay | `{Alipay}` | no sibling/parent — strict single-company scope |

Adding a relationship is config-only:

```yaml
rag.company-subgroups:
  NewParent: [SubA, SubB]
```

---

## Stage 5 · Prompt-Level Guard

Final defense for mixed-label chunks that slip through: when a focus company is set, `PromptBuilder`
injects a `companyContextHint` that names the topic company and **explicitly lists every other known
company to ignore** — so even if unrelated content reaches the context, the LLM is told not to use it
(details in `prompt-design.md`).

---

## Multi-Tenant Intent

Another user runs the system on their own data by changing config only: upload documents, set
`rag.companies`, declare `rag.company-subgroups`. Tagging, Qdrant filters, the allowlist, and the prompt
guard all read config at runtime.
