# Architecture Overview

## Purpose

This document explains the full-stack architecture at a level useful for onboarding, technical review, and portfolio presentation.

## Core Domain Entities

- `User`
- `Purchase`
- `Receipt`
- `ReportJob`

## Main Runtime Components

- React SPA served by Nginx
- Spring Boot backend
- PostgreSQL
- LocalStack S3
- LocalStack SQS
- OCR helper service with Tesseract
- MailHog
- Telegram mock

## High-Level Request Path

```text
Browser -> Nginx frontend container -> /api proxy -> Spring Boot -> PostgreSQL / S3 / SQS / OCR / Notifications
```

## Frontend Layer

Responsibilities:

- route protection
- typed API calls
- query and mutation state
- theme and language preferences
- product screens for purchases, receipts, OCR, reports, and profile settings

## Backend Layer

Responsibilities:

- auth and ownership checks
- persistence
- async orchestration
- report generation
- OCR processing
- notification dispatch

## Main Data Flows

### Purchase Flow

1. user creates a purchase in the frontend
2. frontend calls `POST /api/purchases`
3. backend persists the purchase in PostgreSQL
4. dashboard can aggregate that purchase for category spending

### Receipt Flow

1. user uploads a file in the frontend
2. backend stores the file in S3 and metadata in PostgreSQL
3. backend publishes OCR work to SQS
4. OCR consumer processes the file asynchronously
5. frontend reflects OCR state through receipt list and detail view

### Report Flow

1. user creates a report job in the frontend
2. backend stores `ReportJob` with `NEW`
3. backend publishes a report message to SQS
4. consumer loads dataset, generates the selected format, and stores the file in S3
5. backend updates `ReportJob`
6. frontend shows status progression and enables download when ready

### Notification Flow

1. report finishes as `DONE` or `FAILED`
2. dispatcher selects the preferred notification channel
3. email or Telegram sender delivers the message
4. frontend exposes preference management on the profile page

## Design Choices

- same-origin frontend delivery in Docker through Nginx proxying to backend
- client-side dashboard aggregation from existing backend endpoints
- feature-oriented frontend structure instead of a heavy enterprise shell
- backend remains the source of truth for business logic and async workflows
- UI stays thin and typed, not business-heavy

## Current Extension Points

- dashboard summary endpoint if analytics grow
- richer report preview UI
- browser e2e tests
- notification history view
- OCR-assisted purchase prefill flow
