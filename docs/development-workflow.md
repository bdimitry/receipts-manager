# Development Workflow

## Purpose

This document describes the simplified project-local Codex workflow for `Home Budget & Receipts Manager`.

The local operating model is now intentionally narrow:

- OCR-first
- corpus-first
- parser deferred by default
- percentage-based reporting required

## Default Workflow

### 1. Start With Strategy

Use [skills-catalog.md](skills-catalog.md) and begin with:

- `$receipt-ocr-global-strategy`

First decide:

- which layer is the bottleneck
- which zone is affected
- whether the proposed fix is global or overfit

### 2. Implement Only The Right Layer

If the task is still OCR-side, use:

- `$receipt-ocr-implementation`

Default rule:

- stay on OCR extraction, reconstruction, layout, or canonical text
- do not drift into parser work unless the prompt explicitly asks for parser work

### 3. Evaluate On The Corpus

After non-trivial OCR work, use:

- `$receipt-ocr-evaluation`

Mandatory evaluation requirements:

- full mandatory corpus:
  - `1.jpg`
  - `2.jpg`
  - `3.jpg`
  - `4.jpg`
  - `5.jpg`
  - `6.pdf`
- per-receipt percentage scores
- corpus average
- explicit note on the next bottleneck

For backend regression checks, prefer the truth corpus integration harness:

- expected truth lives in `src/test/resources/fixtures/ocr/truth-corpus/expected-receipt-results.json`
- OCR line fixtures live in `src/test/resources/fixtures/ocr/`
- `ReceiptOcrTruthCorpusIntegrationTests` runs upload -> OCR queue -> reconstruction -> normalization -> parser -> validation -> persistence -> `/ocr`
- scoring compares product-visible fields against the human expected result, not against OCR confidence
- `gateMode=STRICT` means the sample is an acceptance gate and must stay above `minimumScore`
- `gateMode=BASELINE` means the sample is report-only because it is still a known weak case; it must still run end-to-end and print a score, but it should not be presented as accepted quality
- per-receipt `minimumScore` values are intentionally explicit and should be tightened or promoted from `BASELINE` to `STRICT` as the pipeline improves

### 4. Run Browser Verification When Product Output Changes

If the change affects product-visible OCR behavior:

- upload real receipts through the live app
- inspect receipt detail
- compare OCR/canonical/product-visible quality

Browser verification is part of evaluation, not a separate local operating model anymore.

### 5. Sync Docs Only Where Behavior Changed

Use `docs-sync` when the behavior changed enough that future sessions would otherwise mistrust the docs.

Prefer the smallest accurate doc set:

- `README.md` only if it really changed
- directly affected OCR docs
- local workflow docs when the operating model changes

## Current Project Rules

- Do not optimize one receipt at the expense of the corpus.
- Do not treat sample-specific transcript repair as success.
- Keep `5.jpg` clean as the sanity baseline.
- Keep `2.jpg` strong without memorizing it.
- Treat percentage-based reporting as mandatory, not optional polish.

## Roles

Use only the local roles from [agent-roster.md](agent-roster.md):

1. `OCR Strategy Engineer`
2. `OCR Reconstruction Engineer`
3. `OCR Evaluation Analyst`

## Required Task Summary

Every non-trivial OCR task should close with:

- files changed
- role used
- skill used
- checks or browser verification run
- per-receipt percentages when applicable
- corpus average when applicable
- next bottleneck
