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
10. receive report delivery by email or Telegram

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

## Delivery Env Variables

Real delivery can be switched on through `.env` values that are passed into Docker Compose:

- `SPRING_MAIL_HOST`
- `SPRING_MAIL_PORT`
- `SPRING_MAIL_USERNAME`
- `SPRING_MAIL_PASSWORD`
- `SPRING_MAIL_SMTP_AUTH`
- `SPRING_MAIL_SMTP_STARTTLS_ENABLE`
- `NOTIFICATION_EMAIL_FROM`
- `NOTIFICATION_TELEGRAM_BOT_TOKEN`
- `NOTIFICATION_TELEGRAM_BOT_USERNAME`
- `NOTIFICATION_TELEGRAM_POLLING_ENABLED`
- `NOTIFICATION_TELEGRAM_POLLING_INTERVAL_MS`
- `NOTIFICATION_TELEGRAM_MAX_UPDATES`
- `NOTIFICATION_TELEGRAM_CONNECT_TOKEN_TTL`

Behavior:

- if SMTP credentials are configured, email delivery uses the real SMTP server
- if SMTP credentials are not configured, the default local MailHog setup remains available
- Telegram connect and document delivery use the configured bot token and bot username

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
- backend failures upload Surefire reports as artifacts
- frontend smoke failures upload the Playwright HTML report

Before pushing to Git, use the full local verification script so CI does not fail on unverified regressions:

```powershell
./scripts/verify-all.ps1
```

The script:

- runs the full Maven backend suite
- runs frontend unit tests, browser smoke, and production build
- supports `MAVEN_CMD` when Maven is installed outside your PATH
- falls back to a Dockerized Playwright environment if `npm` is not available in your local PATH

## Async Report Test Stability

The report integration tests still use the real async SQS flow, but the test environment now avoids extra queue latency:

- report consumer polling remains asynchronous
- report integration tests run with `app.report-jobs.consumer.wait-time-seconds=0`
- Awaitility timeouts are aligned with the real async contract instead of assuming an unusually fast CI runner

## Demo

Backend smoke script:

```powershell
./scripts/demo.ps1
```

For the full UI walkthrough use [demo-guide.md](docs/demo-guide.md).

## Real Email And Telegram Delivery

Email:

- report completion sends a multipart email to the registration email address
- ready reports are attached directly to the message with the correct filename and content type
- the subject includes report type, format, and period

Telegram:

- the profile page exposes a `Connect Telegram` action
- the backend creates a one-time connect token and returns a deep-link
- the user opens the bot link and presses `Start`
- the backend polling worker reads `/start <token>` updates and binds the chat automatically
- ready reports are sent as Telegram documents instead of plain text when file delivery is available

Local verification:

1. set real SMTP values in `.env` if you want to test delivery to an external mailbox
2. run `docker compose up -d --build`
3. connect Telegram from the profile page or through the API
4. create a report and wait for `DONE`
5. verify:
   - MailHog or your real inbox for email attachment delivery
   - [http://localhost:8082/messages](http://localhost:8082/messages) and [http://localhost:8082/documents](http://localhost:8082/documents) for Telegram mock delivery

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

## Current Limitations

- no mobile app
- no frontend design system package
- dashboard aggregates existing API data on the frontend instead of using a dedicated summary endpoint
- OCR parsing remains best-effort and not every receipt layout yields perfect line items
- notification history and retry orchestration are still out of scope
- report visuals are practical, not document-design heavy
