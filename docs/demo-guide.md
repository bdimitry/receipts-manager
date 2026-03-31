# Demo Guide

## Purpose

This guide gives a short, reliable full-stack script for showing the product through the UI, while keeping the backend internals easy to demonstrate when needed.

## Start The Environment

```powershell
docker compose up -d --build
```

Open:

- frontend: `http://localhost:3000`
- backend Swagger: `http://localhost:8080/swagger-ui.html`
- MailHog: `http://localhost:8025`
- Telegram mock: `http://localhost:8082/messages`

## Recommended Demo Order

### 1. Show The Product Shell

Open the frontend and show:

- cozy dashboard layout
- sidebar navigation
- `Настройки ▾` dropdown
- light and dark theme switch
- language switch

### 2. Register And Login

Show:

- register page
- login page
- protected navigation after login

Expected result:

- user enters the main workspace

### 3. Show The Dashboard

Show:

- donut chart by category
- total spent
- recent activity
- quick actions

Expected result:

- the dashboard feels like the main product entry point

### 4. Create A Purchase

Go to `Purchases`.

Show:

- filters
- creation form
- new purchase appearing in the list

### 5. Upload A Receipt

Go to `Receipts`.

Show:

- upload form
- optional link to a purchase
- uploaded receipt in the list

Then open receipt detail and show:

- OCR status
- parsed fields
- raw OCR text

### 6. Create A Report

Go to `Reports`.

Show:

- report type selection
- report format selection
- created job in the list
- status panel

Expected result:

- job progresses asynchronously

### 7. Show Completion

When the job reaches `DONE`, show:

- enabled download action in the UI
- download contract in the backend if you want a technical view
- MailHog or Telegram mock notification

### 8. Optional Technical Proof Points

If the audience is technical, also show:

- `GET /api/health`
- LocalStack S3 listing
- LocalStack SQS queue inspection
- backend logs

## What To Emphasize At The End

- one coherent user flow from login to report download
- async backend work is visible through the UI
- OCR and reporting are real backend integrations, not mock-only demos
- theme and language preferences are product-level touches, not afterthoughts
