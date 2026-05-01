# Project Skills Catalog

## Purpose

This document lists the minimal project-local Codex skills that remain after the OCR-only workflow reset.

The local skill layer is now intentionally small:

- OCR-first
- corpus-driven
- parser deferred by default
- no sample-specific hacks as a success criterion

## Where Local Skills Live

Project-local skills live only here:

- `C:\Users\dmitr\Documents\Receipts-Manager\.codex\skills`

Global system skills in `C:\Users\dmitr\.codex\skills` remain separate and are not part of this project-local reset.

## Current Local Skills

### `receipt-ocr-global-strategy`

Purpose:

- choose the next OCR-first direction
- check whether a change is global or overfit
- enforce zone-aware OCR thinking:
  - header
  - body
  - summary
  - footer

Use it before implementation when the main question is "what should we improve next and why?"

### `receipt-ocr-evaluation`

Purpose:

- run the mandatory corpus
- compare OCR, canonical, and product-visible quality
- require percentage-based reporting
- produce per-receipt scores and corpus average

Use it after non-trivial OCR changes and whenever browser-visible verification matters.

### `receipt-ocr-implementation`

Purpose:

- implement OCR-side improvements only
- focus on extraction, geometry, reconstruction, layout, and canonical OCR text
- keep parser out of scope unless the task explicitly says otherwise

Use it when the work belongs to OCR or document-structure engineering, not downstream parsing.

## What Was Removed From The Local Skill Layer

The local project setup no longer keeps separate repo-local skills for:

- one-receipt-first canonical-text tuning
- browser regression as a standalone local skill
- broad multi-role workflow scaffolding

Those concerns now live inside the new three-skill model:

- strategy decides direction
- implementation changes OCR-side behavior
- evaluation runs corpus and browser-visible checks

## How To Use The New Setup

For most future OCR prompts, use this sequence:

1. `$receipt-ocr-global-strategy`
2. `$receipt-ocr-implementation`
3. `$receipt-ocr-evaluation`

Add global companion skills only when needed, for example:

- `docs-sync` when docs changed
- a browser-verification skill if the environment exposes one and the task explicitly asks for UI proof

## Operating Rule

Before starting non-trivial OCR work, decide:

1. which of the three local OCR skills is primary
2. which role from [agent-roster.md](agent-roster.md) owns the work
3. which percentage-based result must be reported at the end
