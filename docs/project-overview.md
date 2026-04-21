# Project Overview

## Purpose

`Home Budget & Receipts Manager` is a full-stack demo product for personal finance tracking.

It allows a user to:

- authenticate with JWT
- create purchases with explicit currency
- upload receipt files with explicit currency
- process receipts through asynchronous OCR
- inspect parsed OCR fields and receipt line items
- generate async reports in different types and formats
- download finished reports from S3-backed flow
- receive report completion notifications through the selected channel

## What Is Implemented

### Platform Foundation

- Docker Compose environment
- PostgreSQL
- LocalStack for S3 and SQS
- MailHog for email verification
- Telegram mock for local message delivery checks
- OCR helper containers:
  - PaddleOCR as the standard backend on this branch
  - Tesseract as an explicit legacy fallback and comparison helper
- Flyway migrations
- Swagger UI and OpenAPI
- GitHub Actions CI
- local project-specific Codex skills for OCR, CI, report delivery, frontend polish, and release verification
- a documented agent roster for OCR-first task delegation
- a documented MCP stack plan focused on the connectors that actually accelerate this repo

### Backend Domain

- `User` with JWT auth and notification preferences
- `Purchase` CRUD with user isolation and required `currency`
- `Receipt` upload to S3 with OCR lifecycle and required `currency`
- `ReceiptLineItem` for persisted OCR item rows
- `ReportJob` async processing lifecycle
- `CurrencyCode` values:
  - `USD`
  - `EUR`
  - `UAH`
  - `RUB`
- report types:
  - `MONTHLY_SPENDING`
  - `CATEGORY_SUMMARY`
  - `STORE_SUMMARY`
- report formats:
  - `CSV`
  - `PDF`
  - `XLSX`
- multi-channel notifications:
  - `EMAIL`
  - `TELEGRAM`

### OCR And Parsing Model

- raw OCR text is always stored when extraction succeeds
- geometry-aware reconstructed OCR lines, Java-normalized OCR lines, and parser-ready text are now persisted on the receipt as first-class downstream OCR artifacts
- parsed store name, total amount, and purchase date are stored as best-effort fields
- parsed currency is stored when explicit markers are found
- parsed line items are stored separately and linked to the receipt
- the main backend now defaults to Paddle-first OCR routing instead of a hidden Tesseract default
- the main backend can still switch between OCR helper backends through explicit configuration without changing the business parsing flow
- receipt upload now accepts an optional `receiptCountryHint` so OCR routing can use stronger language context when the user knows the document origin
- current supported manual hint set is intentionally practical:
  - `UKRAINE`
  - `POLAND`
  - `GERMANY`
- OCR routing now resolves in this order:
  - user-selected country hint
  - lightweight auto-detection from preview OCR text
  - safe fallback profile
- the PaddleOCR helper now includes a separate adaptive preprocessing layer for crop cleanup, deskew, denoise, contrast recovery, and safe-by-default soft vs strong enhancement before OCR
- the PaddleOCR helper now returns line-based OCR output with stable reading order, so the next parser step can work with explicit receipt rows instead of a single long text blob
- the PaddleOCR helper now stops at raw ordered line extraction, while the Spring backend owns structural reconstruction, conservative line normalization, line tagging, and parser-ready `normalizedLines[]`
- the live OCR processing path now first rebuilds a safer OCR row stream from geometry, then normalizes and parses that reconstructed stream instead of flattening raw helper text too early
- Spring now also owns a small explicit OCR keyword lexicon for safe post-OCR heuristics around receipt summary words, payment/service markers, barcode/account markers, and trusted merchant aliases
- the baseline parser now returns a first structured Java document result with merchant, date, total, parsed currency, and best-effort line items
- a dedicated Java validation layer now marks suspicious merchant, total, date, and line-item results instead of pretending every parse is equally trustworthy
- receipt detail and OCR retrieval now read those persisted OCR artifacts back through the product API instead of depending on a legacy partial recompute path
- the current diagnostic baseline uses explicit OCR profiles and shows that most obvious mixed-script degradation happens in the OCR engine on script-mismatched inputs, not in the line mapper
- the safe fallback profile for the standard OCR branch remains `en`, but the routing layer can now intentionally promote `cyrillic`, `polish`, or `german` when a user hint or auto-detection makes that route stronger
- OCR routing metadata is now persisted and returned for diagnostics:
  - `receiptCountryHint`
  - `languageDetectionSource`
  - `ocrProfileStrategy`
  - `ocrProfileUsed`
- OCR `DONE` means text extraction succeeded, even if structured parsing is partial
- OCR `FAILED` means extraction or processing itself failed

### Currency Model

- `Purchase.currency` is mandatory
- `Receipt.currency` is mandatory
- receipt upload validates currency against linked purchase when `purchaseId` is provided
- frontend formatting uses the stored currency instead of a hard-coded default
- dashboard and report generation no longer merge mixed currencies into one misleading total
- mixed-currency totals are shown separately by currency

### Frontend Product Layer

- cozy dashboard with spending donut chart as the main visual focal point
- dashboard legend with totals, percentages, translated category labels, and stable `Other` aggregation
- mixed-currency safe dashboard summaries instead of a single misleading total
- login and register flows
- protected app shell with fixed sidebar and independent content scrolling
- separate topbar theme toggle and language dropdown with persisted preferences
- language dropdown uses stable local flag assets for `RU`, `UK`, and `EN`
- purchases list, creation, filtering, calculator window, and multi-item purchase editor
- receipts list, upload, currency selection, and OCR detail view with parsed line items
- reports list, report creation, status tracking, and download action
- profile page with notification settings
- persisted theme and language preferences
- Playwright browser smoke coverage for the main UI shell

## Why This Stage Matters

The project now covers a realistic end-to-end product story:

- money data is currency-aware
- OCR results are more useful because they preserve multiple item rows
- dashboard and report outputs avoid incorrect mixed-currency aggregation
- frontend and backend expose the same financial model consistently

The repo now also has a documented development operating model:

- repeatable work can be routed through local project skills
- delegation can use stable role boundaries instead of ad hoc prompts
- MCP adoption is intentionally limited to high-value backend, OCR, CI, and frontend verification surfaces

## Demo-Ready Scenario

A complete UI-driven walkthrough now looks like this:

1. open the frontend
2. register and login
3. create purchases with selected currency
4. optionally add multiple product rows inside one purchase
5. use the calculator window to assist amount entry
6. upload a receipt with selected currency
7. open receipt OCR detail and inspect parsed line items
8. create a report in a selected type and format
9. observe async status progression
10. download the ready file
11. verify the notification in MailHog or Telegram mock

## Who This Documentation Is For

### Junior Developer

Understand:

- how a React frontend integrates with a Spring Boot API
- how JWT flows into a protected SPA
- how typed API calls, forms, and query state are organized
- how receipt OCR results are persisted beyond raw text, including normalized lines and parser-ready text
- how reconstructed OCR lines now bridge extraction and parser logic before normalization
- how parse warnings and weak-quality flags now travel through the product flow

### Middle Or Senior Developer

Focus on:

- modular frontend structure
- same-origin proxy approach in Docker
- UI integration over existing async backend contracts
- OCR parsing model with persisted line items
- persisted OCR downstream artifacts used for retrieval, not just processing-time parsing
- structural reconstruction as a separate backend layer between OCR extraction and parser logic
- baseline OCR parser model with structured document extraction over normalized lines
- post-parse validation warnings that keep noisy results honest in the product
- Paddle-first test infrastructure and repo-local wrapper execution
- currency-safe reporting and dashboard behavior
- test coverage across backend and frontend layers

### Tech Lead

Focus on:

- product completeness without unnecessary architectural churn
- minimal model evolution for currency safety and OCR usefulness
- maintainable full-stack handoff story

### Engineering Manager

Focus on:

- reproducible demo environment
- clear onboarding path
- CI coverage for both backend and frontend
- reduced demo risk around OCR and currency presentation

### Product Owner

Focus on:

- the core user journey now exists through a real product interface
- key value moments are visible: expenses, currencies, OCR, report readiness, notifications

## Current Limitations

- dashboard analytics are intentionally lightweight and derived from existing endpoints
- no dedicated admin or support UI
- no offline mode
- no push/mobile clients
- no advanced personalization beyond theme, language, and notification preferences
- OCR line item parsing remains best-effort for noisy real-world receipts
- the PaddleOCR helper is currently only a baseline OCR backend and not yet the final receipt understanding pipeline

## Where To Go Next

- [Frontend architecture](frontend-architecture.md)
- [Architecture overview](architecture-overview.md)
- [OCR flow](ocr-flow.md)
- [Skills catalog](skills-catalog.md)
- [Agent roster](agent-roster.md)
- [MCP stack](mcp-stack.md)
- [Development workflow](development-workflow.md)
- [Demo guide](demo-guide.md)
- [Runbook](runbook.md)
