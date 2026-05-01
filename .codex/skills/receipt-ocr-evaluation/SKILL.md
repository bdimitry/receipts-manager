---
name: receipt-ocr-evaluation
description: Run corpus-based evaluation for the Home Budget & Receipts Manager OCR pipeline. Use when checking OCR changes against the mandatory corpus, comparing OCR, canonical, and product-visible quality, performing live browser verification, or writing percentage-based before-and-after reports with per-receipt scores and corpus averages.
---

# Receipt OCR Evaluation

## Overview

Use this skill to turn OCR work into measurable evidence. It owns corpus checks, percentage-based reporting, and browser-visible verification.

## Mandatory Corpus

Default regression corpus:

- `1.jpg`
- `2.jpg`
- `3.jpg`
- `4.jpg`
- `5.jpg`
- `6.pdf`

Treat `5.jpg` as the clean sanity baseline and `2.jpg` as the strongest noisy-retail comparison anchor.

## Scoring Rules

Report three practical percentages for every receipt:

1. `OCR / canonical text quality %`
2. `Product-visible parse quality %`
3. `Overall quality %`

These scores may be estimated, but they must be:

- explainable
- consistent across the corpus
- clearly labeled as practical engineering estimates rather than strict scientific metrics

Also report:

- before vs after for targeted receipts
- average overall % across the full corpus
- one short reason for every weak score

## Evaluation Workflow

1. Run the relevant backend or OCR tests first.
2. Re-run the mandatory corpus through the current live pipeline.
3. Inspect:
   - raw OCR text
   - reconstructed or canonical text
   - parsed result
   - warnings
   - browser-visible receipt detail
4. Separate:
   - OCR-side quality
   - parser or validation quality
   - UI-visible product quality
5. State the next bottleneck clearly.

## Guardrails

- Do not hide regressions behind nicer wording.
- Do not claim precision the evidence does not support.
- Do not score one receipt with a special rule the rest of the corpus does not get.
- If browser-visible behavior conflicts with tests, trust the browser result and call that out.

## Expected Output

Finish with:

- per-receipt OCR %, parse %, and overall %
- corpus average overall %
- before/after percentages for the targeted receipts
- live browser verification summary
- next recommended module
