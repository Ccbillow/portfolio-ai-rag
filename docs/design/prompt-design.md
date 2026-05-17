# Prompt Design

## Why Prompt Management Matters

Prompt quality is the most direct lever on answer quality in a RAG system. Getting it right requires fast iteration: observe a bad answer, update the prompt, test again. If prompts are hardcoded in Java, every change requires a recompile and redeploy — making that loop slow and expensive.

This project treats prompts as first-class data, not code:

- **Configurable** — all templates live in MySQL, editable via admin API without touching Java
- **Decoupled** — prompt logic is separate from retrieval logic; changing how answers are worded doesn't affect how chunks are retrieved
- **Manageable** — each template has a version number and updated_at; rollback is a single UPDATE
- **Extensible** — adding a new question type (e.g. COMPARISON) means adding a new row to `prompt_template`, not modifying `PromptBuilder.java`

---

## How the Prompt Is Assembled

`PromptBuilder` assembles a single prompt string at query time from templates stored in MySQL:

```
[system_prompt]
  ↳ {{typeHint}}           — type-specific rules injected by QuestionClassifier output
  ↳ {{companyContextHint}} — injected only when a focus company is detected
  ↳ {{historySection}}     — last 2 conversation turns (from Redis), omitted if none
  ↳ {{context}}            — top-N reranked chunks
  ↳ {{question}}           — the user's question
```

The admin API (`PUT /api/admin/prompt-templates/{id}`) updates any template live. Changes take effect on the next request.

---

## Current Template Structure (May 2026)

**system_prompt** covers: persona, scope rule, style, compression rules, output constraints, grounding, role accuracy, company scope, fallback behavior. Full content in `prompt_template` table (id=1, v3).

**Type hints** (injected as `{{typeHint}}`):

| Type | Core rules | Version |
|------|-----------|---------|
| FACTUAL | 1 sentence ≤12 words; include entity name if question names one | v6 |
| TECHNICAL | 3 sentences for role/project; 1 sentence per project if multiple; 4 points max for skills | v4 |
| BEHAVIORAL | Exactly 3 sentences (Challenge / Action / Result); blank line between each; HARD STOP after sentence 3 | v5 |
| STRATEGIC | 2 sentences; diplomatic | v1 |
| DEFAULT | Max 3 sentences | v1 |

---

## Evolution

### Phase 2–4: Monolithic prompt in Java

One string in `PromptBuilder.java` covering all question types with the same rules. Problems: "be concise" was too vague; factual questions and behavioral stories got the same length budget; no company scope enforcement.

### Phase 5: Per-type length limits, still in Java

Added `SCOPE RULE` and type-specific length hints inside the system prompt. Helped, but all rules still went to every question type — a strict word limit on factual questions also constrained behavioral answers.

### Phase 7: Moved to MySQL, type hints split out

Created `prompt_template` table. System prompt became a template; each question type got its own hint row. Initial hints were simple one-liners. **Key win:** type-specific rules no longer polluted other types, and prompt changes required no code deploy.

### Phase 8–9: Iterative tightening from test results

Every baseline test round exposed a new failure. Every failure became a new rule:

| Observed failure | Fix |
|-----------------|-----|
| Answer included `(Word count: 3)` | Removed counting instruction; added `OUTPUT CONSTRAINT` banning annotations |
| Behavioral had 4th sentence ("this taught me...") | `HARD STOP` rule + blacklist of trailing phrases |
| Claude said "I owned" when context said "participated in" | Added `ROLE ACCURACY` — match ownership level exactly |
| Alipay answer included NetEase details | Added `COMPANY SCOPE` + `{{companyContextHint}}` placeholder |
| Company name buried mid-sentence | `COMPANY NAME IN ANSWER`: first sentence must begin with "At [CompanyName]," |
| "How long in Australia?" returned visa and family details | Added ENTITY scope rule + concrete examples to factual hint |
| Behavioral gave 2 separate stories | Added "PICK ONE STORY ONLY" |
| Technical role question returned 6 sentences | Explicit per-sub-type budgets (role → 3, multiple projects → 1 each) |
| Behavioral sentences ran together | Mandatory blank line between each sentence |

---

## Design Principles

**Rules over instructions.** "Be concise" doesn't work. "1 sentence ≤12 words" does.

**Negative constraints beat positive ideals.** Listing forbidden phrases ("no this taught me", "no going forward") is more effective than describing a good answer.

**Type isolation.** Mixing all rules in one prompt means every type gets rules that don't apply to it. A strict factual word limit shouldn't constrain a behavioral story.

**Optional placeholders over conditional Java.** `{{companyContextHint}}` resolves to an empty string when no company is detected. `{{historySection}}` resolves to empty when there's no history. No if/else in `PromptBuilder` — just template substitution.
