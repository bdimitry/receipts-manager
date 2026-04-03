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
3. after transaction commit, `ReceiptOcrQueueProducer` publishes a message to `receipt-ocr-queue`
4. `ReceiptOcrQueueConsumer` polls the queue
5. the consumer marks the receipt `PROCESSING`
6. the consumer downloads the file from S3
7. the configured OCR client sends the file to the selected OCR helper container
8. the raw OCR text is stored
9. `ReceiptOcrParser` performs best-effort parsing for summary fields and line items
10. the receipt is marked `DONE` or `FAILED`

## OCR Backend Options

The project now supports two helper backends behind the same application OCR entry point.

### Tesseract Backend

This is still the stable default backend used by the main local stack.

It uses multilingual Tesseract recognition by default:

- `ukr`
- `rus`
- `eng`

The default runtime combination is `ukr+rus+eng`.

### PaddleOCR Backend

This is a new alternative baseline OCR backend designed for OCR-quality experiments.

It runs as a separate helper container and exposes:

- `POST /ocr`

Response contract:

- `rawText`
- `lines`
  - `text`
  - `confidence`

The Spring Boot application can switch to it through:

- `app.ocr.service.backend=PADDLE`
- `app.ocr.service.paddle-base-url=http://...`

The existing business parsing flow remains unchanged. The Paddle response is normalized into raw text first, then passed to `ReceiptOcrParser`.

The PaddleOCR helper now warms its baseline models during container startup. This moves the heaviest cold-start initialization away from the first live OCR request in a fresh local environment.

For Docker-based local runs, OCR endpoint values in `.env` must use container service names, not `localhost`. Inside the `app` container, `localhost` points back to the Spring Boot container itself.

### Paddle Preprocessing Layer

Before OCR, the Paddle helper now runs a dedicated preprocessing layer that is separate from the OCR engine itself.

Current baseline steps:

- upscale small images to an OCR-friendly long edge
- detect and crop the dominant receipt or document area when possible
- deskew mild rotation
- denoise grayscale text regions
- improve local contrast with CLAHE
- apply thresholding to separate text from noisy backgrounds

This pipeline is intentionally pragmatic. It is meant to improve typical user photos of receipts without requiring manual edits, while still staying simple enough to extend in later steps.

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
  - `imageSizeBefore`
  - `imageSizeAfter`
  - `stepsApplied`

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
- `parsedStoreName`
- `parsedTotalAmount`
- `parsedPurchaseDate`
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
- `parsedStoreName`
- `parsedTotalAmount`
- `parsedPurchaseDate`
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

## Local Verification

1. start the stack:

```powershell
docker compose up -d --build
```

2. optional: switch the main backend to PaddleOCR:

```powershell
$env:OCR_SERVICE_BACKEND="PADDLE"
$env:OCR_SERVICE_PADDLE_BASE_URL="http://paddleocr-service:8083"
docker compose up -d --build app paddleocr-service
```

3. compare OCR output with and without preprocessing:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=false" `
  -F "file=@C:/temp/receipt.png;type=image/png"

curl -X POST "http://localhost:8083/ocr?preprocess=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

3. upload a PNG or PDF receipt with explicit currency:

```powershell
curl -X POST "http://localhost:8080/api/receipts/upload" `
  -H "Authorization: Bearer <JWT_TOKEN>" `
  -F "file=@C:/temp/receipt.png;type=image/png" `
  -F "currency=UAH"
```

4. poll OCR status:

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/receipts/<id>/ocr" `
  -Headers @{ Authorization = "Bearer <JWT_TOKEN>" }
```

5. verify that the response contains:

- `currency`
- `parsedTotalAmount`
- `lineItems`
- `rawOcrText`

6. inspect queues and logs if needed:

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
- no receipt-template-specific tuning beyond the current `ukr+rus+eng` helper setup
- the PaddleOCR helper is intentionally baseline-only for now; preprocessing and deeper comparison work come later
