# Report Generation Flow

## Purpose

This document explains the current asynchronous report generation flow after the reporting subsystem was expanded to multiple types and formats.

It is written for:

- junior developers who need a readable end-to-end map
- middle and senior engineers who need code boundaries and contracts
- tech leads who need extension points and trade-off visibility
- engineering managers who need delivery scope and operational clarity
- product owners who need to understand current product value and limits

## What Is Implemented

- `ReportJob` creation through the API
- SQS message publishing
- asynchronous queue consumption
- generation of `CSV`, `PDF`, and `XLSX` files from `Purchase` data
- upload of the generated file to S3
- persistence of `s3Key` on `ReportJob`
- presigned download-link contract for finished reports
- email attachment delivery after `DONE` or status email after `FAILED`
- Telegram document delivery after `DONE` or text delivery after `FAILED`
- failure handling with persisted diagnostic message

## Queue Message Contract

Queue name:

- `report-generation-queue`

Message payload:

```json
{
  "reportJobId": 12,
  "userId": 5,
  "year": 2026,
  "month": 3,
  "reportType": "MONTHLY_SPENDING",
  "reportFormat": "CSV"
}
```

## End-To-End Flow

### 1. Job Creation

Primary endpoint:

- `POST /api/reports`

The endpoint:

- requires JWT authentication
- validates `year`, `month`, `reportType`, and `reportFormat`
- creates a `ReportJob` with status `NEW`
- saves the job in PostgreSQL
- publishes a message to SQS

Compatibility endpoint:

- `POST /api/reports/monthly`

Compatibility behavior:

- still creates `MONTHLY_SPENDING`
- still uses `CSV`

### 2. Queue Consumption

`ReportJobQueueConsumer`:

- polls SQS on a fixed delay
- reads the message payload
- marks the job as `PROCESSING`
- delegates business work to `ReportJobProcessor`

### 3. Data Preparation And Rendering

`ReportGenerationService`:

- loads only purchases that belong to the report owner
- filters them to the requested year and month
- ignores purchases from other users
- ignores purchases from other months
- asks `ReportDataService` to build the dataset for the requested `ReportType`
- selects a matching generator by `reportType + reportFormat`
- generates an S3 key like `reports/{userId}/{reportType}/{year}-{month}-{format}-{uuid}.{ext}`
- uploads the file to S3

Supported report types:

- `MONTHLY_SPENDING`
- `CATEGORY_SUMMARY`
- `STORE_SUMMARY`

Supported formats:

- `CSV`
- `PDF`
- `XLSX`

### 4. Completion

On success:

- `ReportJob.status = DONE`
- `ReportJob.s3Key` is stored
- `ReportJob.errorMessage = null`
- `updatedAt` is refreshed
- success delivery is attempted through the notification subsystem
- email sends the generated file as an attachment
- Telegram sends the generated file as a document when the user is connected

On failure:

- `ReportJob.status = FAILED`
- `ReportJob.s3Key = null`
- `ReportJob.errorMessage` stores a diagnostic message
- `updatedAt` is refreshed
- failure delivery is attempted through the notification subsystem
- channels fall back to text-based status messaging because no file is available

## Status Model

- `NEW`
- `PROCESSING`
- `DONE`
- `FAILED`

Supported transitions:

- `NEW -> PROCESSING -> DONE`
- `NEW -> PROCESSING -> FAILED`

## Download Flow

Endpoint:

- `GET /api/reports/{id}/download`

Behavior:

- owner only
- `404` for a missing or foreign job
- `409` if the job is still `NEW` or `PROCESSING`
- `409` if the job is `FAILED`
- `200` with a JSON contract when the report is ready

Response shape:

```json
{
  "reportJobId": 12,
  "reportType": "MONTHLY_SPENDING",
  "reportFormat": "CSV",
  "status": "DONE",
  "fileName": "monthly-spending-2026-03.csv",
  "contentType": "text/csv",
  "downloadUrl": "http://localhost:4566/home-budget-files/reports/5/monthly-spending/2026-03-csv-uuid.csv?...",
  "expiresAt": "2026-03-30T13:15:00Z"
}
```

## Empty-Period Behavior

If the user has no purchases for the selected month, the system still generates a valid report.

Current behavior:

- generation succeeds
- the rendered file contains section-specific empty-state text
- totals remain deterministic and explicit

## Current Limitations And Trade-Offs

- only three report types are implemented
- report layouts are intentionally simple and backend-oriented
- generated reports are uploaded to S3, but there is no retention management yet
- notification delivery errors are logged but not persisted as separate domain records
- no retry or dead-letter queue orchestration yet
- Telegram connection currently relies on polling instead of webhooks
- no download history is stored

## How To Verify Locally

### 1. Start The Environment

```powershell
docker compose up -d --build
```

### 2. Register, Log In, And Create Purchases

Use the auth and purchase endpoints first.

### 3. Create Reports In Different Formats

```powershell
$headers = @{
  Authorization = "Bearer <JWT_TOKEN>"
  ContentType = "application/json"
}

$body = @{
  year = 2026
  month = 3
  reportType = "CATEGORY_SUMMARY"
  reportFormat = "PDF"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/reports" `
  -Headers $headers `
  -Body $body
```

### 4. Poll Job Status

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/reports/<id>" `
  -Headers @{ Authorization = "Bearer <JWT_TOKEN>" }
```

### 5. Get The Download Contract

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/reports/<id>/download" `
  -Headers @{ Authorization = "Bearer <JWT_TOKEN>" }
```

### 6. Check The File In S3

```powershell
docker exec home-budget-localstack awslocal s3 ls s3://home-budget-files --recursive
```

## Testing Coverage

Relevant integration-test classes:

- `ReportJobQueuePublishingIntegrationTests`
- `ReportJobProcessingIntegrationTests`
- `ReportJobGenerationIntegrationTests`
- `ReportJobFailureIntegrationTests`
- `NotificationChannelDispatchIntegrationTests`
- `TelegramConnectIntegrationTests`

These tests verify:

- HTTP responses
- database state
- SQS publishing
- asynchronous status updates
- S3 object existence
- generated file content or structure
- download contract behavior
