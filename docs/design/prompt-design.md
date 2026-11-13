# Prompt Design

## Prompts as Data, Not Code

Prompt quality is the most direct lever on answer quality, and tuning it needs a fast loop: observe a
bad answer → edit the prompt → retest. Hardcoded prompts make every edit a recompile + redeploy. So
templates are stored in MySQL (`prompt_template`) and edited live via the admin API — no code change,
effective on the next request. Adding a question type or output format means adding a row, not editing
`PromptBuilder.java`.

---

## Assembly (`PromptBuilder.build`)

A single prompt string is assembled per request from templates + resolved format:

```
resolve PromptMode  ← (QuestionType, strategyName, question)   via AnswerFormatResolver
select typeHint key ← PromptMode
system_prompt
  ↳ {{typeHint}}            — format rules for the resolved mode
  ↳ {{companyContextHint}}  — injected only when a focus company is detected
  ↳ {{historySection}}      — last 2 turns (Redis), empty if none
  ↳ {{context}}             — top-N reranked chunks
  ↳ {{question}}
```

All placeholders are **optional by substitution** — `companyContextHint` / `historySection` resolve to
`""` when absent. No if/else branching in `PromptBuilder`, just string replacement.

---

## Output Format = f(QuestionType, RetrievalStrategy)

`AnswerFormatResolver` picks a `PromptMode` from the question type **and the retrieval strategy that ran**
— scope is already encoded in `strategyName`, so no extra classifier is needed. Each mode maps to one
type-hint template:

| PromptMode | When | Type-hint template | Format intent |
|------------|------|---------------------|---------------|
| `SINGLE_STAR` | BEHAVIORAL, single company | `type_hint_behavioral` | one STAR story, 3 sentences |
| `MULTI_COMPANY_SUMMARY` | MULTI_COMPANY strategy, non-behavioral | `type_hint_multi_company_summary` | cross-company summary |
| `MULTI_COMPANY_BEHAVIORAL` | MULTI_COMPANY strategy + behavioral theme | `type_hint_multi_company_behavioral` | one story per company |
| `FACTUAL_SHORT` | FACTUAL | `type_hint_factual` | 1 short sentence |
| `DEFAULT` | TECHNICAL / STRATEGIC / other | `type_hint_technical` · `type_hint_strategic` · `type_hint_default` | type-specific budget |

Multi-company detection reuses `BEHAVIORAL_THEMES` keywords (leadership, conflict, ownership, mentoring…)
so "highlight your leadership across companies" gets one story per employer, while "what did you do at
each company" gets a compact summary.

> `STRATEGIC` and the other gate types (INVALID / OUT_OF_SCOPE / CONTACT_REDIRECT / PERSONAL) normally
> short-circuit at `QuestionClassifier` with a canned message and never reach `PromptBuilder`.

---

## Templates

**Query-time** (per chat request):

- `system_prompt` — persona, scope rule, style, compression, output constraints, grounding, role
  accuracy, company scope, fallback.
- `type_hint_*` — the seven format templates above.

**`companyContextHint`** (built in Java, not a stored template): names the topic company and lists **every
other known company to ignore**, so leaked mixed-label context isn't used. Also re-asserts company scope
under pronouns ("it / that / this"). This is the prompt-level guard in the company-isolation chain.

**Ingestion-time** (per upload, hot-reloadable):

- `contextual_retrieval_prefix` — 1–2 sentence context prefix per chunk. `{{docText}}`, `{{chunkText}}`.
- `raptor_document_summary` — 4–6 sentence document summary chunk. `{{fileName}}`, `{{category}}`, `{{docText}}`.

---

## Design Principles

**Rules over instructions.** "Be concise" fails; "1 sentence ≤ 12 words" works.

**Negative constraints beat positive ideals.** Blacklisting trailing phrases ("no *this taught me*",
"no *going forward*") beats describing a good answer.

**Type isolation.** Each type/mode gets only its own rules — a strict factual word limit must not
constrain a behavioral story. This is why hints are split templates, not one monolith.

**Optional placeholders over conditional Java.** Empty-string substitution replaces branching.

---

## Evolution (condensed)

Prompts started as one monolithic Java string (all types, same rules), moved to MySQL with per-type hint
rows (type rules stopped polluting other types; edits needed no deploy), then tightened iteratively — each
baseline test failure became a concrete rule:

| Observed failure | Fix |
|------------------|-----|
| Answer printed `(Word count: 3)` | banned annotations via `OUTPUT CONSTRAINT` |
| Behavioral added a 4th "this taught me…" sentence | `HARD STOP` + trailing-phrase blacklist |
| Claimed "I owned" when context said "participated in" | `ROLE ACCURACY` — match ownership exactly |
| Alipay answer leaked NetEase details | `COMPANY SCOPE` + `{{companyContextHint}}` |
| "How long in Australia?" returned visa/family | factual ENTITY scope rule + examples |
| Behavioral gave two stories | "PICK ONE STORY ONLY" |
| Cross-company question needed per-employer structure | multi-company modes + dedicated hints |

The current layer — resolving output format from question type + retrieval strategy — grew out of that
last row: cross-company questions need a different shape than single-company ones, and the strategy already
knows which it is.
