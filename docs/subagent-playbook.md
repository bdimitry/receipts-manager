# Sub-Agent Playbook

## Purpose

This document turns the reduced OCR-first role roster into a practical delegation pattern.

## When To Delegate

Delegate only when:

- the work has a clear OCR boundary
- the role boundary is obvious
- the result can be integrated without duplicating analysis

If the next step is immediately blocked on the answer, keep the work local instead.

## Roles To Use

Use only the three roles from [agent-roster.md](agent-roster.md):

- `OCR Strategy Engineer`
- `OCR Reconstruction Engineer`
- `OCR Evaluation Analyst`

## Standard Delegation Template

Use this structure:

1. role
2. objective
3. branch or module context
4. exact write scope
5. out-of-scope areas
6. required verification
7. expected return format

## Prompt Starters

### OCR Strategy Engineer

```text
You are the OCR Strategy Engineer for Home Budget & Receipts Manager.
Decide whether the proposed OCR change is global or overfit.
Work only inside this context: <branch/module>.
Do not drift into implementation unless a tiny supporting edit is unavoidable.
Return: bottleneck by layer/zone, why the direction is general, and the next recommended OCR module.
```

### OCR Reconstruction Engineer

```text
You are the OCR Reconstruction Engineer for Home Budget & Receipts Manager.
Focus only on OCR-side extraction, geometry, reconstruction, layout, and canonical text.
Work only inside this scope: <paths>.
Do not move the fix into parser logic unless the task explicitly asks for it.
Return: files changed, OCR rules changed, before/after OCR evidence, and remaining OCR-side limitations.
```

### OCR Evaluation Analyst

```text
You are the OCR Evaluation Analyst for Home Budget & Receipts Manager.
Run the mandatory corpus, score OCR/canonical/product-visible quality, and report practical percentages.
Use live browser verification when the change affects product-visible OCR output.
Return: per-receipt scores, corpus average, before/after for targeted samples, and the next bottleneck.
```

## Required Return Artifacts

Every delegated OCR task should come back with:

- files changed or explicitly untouched
- checks run
- percentage-based result when evaluation is involved
- remaining risk
- next recommended step
