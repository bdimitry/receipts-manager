# Frontend Architecture And User Flow

## Purpose

This document explains how the frontend is built, how it maps to backend capabilities, and how to verify the main user journey locally.

## Chosen Stack

- React
- TypeScript
- Vite
- React Router
- TanStack Query
- React Hook Form
- Zod
- Recharts
- Vitest + Testing Library + MSW
- Playwright browser smoke checks

## Design Direction

The frontend is based on the required reference:

- [dashboard-variant-1-cozy-themes.svg](/C:/Users/dmitr/Documents/Receipts-Manager/docs/concepts/dashboard-variant-1-cozy-themes.svg)

Main UI principles:

- Cozy Finance Hub visual style
- warm palette and product feel instead of a dry admin panel
- donut chart as the main dashboard focal point
- donut legend with category percentages, translated labels, and stable `Other` aggregation
- mixed-currency summaries are explicit instead of visually hiding conversion assumptions
- light and dark themes
- separate topbar controls for theme and language instead of a shared settings dropdown
- asset-based language flags instead of emoji rendering
- fixed sidebar with an independently scrollable content area

## Frontend Structure

- `frontend/src/app`
  - app root, router, protected layout
- `frontend/src/features/auth`
  - login and register screens
- `frontend/src/features/dashboard`
  - dashboard page, donut chart, and dashboard-specific transforms
- `frontend/src/features/purchases`
  - purchase list, filters, and creation form
- `frontend/src/features/receipts`
  - receipt list, upload, and OCR detail
- `frontend/src/features/reports`
  - report creation, status list, detail panel, and download action
- `frontend/src/features/profile`
  - profile and notification settings
- `frontend/src/shared`
  - API client, auth storage, providers, formatting, currency helpers, and shared UI

## How State And API Work

### Auth

- JWT is stored in local storage for this project
- protected routes require a token
- `401` clears the stale token and returns the user to login

### Data Fetching

- TanStack Query handles server state
- feature modules own typed API calls
- the UI does not call `fetch` directly from random components

### Forms

- React Hook Form manages form state
- Zod validates inputs before requests are sent

### Formatting

- all money rendering uses a shared currency-aware formatter
- the frontend does not assume a default currency when displaying persisted values

## Key Screens

### Login

- email and password
- backend login contract
- redirect into the protected app shell

### Register

- email, password, password confirmation
- backend register contract
- redirect to login

### Dashboard

- donut chart built from real purchase data for the current month
- category labels are translated on the frontend
- small low-impact categories are grouped into `Other` to keep the chart readable
- mixed-currency purchases are not merged into one misleading total
- total summaries are shown per currency when needed
- recent activity stream is built from receipts and reports
- quick actions stay visible on desktop and mobile layouts

### Purchases

- list
- creation form
- year, month, and category filters
- required currency select with supported values `USD`, `EUR`, `UAH`, `RUB`
- optional floating calculator window docked on the left side of the workspace on desktop
- calculator window can apply its result directly into the amount field
- optional multi-item purchase editor with `title`, `quantity`, `unit`, `unitPrice`, and `lineTotal`
- automatic amount assistance when item totals are available
- list amounts formatted with the stored currency
- purchase cards preview item titles when items were entered

### Receipts

- upload form with required currency select
- optional purchase link
- OCR status in list
- parsed total formatted with receipt currency
- detail page with parsed fields, line items, and raw OCR text

### Reports

- create report job by type and format
- status list
- report detail panel
- ready-report download action
- mixed-currency-safe output inherited from backend report generation

### Profile

- current user info
- notification preference update
- Telegram chat id field when needed

## Theme And Language

- both are global preferences
- both are persisted in local storage
- the theme switch is a compact always-visible toggle in the top bar
- the language switch is a separate dropdown that shows the current language code and a local flag asset
- smoke coverage verifies that the preferences survive a fresh page load

## App Shell Scrolling Model

- the sidebar stays fixed or sticky while the content area scrolls
- long forms and lists do not pull the left navigation column away
- narrow layouts relax into a mobile-friendly stacked flow without reintroducing a shared settings dropdown

## Dashboard Data Strategy

The dashboard intentionally uses existing backend endpoints:

- `GET /api/purchases`
- `GET /api/receipts`
- `GET /api/reports`

This keeps backend changes minimal and preserves compatibility with the already-tested API surface.

## How Receipt And Report Flows Look In The UI

### Receipt Flow

1. user uploads a file with selected currency
2. receipt appears in the list immediately
3. OCR status changes asynchronously
4. receipt detail reveals raw OCR text, parsed summary fields, and parsed line items

### Report Flow

1. user creates a report job
2. list shows the new job and current status
3. detail panel reflects status, error, and S3 key
4. ready jobs expose the download action
5. generated content remains currency-safe because totals are kept separate by currency

## Docker Delivery Model

- frontend is built with Vite
- Nginx serves the production bundle
- Nginx proxies `/api` to the Spring Boot container
- this avoids CORS complexity in the demo environment

## Local Verification

Start the full stack:

```powershell
docker compose up -d --build
```

Open:

- [http://localhost:3000](http://localhost:3000)

Verify:

1. register
2. login
3. open dashboard and confirm donut chart plus currency summary behavior
4. create a purchase and choose a currency
5. open the calculator window from the purchase form and apply a result to the amount field
6. add several purchase items and confirm the amount is assisted by item totals
7. upload a receipt and choose a currency
8. open receipt OCR detail and inspect line items
9. create a report in PDF or XLSX
10. wait for `DONE`
11. download the report
12. check MailHog or Telegram mock

Run frontend checks:

```powershell
cd frontend
npm ci
npm test
npx playwright install chromium
npm run test:smoke
npm run build
```

If local Node tooling is unavailable, the same checks can be run through Docker containers used in the project workspace.

## Current Limitations

- dashboard aggregation is client-side
- no code-splitting strategy beyond Vite defaults
- no advanced table UX like pagination or inline edit
- no frontend-side notification history view
- OCR presentation is helpful but intentionally not over-designed into a document viewer
