---
name: receipt-ocr-global-strategy
description: Guide OCR-first strategy work for Home Budget & Receipts Manager. Use when deciding what OCR module to improve next, checking whether a proposed change is global or overfit, planning corpus-first OCR work, or enforcing zone-aware thinking across header, body, summary, and footer before parser work resumes.
---

# Receipt OCR Global Strategy

## Overview

Use this skill to keep OCR work global, corpus-driven, and architecture-safe. It is for strategy and prioritization, not implementation details.

## Use This Skill To Decide Direction

Before changing code, classify the current bottleneck by:

- layer:
  - OCR recognition
  - structural reconstruction
  - canonical text cleanup
  - normalization
  - parser
  - validation
- zone:
  - header
  - body
  - summary
  - footer

Do not let one attractive sample dominate the roadmap. A strong result on `2.jpg` is useful evidence, not permission to memorize `2.jpg`.

## Current Operating Rules

- OCR pipeline comes first.
- Parser work is deferred by default until OCR quality is strong enough.
- Prefer global rules over receipt-specific repairs.
- Treat percentage-based corpus reporting as mandatory for non-trivial OCR work.
- Keep sample-specific transcript memorization out of success criteria.

## Strategy Checklist

When a task proposes a change, check:

1. Is the change driven by one receipt or by a repeatable pattern?
2. Does it improve a zone or layer that appears across the corpus?
3. Does it preserve `5.jpg` as the clean sanity baseline?
4. Does it keep `2.jpg` strong without hardcoding it?
5. Does it avoid pushing OCR debt downstream into parser hacks?

If any answer is "no", narrow or reject the change.

## Expected Output

Finish with:

- current bottleneck by layer and zone
- why the proposed direction is general rather than overfit
- which receipt or zone is the validation anchor
- what the next module should be after the current change
