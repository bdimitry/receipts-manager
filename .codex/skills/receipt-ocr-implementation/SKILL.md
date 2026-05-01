---
name: receipt-ocr-implementation
description: Implement OCR-side improvements for Home Budget & Receipts Manager. Use when changing extraction, geometry, row reconstruction, canonical OCR text, reading order, detached amount recovery, summary pairing, or barcode and service isolation, and when parser work should stay out of scope unless the task explicitly asks for it.
---

# Receipt OCR Implementation

## Overview

Use this skill for OCR-side engineering only. It is the implementation companion to global OCR strategy and corpus evaluation.

## In Scope

- OCR extraction quality
- bbox and geometry handling
- row reconstruction
- reading order recovery
- detached amount recovery
- summary row pairing
- barcode and service isolation
- canonical OCR text cleanup based on generic receipt structure

## Out Of Scope By Default

- parser business rules
- validation tuning
- UI work
- sample-specific transcript memorization
- merchant-specific hacks
- broad multi-pass OCR or routing changes

Only cross those boundaries if the task explicitly asks for it.

## Implementation Rules

- Preserve raw OCR evidence and traceability.
- Prefer generic structural rules over merchant-specific rewrites.
- Think by zones:
  - header
  - body
  - summary
  - footer
- Keep `2.jpg` useful, but do not optimize only for `2.jpg`.
- Do not move OCR debt into parser hacks.

## Required Verification

At minimum:

1. run the relevant OCR-side or backend tests
2. check the affected receipts against the mandatory corpus
3. hand the result to evaluation with explicit before/after evidence

If the task changes product-visible OCR behavior, include live browser verification in the follow-up.

## Expected Output

Finish with:

- files changed
- structural or OCR rules changed
- before/after OCR or canonical text examples
- what improved globally
- what still remains the OCR-side bottleneck
