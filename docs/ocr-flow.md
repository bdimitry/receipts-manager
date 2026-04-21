# OCR Flow

## Purpose

This document explains how receipt OCR works, which OCR backends are available, what is stored, how line items are parsed, and how to verify the flow locally.

## Why OCR Exists In The Product

OCR is the foundation for future user-assisted receipt interpretation. At this stage it gives the system:

- raw extracted receipt text
- a best-effort parsed store name
- a best-effort parsed total amount
- a best-effort parsed purchase date
- a best-effort parsed set of receipt line items

This prepares the backend for later semi-automatic purchase assistance without creating purchases automatically today.

## Trigger Model

OCR starts asynchronously after a successful receipt upload.

Flow:

1. `POST /api/receipts/upload` stores the file in S3 and metadata in PostgreSQL
2. the upload request now also stores the selected `currency`
3. the upload request can optionally store `receiptCountryHint`
4. after transaction commit, `ReceiptOcrQueueProducer` publishes a message to `receipt-ocr-queue`
5. `ReceiptOcrQueueConsumer` polls the queue
6. the consumer marks the receipt `PROCESSING`
7. the consumer downloads the file from S3
8. `ReceiptOcrRoutingService` resolves OCR profile strategy in this order:
   - user-selected country hint
   - auto-detected script or language from preview OCR text
   - safe fallback profile
9. the configured OCR client sends the file to the selected OCR helper container with routing options
10. the raw OCR text is stored
11. `ReceiptOcrStructuralReconstructionService` rebuilds a cleaner geometry-aware line stream before normalization
12. `ReceiptOcrLineNormalizationService` builds Java `normalizedLines[]` and parser-ready text from reconstructed lines
13. `ReceiptOcrParser` performs best-effort baseline parsing for summary fields, parsed currency, and line items
14. `ReceiptOcrValidationService` runs sanity checks on the parsed result
15. the receipt persists those OCR artifacts, routing metadata, and validation warnings and is marked `DONE` or `FAILED`

## OCR Backend Options

The project still contains two helper backends behind the same application OCR entry point, but the branch standard is now Paddle-first.

### PaddleOCR Backend

This is the standard OCR backend used by the main local stack, backend integration tests, and the current product flow.

It runs as a separate helper container and exposes:

- `POST /ocr`

Response contract:

- `rawText`
- `lines`
  - `text`
  - `confidence`
  - `order`
  - `bbox` when coordinates are available

The Spring Boot application uses it by default through:

- `app.ocr.service.backend=PADDLE`
- `app.ocr.service.base-url=http://...:8083`
- `app.ocr.service.paddle-base-url=http://...`

### Tesseract Backend

This backend is now explicit legacy fallback coverage only.

It is kept for:

- controlled comparison work
- explicit fallback experiments
- avoiding a hard delete while the branch direction is still being validated

It is not the default runtime or integration-test path anymore.

When needed, it is selected only through explicit configuration:

- `app.ocr.service.backend=TESSERACT`
- `app.ocr.service.tesseract-base-url=http://...`

For Docker Compose, the legacy Tesseract helper is now behind the `legacy-ocr` profile instead of shipping as part of the normal stack.

The existing business parsing flow remains unchanged. The Paddle response is now normalized into both:

- `rawText` for compatibility
- ordered `lines[]` for future line-aware parsing work

The current Spring flow keeps both raw OCR text and ordered raw lines, but downstream parsing now runs on Java-normalized lines and parser-ready text instead of using the raw OCR blob as the primary parsing source.

The PaddleOCR helper now warms its baseline models during container startup. This moves the heaviest cold-start initialization away from the first live OCR request in a fresh local environment.

For Docker-based local runs, OCR endpoint values in `.env` must use container service names, not `localhost`. Inside the `app` container, `localhost` points back to the Spring Boot container itself.

Current local diagnostic profile registry on this branch includes:

- `en`
- `cyrillic`
- `polish`
- `german`
- `latin`

Current routing baseline:

- safe fallback profile: `en`
- `UKRAINE` -> `en+cyrillic`
- `POLAND` -> `en+polish`
- `GERMANY` -> `en+german`
- auto-detection can also promote `cyrillic`, `polish`, or `german` when preview OCR text is strong enough
- the final helper profile is still chosen by quality-aware candidate scoring, so a manual country hint can keep `en` if the local-language OCR result is visibly worse

The helper still returns one selected helper profile per OCR attempt. The practical "English plus chosen or detected language" strategy is implemented in Spring as candidate-profile routing and selection, not as a hidden monolithic helper default.

### OCR Keyword Lexicon Layer

Spring now also includes a small explicit OCR keyword lexicon that acts only as a safe assistive layer after OCR.

It is intentionally narrow and language-aware:

- English
- Ukrainian
- Russian

Current categories include:

- receipt summary words such as `total`, `sum`, `balance`, `сумма`, `сума`, `итого`
- service and payment words such as `visa`, `mastercard`, `terminal`, `payment`, `картка`, `банк`
- barcode and account markers such as `barcode`, `ean`, `штрих код`, `iban`, `swift`, `edrpou`
- a tiny merchant alias set such as `NOVUS` and `UkrsibBank`

The lexicon is not a fake OCR replacement. It is only used conservatively in:

- normalization tagging
- parser heuristics
- validation heuristics

It does not rewrite arbitrary OCR text and does not invent product titles.

### Paddle Preprocessing Layer

Before OCR, the Paddle helper now runs a dedicated preprocessing layer that is separate from the OCR engine itself.

Current baseline steps:

- optionally upscale smaller images, but with a capped scale factor for clean receipts
- detect and crop the dominant receipt or document area when possible
- deskew mild rotation
- denoise grayscale text regions
- improve local contrast with CLAHE-based soft contrast recovery
- choose between:
  - a `soft` path for clean and PDF-like pages
  - a `strong` path with threshold-guided darkening for noisy receipt photos

This pipeline is intentionally pragmatic. It is meant to improve typical user photos of receipts without requiring manual edits, while still staying simple enough to extend in later steps.

The key safety rule for the current branch is now:

- clean baseline receipts must not be turned into harsh binary masks
- thresholding is no longer the universal default preprocessing step
- if preprocessing makes a clean receipt visually worse for a human, that is treated as a defect

The preprocessing layer can be turned off for comparison through request override:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=false" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

Default Docker env:

- `PADDLE_OCR_PREPROCESSING_ENABLED=true`
- `PADDLE_OCR_TARGET_LONG_EDGE=1600`

The Paddle helper response now also includes lightweight debug metadata:

- `preprocessingApplied`
- `pages[]`
  - `strategy`
  - `imageSizeBefore`
  - `imageSizeAfter`
  - `stepsApplied`

### Java Line Normalization Layer

After raw OCR extraction, normalization now happens in Spring, not in the Paddle helper.

Responsibility split:

- Python helper:
  - preprocessing
  - PaddleOCR invocation
  - raw ordered line extraction
  - engine-side diagnostics
- Java backend:
  - line normalization
  - punctuation and whitespace cleanup
  - edge artifact cleanup
  - safe OCR confusion cleanup in narrow contexts
  - line tagging and lightweight classification
  - parser-ready `normalizedLines[]`

The real OCR processing path now uses Java normalization as an active downstream step, not just as response decoration:

- `OcrClient` returns raw ordered lines from the helper
- `ReceiptOcrStructuralReconstructionService` first rebuilds a safer parser-ready line structure from OCR geometry
- `ReceiptOcrLineNormalizationService` then builds a normalized document artifact in Java
- non-ignored normalized lines become the parser-ready stream
- current parser invocation already consumes that parser-ready text instead of raw helper text

### Java Structural Reconstruction Layer

Before normalization, Spring now runs a dedicated layout-aware structural reconstruction layer.

Its job is not business parsing. Instead, it uses OCR geometry and line metadata to recover a better parser-ready line stream while preserving traceability.

Current responsibilities:

- cluster OCR fragments into visual rows using bbox overlap and line center proximity
- preserve reading order top-to-bottom and left-to-right
- separate service or barcode fragments from content fragments when they share the same OCR row
- reconnect detached amount rows with nearby item-title rows
- reconnect title rows with following standalone amount rows
- preserve summary lines such as `TOTAL` or `Cyma` without flattening them into item rows

Current debug artifact:

- `reconstructedLines[]`
  - `text`
  - `order`
  - `confidence`
  - `bbox`
  - `sourceOrders[]`
  - `sourceTexts[]`
  - `structuralTags[]`

This layer is intentionally conservative:

- it never invents text that is not present in OCR output
- it keeps the original raw `lines[]` intact for diagnostics
- when rows are merged or reordered, that evidence remains visible in `sourceOrders[]` and `sourceTexts[]`

The Java normalization layer is intentionally conservative. It does not try to infer store names, totals, dates, or items.

Current Java normalization responsibilities:

- trim and collapse whitespace
- normalize punctuation noise inside receipt lines
- remove obvious trailing amount punctuation such as `0.40,`
- clean separator artifacts such as `CASH.RECEIPT` into `CASH RECEIPT`
- normalize multiplication separators like `х`, `Х`, `×` into `x`
- keep both original text and normalized text for traceability
- tag obvious line classes for downstream parsing

Current `normalizedLines[]` shape on `GET /api/receipts/{id}/ocr`:

- `originalText`
- `normalizedText`
- `order`
- `confidence`
- `bbox`
- `tags[]`
- `ignored`

Current light tags include:

- `noise`
- `barcode_like`
- `price_like`
- `header_like`
- `service_like`
- `content_like`

Current ignore rule:

- obvious barcode-like and junk lines are flagged as `ignored=true`
- price, header, and service lines are preserved for later parser decisions

Current downstream artifact:

- `NormalizedOcrDocument` in Spring holds:
  - `normalizedLines[]`
  - `parserReadyLines[]`
  - `parserReadyText`
- this is the bridge into the baseline parser layer

## Java Baseline Parser Layer

Sprint 3 adds a dedicated Java parser layer on top of `NormalizedOcrDocument`.

Responsibility split now becomes:

- Python helper:
  - preprocess image
  - run PaddleOCR
  - return raw ordered OCR lines
- Java backend:
  - normalize lines
  - tag/classify lines
  - build parser-ready line stream
  - parse merchant/date/total/currency/items from normalized lines

Current parser characteristics:

- rule-based
- line-oriented
- explainable
- best-effort
- no document-type routing
- no hard-fail validation gate; suspicious results are flagged instead

Current parser output model in Spring:

- `ParsedReceiptDocument`
  - `merchantName`
  - `purchaseDate`
  - `totalAmount`
  - `currency`
  - `lineItems[]`
- `ParsedReceiptLineItem`
  - `title`
  - `lineTotal`
  - `quantity`
  - `unit`
  - `unitPrice`
  - `rawFragment`
  - `sourceLines[]`

Current parser rules focus on:

- extracting merchant/store from early header-like lines
- extracting dates from line-level date matches
- extracting totals from total-like lines near the bottom of the document
- extracting explicit currency markers such as `UAH`, `грн`, `USD`, `EUR`, `RUB`
- building item-like lines from `content_like` / `price_like` lines
- pairing title lines with following amount-only lines
- ignoring barcode/service/noise lines as item candidates
- rejecting weak short merchant fragments before they can become parsed store names
- preferring explicit merchant aliases like `NOVUS` and `UkrsibBank` when OCR noise breaks header candidates
- treating plain late amount lines as totals only when they are connected to summary context, not as a global fallback
- filtering payment/card/promo fragments out of item extraction so they do not pollute parsed line items

The parser uses `normalizedLines[]` as its primary input. Raw OCR text remains stored for diagnostics and compatibility, but it is no longer the main parsing artifact.
The parser also no longer needs to compensate for every OCR row-order defect itself. The new structural reconstruction layer is now the primary place where detached amount rows, split title or amount rows, and interleaved barcode/service rows are cleaned up before parsing.

## Persisted OCR Artifacts

When OCR processing succeeds, the receipt now stores the same downstream OCR artifacts that the product later uses during retrieval:

- `rawOcrText`
- `reconstructedOcrLinesJson`
- `normalizedOcrLinesJson`
- `parserReadyText`
- `parsedStoreName`
- `parsedTotalAmount`
- `parsedCurrency`
- `parsedPurchaseDate`
- `receiptCountryHint`
- `languageDetectionSource`
- `ocrProfileStrategy`
- `ocrProfileUsed`
- persisted `ReceiptLineItem` rows

This means receipt detail and `GET /api/receipts/{id}/ocr` now prefer the product-integrated OCR result instead of rebuilding most of it from raw OCR text on every read.

## Java Validation Layer

After parsing, Spring now runs a separate validation and sanity-check layer.

Responsibility split:

- parser:
  - best-effort extraction of merchant, date, total, currency, and items
- validation:
  - detects suspicious or contradictory parse results
  - keeps the parse result available
  - surfaces warnings for diagnostics and product retrieval

Current validation checks include:

- suspicious merchant candidates
- suspicious totals
- suspicious dates
- suspicious line items
- item-total mismatch against document total
- payment or service content leaking into items
- noisy item titles
- inconsistent quantity, unit price, and line total math

Current persisted validation artifacts:

- `parseWarningsJson`
- `weakParseQuality`

Retrieval behavior:

- if persisted normalized OCR JSON exists, Spring restores it and returns it directly
- if an older receipt does not have the new persisted fields yet, Spring falls back to conservative reconstruction from `rawOcrText`
- raw OCR text still remains available for diagnostics

### Paddle Line-Based Output

The Paddle helper no longer treats OCR as only one opaque text block. It now maps raw PaddleOCR output through a dedicated response mapper and returns explicit receipt lines.

Current guarantees:

- lines are sorted in human reading order from top to bottom
- lines within the same visual row are sorted left to right
- each line has a stable `order`
- `rawText` is derived from ordered lines, not from raw engine output order

This gives the next parser step a predictable foundation for normalization, noise filtering, and receipt field extraction.

### Paddle Diagnostics

The helper also exposes diagnostic visibility so the OCR baseline can be inspected before business parsing:

- `GET /diagnostics/config`
- `POST /ocr?debug=true`

`debug=true` adds:

- `diagnostics.engineConfig`
- `diagnostics.rawEngineLines`
- `diagnostics.rawEngineText`
- `diagnostics.mappedLines`
- `diagnostics.mappedRawText`

The product API now complements that helper-side visibility with persisted routing metadata on `GET /api/receipts/{id}/ocr`:

- `receiptCountryHint`
- `languageDetectionSource`
- `ocrProfileStrategy`
- `ocrProfileUsed`

This lets you compare the engine's own text with:

- mapped ordered `lines[]`
- assembled helper `rawText`

Java-side normalization should now be inspected through the main backend OCR response:

- `GET /api/receipts/{id}/ocr`
- `normalizedLines[]`
- `parsedCurrency`

Current diagnostic conclusion:

- the most visible script-mixing issues are already present in raw PaddleOCR output on some inputs
- the line mapper is mostly preserving engine text and improving row order, not introducing character corruption
- the controlled comparison corpus now selects `en` as the strongest default baseline for the standard OCR branch, while `cyrillic` remains useful as a comparison profile
- preprocessing is now also treated as an explicit quality lever:
  - clean baseline receipts like `5.jpg` should stay on the soft path
  - PDF-like pages such as `6.pdf` should avoid destructive thresholding
  - hard noisy photos such as `2.jpg` and `4.jpg` may still use the stronger path when it keeps OCR usable

Service-side preprocessing tests can be run directly with:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

## Storage Model

OCR data is stored in two layers.

### Receipt Summary Fields

Stored directly on `Receipt`:

- `currency`
- `ocrStatus`
- `rawOcrText`
- `normalizedOcrLinesJson`
- `parserReadyText`
- `parsedStoreName`
- `parsedTotalAmount`
- `parsedCurrency`
- `parsedPurchaseDate`
- `parseWarningsJson`
- `weakParseQuality`
- `ocrErrorMessage`
- `ocrProcessedAt`

### Parsed Item Rows

Parsed receipt items are stored in `ReceiptLineItem`.

Fields:

- `id`
- `receiptId`
- `lineIndex`
- `title`
- `quantity`
- `unit`
- `unitPrice`
- `lineTotal`
- `rawFragment`

This makes OCR results available end-to-end instead of keeping item rows only inside parser memory.

## Status Semantics

- `NEW`: receipt uploaded, OCR not processed yet
- `PROCESSING`: worker is currently extracting OCR
- `DONE`: raw OCR text was successfully extracted
- `FAILED`: OCR extraction or processing failed

Important rules:

- `DONE` does not require every structured field to be filled
- `DONE` also does not require every line item to have quantity or unit
- if raw OCR text exists but parsing is partial, the status still stays `DONE`

## Best-Effort Parsing Model

The parser is still intentionally rule-based, but it is now more tolerant to noisy receipts.

What it does:

- keeps raw OCR text unchanged
- identifies a store candidate from meaningful header lines
- extracts totals from `SUM`, `TOTAL`, and similar lines
- extracts common date formats
- filters out barcode-like lines and service lines
- ignores address, register, cashier, and technical fragments where possible
- extracts multiple item rows when prices appear near the end of lines
- keeps amount-only lines available for pairing with nearby item titles
- avoids using obvious date or promo fragments as total candidates
- handles Cyrillic and mixed Cyrillic or Latin receipts through the multilingual helper setup
- tolerates cases where quantity or unit cannot be determined
- avoids inventing retail line items and totals for obviously bank-like or payment-style documents

What it stores even when parsing is partial:

- `title`
- `lineTotal`, when recognized
- `rawFragment`

What it does not try to do:

- parse every receipt layout perfectly
- guarantee ideal quantity detection
- reconstruct a canonical product catalog
- create purchases automatically
- reinterpret banking slips as store receipts

## Currency Handling

Receipts now carry an explicit `currency` selected by the user during upload.

Supported values:

- `USD`
- `EUR`
- `UAH`
- `RUB`

If `purchaseId` is supplied during upload, the system validates that receipt currency matches purchase currency. On mismatch, upload is rejected with `400` instead of creating inconsistent financial data.

Line items use the receipt currency as the primary currency context.

## API Surface

Receipt responses expose OCR summary fields and OCR detail fields.

Use:

- `GET /api/receipts/{id}` for receipt metadata, currency, OCR summary, and parsed line item count
- `GET /api/receipts/{id}/ocr` for full OCR result, including `rawOcrText` and `lineItems`

`ReceiptOcrResponse` now includes:

- `currency`
- `reconstructedLines`
- `normalizedLines`
- `parsedStoreName`
- `parsedTotalAmount`
- `parsedCurrency`
- `parsedPurchaseDate`
- `receiptCountryHint`
- `languageDetectionSource`
- `ocrProfileStrategy`
- `ocrProfileUsed`
- `parseWarnings`
- `weakParseQuality`
- `rawOcrText`
- `lineItems`
- `ocrStatus`
- `ocrErrorMessage`
- `ocrProcessedAt`

Ownership is enforced the same way as for the rest of the receipt API:

- only the owner can read OCR result
- access to another user's receipt returns `404`

## Failure Strategy

OCR errors do not break the original upload flow.

Chosen strategy:

- upload remains successful if OCR later fails
- the failure is recorded on the receipt
- the user can inspect `ocrStatus` and `ocrErrorMessage`

If queue publication itself fails after upload commit, the receipt is marked `FAILED` with a diagnostic message.

## Test Architecture

The backend suite is intentionally split into two layers:

- integration and API tests:
  - JUnit 5
  - `@SpringBootTest`
  - `TestRestTemplate`
  - shared Spring + Postgres + LocalStack + Paddle helper infrastructure
- focused unit and service tests:
  - routing
  - normalization
  - parser
  - validation
  - OCR keyword lexicon

The standard OCR integration path in tests now targets PaddleOCR. Legacy Tesseract coverage is secondary and must be opted into explicitly.

## Local Verification

1. start the stack:

```powershell
docker compose up -d --build
```

2. optional: start the legacy Tesseract helper for comparison only:

```powershell
docker compose --profile legacy-ocr up -d ocr-service
```

3. compare OCR output with and without preprocessing:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=false" `
  -F "file=@C:/temp/receipt.png;type=image/png"

curl -X POST "http://localhost:8083/ocr?preprocess=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

4. upload a PNG or PDF receipt with explicit currency and optional country hint:

```powershell
curl -X POST "http://localhost:8080/api/receipts/upload" `
  -H "Authorization: Bearer <JWT_TOKEN>" `
  -F "file=@C:/temp/receipt.png;type=image/png" `
  -F "currency=UAH" `
  -F "receiptCountryHint=UKRAINE"
```

5. poll OCR status:

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/receipts/<id>/ocr" `
  -Headers @{ Authorization = "Bearer <JWT_TOKEN>" }
```

5. verify that the response contains:

- `currency`
- `normalizedLines`
- `parsedTotalAmount`
- `parsedCurrency`
- `receiptCountryHint`
- `languageDetectionSource`
- `ocrProfileStrategy`
- `ocrProfileUsed`
- `parseWarnings`
- `weakParseQuality`
- `lineItems`
- `rawOcrText`

For direct helper verification, confirm that `POST /ocr` returns:

- `rawText`
- `lines[]`
  - `text`
  - `confidence`
  - `order`
  - optional `bbox`

For Java-side normalization verification, confirm that `GET /api/receipts/{id}/ocr` returns:

- `normalizedLines[]`
  - `originalText`
  - `normalizedText`
  - `tags`
  - `ignored`

6. run backend tests from the repo-local Maven Wrapper:

```powershell
.\mvnw.cmd test
```

7. inspect queues and logs if needed:

```powershell
docker exec home-budget-localstack awslocal sqs receive-message `
  --queue-url http://localhost:4566/000000000000/receipt-ocr-queue

docker compose logs -f app
docker compose logs -f ocr-service
docker compose logs -f paddleocr-service
```

## Current Limitations

- OCR quality depends on scan quality and file readability
- parsing is rule-based and intentionally best-effort
- some receipts still produce partial item extraction
- no automatic purchase creation
- no OCR confidence scoring
- no receipt-template-specific tuning beyond the current Paddle profile-routing strategy and explicit legacy fallback
- Java normalization is intentionally conservative and still leaves some noisy mixed-language product names untouched
- parser work still comes later; `normalizedLines[]` are the bridge into that step
