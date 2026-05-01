# Agent Roster

## Purpose

This document defines the minimal local role model for project-specific Codex work in `Home Budget & Receipts Manager`.

The roster is intentionally small and OCR-first.

## Core Rule

Every delegated task should still name:

- the role that owns it
- the branch or module context
- the write scope
- the required verification
- the expected return artifacts

## Current Roles

### OCR Strategy Engineer

Purpose:

- choose the next OCR direction
- check whether a proposed fix is global or overfit
- keep parser work deferred until OCR quality justifies it

Default output:

- bottleneck by layer and zone
- why the direction is general
- what should be implemented next

### OCR Reconstruction Engineer

Purpose:

- implement OCR extraction, reconstruction, geometry, layout, and canonical text improvements
- keep the work on the OCR side of the pipeline
- preserve traceability and avoid sample-specific hacks

Default output:

- files changed
- OCR or structural rules changed
- before and after OCR examples
- remaining OCR-side limitation

### OCR Evaluation Analyst

Purpose:

- run corpus checks
- score OCR/canonical/product-visible quality
- verify whether the improvement is global
- include browser-visible verification when the change affects the live product result

Default output:

- per-receipt percentages
- corpus average
- before vs after for targeted samples
- next bottleneck

## Practical Ownership Model

Use this order for most non-trivial OCR work:

1. `OCR Strategy Engineer`
2. `OCR Reconstruction Engineer`
3. `OCR Evaluation Analyst`

Do not create more roles unless the project direction changes materially.
