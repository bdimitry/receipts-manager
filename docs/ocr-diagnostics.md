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
- `polish`
- `german`
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

`cyrillic`, `polish`, `german`, and `latin` remain available as controlled comparison profiles.

The product flow now persists OCR routing metadata so the selected route is diagnosable after processing:

- `receiptCountryHint`
- `languageDetectionSource`
- `ocrProfileStrategy`
- `ocrProfileUsed`

Current routing priority:

1. explicit user-selected country hint
2. auto-detected script or language from preview OCR text
3. safe fallback profile

Current country-to-strategy mappings:

- `UKRAINE` -> `en+cyrillic`
- `POLAND` -> `en+polish`
- `GERMANY` -> `en+german`

Manual country hints now select a comparison strategy, not a blind hard force of the local profile. Spring still quality-scores the candidate OCR outputs inside that strategy, so `ocrProfileUsed` can remain `en` when the local-language result is more fragmented than the English fallback.

## Diagnostic Endpoints

The Paddle helper exposes two diagnostic-friendly entry points:

- `GET /diagnostics/config`
- `POST /ocr?debug=true`

`GET /diagnostics/config` returns:

- `activeProfile`
- `defaultConfig`
- `availableProfiles[]`
- preprocessing defaults
- current profile descriptions

`POST /ocr?debug=true` returns:

- normal OCR payload:
  - `profile`
  - `rawText`
  - `lines[]`
  - `headerRescueApplied`
  - `pages[].headerRescueApplied`
  - `pages[].headerRescueStrategy`
- diagnostics:
  - `engineConfig`
  - `rawEngineLines[]`
  - `rawEngineText`
  - `mappedLines[]`
  - `mappedRawText`
  - `headerRescue[]`

This lets you compare:

1. raw PaddleOCR output
2. mapped line output
3. helper `rawText` assembly

For receipts whose first header rows are much weaker than the rest of the document, the helper now also runs a conservative first-page header rescue pass:

- it OCRs the top crop of the original first page at `2x`
- it keeps only the pre-anchor header prefix
- it reprojects those rescued rows into processed-page reading order using the first anchor row
- it leaves the rest of the receipt untouched

This keeps the rescue traceable in diagnostics without turning the whole receipt into a second OCR pass.

Post-OCR normalization is now intentionally outside the helper. Java/Spring owns `normalizedLines[]` so that parser-facing text cleanup, tagging, and future business integration all live on the backend side.

That ownership is now active in the real OCR flow as well:

- Spring first runs `ReceiptOcrStructuralReconstructionService` to rebuild a geometry-aware line stream from helper `lines[]`
- then it builds a parser-ready normalized line stream from those reconstructed lines
- downstream OCR parsing already consumes that Java-prepared text rather than the raw helper blob
- recent reconstruction hardening also splits vertically stacked numeric fragments that were being over-clustered into one row and keeps summary/tax amount pairing separate

The backend OCR detail response now also exposes:

- `reconstructedLines[]`
  - `text`
  - `bbox`
  - `sourceOrders[]`
  - `sourceTexts[]`
  - `structuralTags[]`

For hard receipts like `2.jpg`, reconstruction may now also apply a small canonical OCR repair pass on top of the geometry-backed rows:

- receipt keywords such as `Чек`, `Штрих код`, `Сума`, `ПДВ`
- payment-service rows such as `ПЛАТІЖНА СИСТЕМА`, `КОД ТРАНЗ.`, `КОД АВТ.`
- payment-card summary phrasing such as `БЕЗГОТІВКОВА КАРТКА ... грн`

This is still traceable because the original OCR evidence remains available in `sourceTexts[]`, and the layer does not rewrite arbitrary product text.

The reconstruction layer is now intentionally generalized:

- tests no longer lock in a full memorized `2.jpg` transcript
- merchant-specific header or footer injection was removed
- `2.jpg` quality is now held by reusable structure and label rules rather than by exact hardcoded lines

The next layer after diagnostics now also lives in Java:

- `ReceiptOcrParser` consumes `normalizedLines[]`
- it returns a baseline `ParsedReceiptDocument`
- `ReceiptOcrValidationService` marks suspicious parse results with warnings and a weak-quality flag
- `ReceiptOcrKeywordLexicon` provides a tiny explicit English/Ukrainian/Russian keyword registry for safe summary, payment, barcode, and merchant heuristics
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

That means the current risk is not only "post-processing corrupts good OCR," but also "preprocessing can damage a clean receipt before OCR even starts."

The current preprocessing baseline on this branch now treats that as a first-class defect:

- clean baseline receipts should stay on a soft preprocessing path
- PDF-like pages should avoid destructive thresholding
- threshold-guided processing should be reserved for noisy receipt photos where it actually helps

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

For preprocessing regression checks, run the same file twice:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=false&debug=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"

curl -X POST "http://localhost:8083/ocr?preprocess=true&debug=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

Compare:

- `pages[].strategy`
- `pages[].stepsApplied`
- `diagnostics.rawEngineText`
- `diagnostics.mappedRawText`

4. Run the bundled comparison script across OCR profiles:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic polish german latin --preprocess true
```

5. Optionally include the local real-check corpus from `C:\Users\dmitr\Pictures\чеки`:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic polish german latin --preprocess true --local-corpus-dir "C:/Users/dmitr/Pictures/чеки"
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

- the standard OCR-first branch should keep `en` as the safe default fallback profile
- `cyrillic`, `polish`, and `german` should be promoted through explicit routing rather than becoming hidden global defaults
- line-based output is good enough to continue toward normalization and parser experiments
- future modules should still validate the chosen routing strategy on more real receipts, especially local-language retail documents

The current branch has now started that validation work. The practical next investigation should focus on one of these paths:

- hardening validation heuristics on a larger real-world receipt corpus
- continuing preprocessing tuning with the mandatory corpus when a clean receipt starts to degrade visually
- deciding whether a simple primary-plus-fallback profile strategy is worth adding later
- then moving into parser refinement and stronger sanity checks on top of the current normalized line stream

## Self-Contained Verification

The repository now ships with a real Maven Wrapper again, so backend verification no longer requires a globally installed Maven.

Standard backend verification from the repo root:

```powershell
.\mvnw.cmd test
```

The OCR integration test base now targets the real Paddle-first path:

- Postgres + Flyway
- LocalStack
- Paddle helper container
- Telegram mock

Legacy Tesseract is now explicit fallback coverage only and is not the default integration-test path.
