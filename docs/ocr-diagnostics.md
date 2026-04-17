# OCR Diagnostics

## Purpose

This document explains how to inspect the current PaddleOCR baseline on the `dmitr/experiment-paddleocr` branch without mixing OCR diagnosis with receipt parsing.

The goal of this stage is to answer three questions clearly:

- which PaddleOCR profile and model configuration are actually active
- what raw OCR output the engine produces before business logic
- whether quality is lost inside the OCR engine itself or later during mapping

## Active PaddleOCR Configuration

The helper now uses explicit OCR profiles instead of a hidden single-language default.

Compared profiles:

- `en`
- `cyrillic`
- `latin`

Current selected baseline profile for the standard OCR branch:

- profile: `en`
- OCR version: `PP-OCRv4`
- detector: `DB`
- recognizer: `SVTR_LCNet`
- detector model: `en_PP-OCRv3_det_infer`
- recognizer model: `en_PP-OCRv4_rec_infer`
- classifier model: `ch_ppocr_mobile_v2.0_cls_infer`
- default angle classification: `false`

`cyrillic` and `latin` remain available as controlled comparison profiles.

## Diagnostic Endpoints

The Paddle helper exposes two diagnostic-friendly entry points:

- `GET /diagnostics/config`
- `POST /ocr?debug=true`

`GET /diagnostics/config` returns:

- `activeProfile`
- `defaultConfig`
- `availableProfiles[]`
- preprocessing defaults

`POST /ocr?debug=true` returns:

- normal OCR payload:
  - `profile`
  - `rawText`
  - `lines[]`
- diagnostics:
  - `engineConfig`
  - `rawEngineLines[]`
  - `rawEngineText`
  - `mappedLines[]`
  - `mappedRawText`

This lets you compare:

1. raw PaddleOCR output
2. mapped line output
3. helper `rawText` assembly

Post-OCR normalization is now intentionally outside the helper. Java/Spring owns `normalizedLines[]` so that parser-facing text cleanup, tagging, and future business integration all live on the backend side.

That ownership is now active in the real OCR flow as well: Spring builds a parser-ready normalized line stream from helper `lines[]`, and downstream OCR parsing already consumes that Java-prepared text rather than the raw helper blob.

The next layer after diagnostics now also lives in Java:

- `ReceiptOcrParser` consumes `normalizedLines[]`
- it returns a baseline `ParsedReceiptDocument`
- `ReceiptOcrValidationService` marks suspicious parse results with warnings and a weak-quality flag
- no parser logic lives in the Python helper anymore

## Diagnostic Corpus

The reproducible comparison corpus is generated inside the helper and currently contains:

- `clean-english-receipt`
- `cyrillic-transaction`
- `mixed-script-receipt`
- `bank-like-document`
- `pdf-rendered-payment-page`

The corpus is intentionally diagnostic rather than business-oriented. It focuses on:

- label readability
- date and total fidelity
- numeric stability
- mixed-script corruption
- line integrity

## Profile Comparison Criteria

Each profile run is evaluated on:

- expected label hits
- expected numeric hits
- number of mixed-script lines
- number of obviously broken long lines

This is a practical reproducible score, not a full OCR benchmark. It is meant to support baseline profile selection transparently.

## Where Quality Currently Degrades

The current diagnosis shows that the main quality loss is already present inside the OCR engine on some inputs, not in the line mapper.

What we observed:

- for clean Latin-heavy and mixed-script samples, `cyrillic` substitutes visually similar glyphs and increases script mixing
- for the same samples, `en` produces cleaner full-line recognition and better preserves dates, totals, and receipt labels
- `cyrillic` remains informative on Cyrillic-heavy text, but as a global default it degrades mixed transactional documents too early
- mapped `lines[]` and assembled `rawText` mostly preserve the engine text faithfully
- the mapper mainly changes ordering and line grouping, not character identity

That means the current risk is not "post-processing corrupts good OCR," but rather "the baseline recognizer choice determines whether mixed-script transactional documents start from a usable OCR result at all."

## Reproducible Local Scenario

1. Start the stack:

```powershell
docker compose up -d --build
```

2. Inspect current config:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8083/diagnostics/config"
```

3. Inspect raw engine output versus mapped output:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=true&debug=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

4. Run the bundled comparison script across OCR profiles:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic latin --preprocess true
```

5. Optionally include the local real-check corpus from `C:\Users\dmitr\Pictures\чеки`:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic latin --preprocess true --local-corpus-dir "C:/Users/dmitr/Pictures/чеки"
```

The script runs the full diagnostic corpus and, for each profile, prints:

- `engineConfig`
- `rawEngineText`
- `rawEngineLines`
- `mappedLines`
- `mappedRawText`
- `evaluation`

At the end it also prints:

- per-profile average score
- total mixed-script lines
- total broken lines
- recommended baseline profile

## Practical Conclusion For The Next Step

The current controlled comparison supports this baseline decision:

- the standard OCR-first branch should use `en` as the default baseline profile
- `cyrillic` should remain a diagnostic comparison profile and a possible future fallback, not the implicit default
- line-based output is good enough to continue toward normalization and parser experiments
- future modules should still validate the chosen baseline on more real receipts, especially local-language retail documents

The current branch has now started that validation work. The practical next investigation should focus on one of these paths:

- hardening validation heuristics on a larger real-world receipt corpus
- deciding whether a simple primary-plus-fallback profile strategy is worth adding later
- then moving into parser refinement and stronger sanity checks on top of the current normalized line stream
