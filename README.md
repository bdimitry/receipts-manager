# Home Budget & Receipts Manager

Full-stack demo project for personal budget tracking, receipt OCR, async report generation, S3-backed downloads, and multi-channel notifications.

## Quick Links

- [Project overview](docs/project-overview.md)
- [Project structure](docs/project-structure.md)
- [Architecture overview](docs/architecture-overview.md)
- [Frontend architecture](docs/frontend-architecture.md)
- [Report generation flow](docs/report-generation-flow.md)
- [Reporting system](docs/reporting-system.md)
- [Notification flow](docs/notification-flow.md)
- [OCR flow](docs/ocr-flow.md)
- [OCR diagnostics](docs/ocr-diagnostics.md)
- [Demo guide](docs/demo-guide.md)
- [Runbook](docs/runbook.md)

## What The Project Includes

Backend:

- Spring Boot 3 + Java 21 + Maven
- PostgreSQL + Flyway
- LocalStack S3 and SQS
- JWT auth
- purchases, receipts, OCR, reports, notifications
- currency-aware purchases and receipts (`USD`, `EUR`, `UAH`, `RUB`)
- receipt OCR line item extraction with persisted parsed positions
- async report and OCR processing
- CSV, PDF, and XLSX report generation
- two OCR backend options:
  - Tesseract helper as the current default path
  - PaddleOCR helper as an alternative baseline backend for OCR comparison work

Frontend:

- React + TypeScript + Vite
- React Router
- TanStack Query
- React Hook Form + Zod
- Recharts donut chart
- Playwright browser smoke coverage
- light and dark themes
- persisted language and theme settings
- dedicated topbar controls for theme and language, without a shared settings dropdown
- asset-based language switch with visible `RU`, `UK`, `EN` flags
- fixed sidebar with independent content scrolling
- calculator window for quick amount calculations in purchase creation
- multi-item purchase form with item-level totals and automatic amount assistance
- cozy dashboard UI based on [dashboard-variant-1-cozy-themes.svg](docs/concepts/dashboard-variant-1-cozy-themes.svg)
- currency-aware formatting and mixed-currency safe dashboard summaries

## Implemented User Flow

The product now supports a full demo-ready scenario:

1. register and login
2. open the dashboard and inspect spending by category
3. create purchases, optionally with multiple product items and calculator help
4. upload receipts and watch OCR progress
5. open OCR detail for a receipt
6. inspect parsed receipt line items and OCR summary fields
7. create report jobs in multiple types and formats
8. wait for async completion through SQS
9. download ready reports from S3-backed flow
10. receive completion notifications by email or Telegram

## Local Run

Start the full stack:

```powershell
docker compose up -d --build
```

Main local URLs:

- Frontend UI: [http://localhost:3000](http://localhost:3000)
- Backend API: [http://localhost:8080](http://localhost:8080)
- Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- MailHog UI: [http://localhost:8025](http://localhost:8025)
- Telegram mock: [http://localhost:8082/messages](http://localhost:8082/messages)
- Tesseract OCR helper: [http://localhost:8081/health](http://localhost:8081/health)
- PaddleOCR helper: [http://localhost:8083/health](http://localhost:8083/health)

Stop everything:

```powershell
docker compose down
```

## Supported Currencies

The project now supports these currency codes end-to-end:

- `USD`
- `EUR`
- `UAH`
- `RUB`

Currency is required:

- when creating a purchase
- when uploading a receipt

Important safety rule:

- dashboard and reporting no longer silently sum mixed currencies into one misleading total
- UI summaries and generated reports keep totals separated by currency

## Local Frontend Development

If you want to run the frontend outside Docker:

```powershell
cd frontend
npm ci
npm run dev
```

The Vite dev server proxies `/api` to `http://localhost:8080`.

The top bar keeps two always-visible controls:

- a compact theme toggle for light and dark mode
- a language dropdown with the current language code and local SVG flag for `RU`, `UK`, and `EN`

Both preferences persist after reload.

The application shell keeps the left navigation in place while the right content area scrolls independently.

## OCR Backends

The project now ships with two OCR helper services:

- `ocr-service`: the current Tesseract-based helper kept for comparison
- `paddleocr-service`: a PaddleOCR-based baseline helper for OCR quality exploration

The main Spring Boot app chooses the backend through environment configuration:

```env
OCR_SERVICE_BACKEND=PADDLE
OCR_SERVICE_TESSERACT_BASE_URL=http://ocr-service:8081
OCR_SERVICE_PADDLE_BASE_URL=http://paddleocr-service:8083
```

Supported values:

- `TESSERACT`
- `PADDLE`

If `OCR_SERVICE_BACKEND=PADDLE`, the backend sends receipt images to `POST /ocr` on the Paddle helper and maps the response back into the existing receipt OCR flow.

When you run the stack through `docker compose`, `.env` values are injected into containers. For container-to-container traffic, use Docker service names such as `paddleocr-service`, `ocr-service`, `telegram-mock`, `localstack`, and `mailhog`, not `localhost`.

The PaddleOCR helper currently returns:

- `rawText`
- `lines[]`
  - `text`
  - `confidence`
  - `order`
  - `bbox` as an optional four-point polygon when Paddle exposes coordinates

The Paddle helper now also applies automatic preprocessing before OCR. The baseline preprocessing layer currently:

- upscales smaller images
- attempts receipt-region crop when a dominant document contour is visible
- deskews mildly rotated photos
- denoises grayscale text regions
- boosts local contrast
- applies thresholding for OCR-friendly text separation

You can compare OCR output with and without preprocessing:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=false" `
  -F "file=@C:/temp/receipt.png;type=image/png"

curl -X POST "http://localhost:8083/ocr?preprocess=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

The Paddle helper response now also includes lightweight preprocessing metadata:

- `preprocessingApplied`
- `pages[]`
  - `imageSizeBefore`
  - `imageSizeAfter`
  - `stepsApplied`

The `lines[]` collection is now the main structured OCR output for downstream parsing work:

- each line keeps human reading order from top to bottom
- `order` is explicit and stable across pages
- `bbox` is included for local diagnostics and future line-aware parsing steps
- `rawText` remains available for compatibility and quick debugging

For diagnosis, the helper also exposes:

- `GET /diagnostics/config`
- `POST /ocr?debug=true`

The current diagnostic baseline on this branch uses an explicit OCR profile strategy:

- active profile: `en`
- compared profiles: `en`, `cyrillic`, `latin`
- detector: `en_PP-OCRv3_det_infer`
- recognizer: `en_PP-OCRv4_rec_infer`
- recognizer algorithm: `SVTR_LCNet`
- OCR version: `PP-OCRv4`

Current conclusion from the diagnostic step:

- the weakest quality cases mostly originate in the OCR engine itself on script-mismatched inputs
- `lines[]` mapping preserves engine text fairly closely and mainly affects order and grouping
- the controlled comparison corpus now selects `en` as the strongest default baseline for the standard OCR branch, while `cyrillic` remains available as a comparison profile

To run the Paddle helper service-side tests directly:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

To run the local OCR diagnostic comparison script:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic latin --preprocess true
```

This is intentionally a baseline OCR backend only. It is not yet a final receipt parsing pipeline.

The PaddleOCR helper now warms its baseline models during container startup, so the first live OCR request no longer needs to perform the heaviest model initialization work on the user-facing path.

## Testing

Backend:

```powershell
mvn test
```

Frontend:

```powershell
cd frontend
npm test
npx playwright install chromium
npm run test:smoke
npm run build
```

CI runs both layers:

- Maven backend tests
- frontend unit/integration tests
- frontend Playwright smoke check
- frontend production build

To smoke-test the PaddleOCR helper manually:

```powershell
docker compose up -d --build paddleocr-service
curl -X POST "http://localhost:8083/ocr" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

## Demo

Backend smoke script:

```powershell
./scripts/demo.ps1
```

For the full UI walkthrough use [demo-guide.md](docs/demo-guide.md).

For a realistic OCR check, upload a receipt image or PDF that contains:

- several item rows
- prices near the end of lines
- a visible total
- optionally Cyrillic text

Then open the receipt detail page and verify:

- OCR status changes to `DONE`
- parsed line items are shown
- parsed total and receipt currency are correct
- the OCR helper handles multilingual `ukr+rus+eng` text in the current local setup

If you upload a bank transfer slip or payment document instead of a retail receipt, the system now prefers a safe OCR outcome:

- raw OCR text is still stored
- store-like header text may still appear
- parsed line items stay empty
- misleading totals are not guessed from dates or account identifiers

## Current Limitations

- no mobile app
- no frontend design system package
- dashboard aggregates existing API data on the frontend instead of using a dedicated summary endpoint
- OCR parsing remains best-effort and not every receipt layout yields perfect line items
- notification history and retry orchestration are still out of scope
- report visuals are practical, not document-design heavy
