# Project Structure

## Goal

This document explains where the full-stack code lives and how responsibilities are split between frontend, backend, Docker assets, tests, and docs.

## Top-Level Layout

- `frontend`
- `src/main/java/com/blyndov/homebudgetreceiptsmanager`
- `src/main/resources`
- `src/test/java`
- `src/test/resources`
- `docker`
- `docs`
- `scripts`
- `.github/workflows`

## Frontend Module

### `frontend/src/app`

Responsibilities:

- app bootstrapping
- router
- protected layout
- top-level providers for auth, theme, i18n, and query state

### `frontend/src/features`

Responsibilities:

- screen-level modules and API integrations

Main feature areas:

- `auth`
- `dashboard`
- `purchases`
- `receipts`
- `reports`
- `profile`
- `user`

### `frontend/src/shared`

Responsibilities:

- typed API client
- auth token handling
- theme and i18n providers
- shared UI building blocks
- formatting helpers
- currency helpers

### `frontend/src/test`

Responsibilities:

- MSW server
- render helpers
- frontend integration-style component tests

### `frontend/tests/smoke`

Responsibilities:

- Playwright browser smoke coverage for the main user shell
- stable route-level checks for auth, dashboard, navigation, and settings persistence

### Frontend Delivery Files

- `frontend/package.json`
- `frontend/vite.config.ts`
- `frontend/playwright.config.ts`
- `frontend/Dockerfile`
- `frontend/nginx.conf`

These files define:

- Vite development
- Vitest execution
- Playwright smoke execution
- production build
- Docker delivery through Nginx with proxying to the backend API

## Backend Module

### `config`

- Spring configuration
- AWS client beans
- OCR, report, notification, and Telegram properties
- security configuration

### `controller`

- REST endpoints only
- validated input
- delegation to services

### `service`

- business orchestration
- ownership checks
- OCR processing
- receipt upload validation including currency compatibility
- report dataset building and generation
- notification dispatching

### `repository`

- JPA access
- scoped lookups
- ordered queries

### `entity`

- persistence entities and enums
- key domain entities now include:
  - `User`
  - `Purchase`
  - `Receipt`
  - `ReceiptLineItem`
  - `ReportJob`
  - `CurrencyCode`

### `dto`

- stable request and response contracts
- OCR responses include parsed line items
- purchase and receipt responses include currency

### `client`

- queue publishing
- OCR client integration
- notification provider clients
- both Tesseract and PaddleOCR adapters live here, while receipt parsing stays in the service layer

### `security`

- JWT parsing
- authenticated principal resolution

## Infrastructure Assets

### `docker`

Contains helper services and init scripts for:

- LocalStack
- Tesseract OCR helper
- PaddleOCR helper
- Telegram mock

Inside `docker/paddleocr-service`:

- `app.py`: HTTP API and OCR orchestration
- `profiles.py`: explicit OCR profile registry for baseline and comparison runs
- `corpus.py`: reproducible diagnostic corpus for OCR profile comparison
- `comparison.py`: scoring and recommendation logic for profile selection
- `diagnostics.py`: local comparison script for raw PaddleOCR output across OCR profiles
- `ocr_engine.py`: PaddleOCR engine lifecycle and retry wrapper
- `preprocessing.py`: dedicated receipt image preprocessing layer
- `response_mapping.py`: line-based mapping from raw PaddleOCR output into ordered OCR rows
- `tests/`: service-side preprocessing, profile, comparison, corpus, and API contract tests

Inside `src/main/java/com/blyndov/homebudgetreceiptsmanager/service`:

- `ReceiptOcrLineNormalizationService`: Java-side conservative line normalization, tagging, and parser-ready `normalizedLines[]` construction after raw OCR extraction
- `NormalizedOcrDocument`: internal Spring-side downstream OCR artifact that carries `normalizedLines[]`, `parserReadyLines[]`, and `parserReadyText`
- `ReceiptOcrParser`: line-oriented baseline parser that consumes `normalizedLines[]`
- `ParsedReceiptDocument`: structured parser result model for merchant/date/total/currency/items
- `ParsedReceiptLineItem`: structured parser line item model with raw fragment and source lines

### `docker-compose.yml`

Starts:

- `postgres`
- `localstack`
- `mailhog`
- `telegram-mock`
- `ocr-service`
- `paddleocr-service`
- `app`
- `frontend`

## Tests

### `src/test/java`

Backend coverage includes:

- auth
- purchases with currency validation
- receipts and OCR
- realistic OCR parser fixtures with noisy Cyrillic text
- reports and downloads
- mixed-currency reporting safety
- notifications
- demo smoke flow

### `src/test/resources/fixtures/ocr`

Holds OCR fixtures that simulate noisy real receipts and help keep the parser grounded in realistic text structure.

### `frontend/src/**/*.test.tsx`

Frontend coverage includes:

- auth screens and route protection
- dashboard data rendering and mixed-currency behavior
- donut chart totals, legend, percentages, and translated labels
- purchases flow with currency selection
- receipts flow, upload currency selection, and OCR detail line items
- reports flow and download action
- settings dropdown
- profile notification preferences
- currency formatting helpers

### `frontend/tests/smoke/**/*.spec.ts`

Browser coverage includes:

- register and login
- dashboard render
- opening purchases, receipts, reports, and profile
- settings dropdown persistence after reload

## Docs And Run Assets

### `docs`

Holds:

- architecture overviews
- OCR and reporting flow documents
- frontend architecture guide
- demo guide
- runbook
- skills catalog
- agent roster
- MCP stack plan
- development workflow conventions

Project-specific Codex skills themselves live outside the repo in the local Codex home:

- `C:\Users\dmitr\.codex\skills`

The repo documentation points to those skills and explains when to use them.

### `scripts`

Holds:

- backend and demo helper scripts

## Main Full-Stack Flow

1. browser opens the React frontend
2. Nginx serves the SPA and proxies `/api` to Spring Boot
3. frontend calls typed API functions
4. backend controllers delegate to services
5. services persist to PostgreSQL and interact with S3, SQS, OCR, and notifications
6. the OCR adapter selects Tesseract or PaddleOCR by configuration and now accepts both raw text and ordered OCR line rows for the evolving OCR pipeline
7. OCR results are stored as receipt summary fields plus `ReceiptLineItem` rows
8. dashboard and reports format financial data with explicit currency awareness
9. frontend reflects backend state through TanStack Query and form mutations

For a deeper view, continue with [architecture-overview.md](architecture-overview.md), [frontend-architecture.md](frontend-architecture.md), and [ocr-flow.md](ocr-flow.md).
