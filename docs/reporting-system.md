# Reporting System

## Purpose

This document describes the expanded reporting subsystem introduced in stage 10.

It explains:

- which report types are supported
- which file formats are supported
- how the generator is selected
- how files are built and stored
- how download works
- what limitations still exist

## What Is Implemented

- report types:
  - `MONTHLY_SPENDING`
  - `CATEGORY_SUMMARY`
  - `STORE_SUMMARY`
- report formats:
  - `CSV`
  - `PDF`
  - `XLSX`
- asynchronous generation through the existing SQS-based `ReportJob` flow
- storage of generated files in S3
- unified download contract for all formats
- compatibility endpoint for legacy monthly CSV flow

## API

Primary endpoint:

- `POST /api/reports`

Request shape:

```json
{
  "year": 2026,
  "month": 3,
  "reportType": "CATEGORY_SUMMARY",
  "reportFormat": "PDF"
}
```

Compatibility endpoint:

- `POST /api/reports/monthly`

Compatibility behavior:

- still creates `MONTHLY_SPENDING`
- still uses `CSV`
- remains available for older tests, docs, and demo flows

## Generator Selection

The subsystem is split into two simple layers:

1. `ReportDataService` builds the dataset for the chosen `ReportType`
2. `ReportGenerator` implementations render the dataset into the chosen `ReportFormat`

Current generator beans:

- `CsvReportGenerator`
- `PdfReportGenerator`
- `XlsxReportGenerator`

Selection happens centrally in `ReportGenerationService` using the pair:

- `reportType`
- `reportFormat`

This keeps the branching logic in one place instead of scattering `if/else` blocks through controllers and queue consumers.

## How Files Are Built

### `MONTHLY_SPENDING`

Contains:

- report title
- period
- owner email
- purchases table
- monthly total
- category summary

### `CATEGORY_SUMMARY`

Contains:

- report title
- period
- owner email
- aggregated category table
- total purchase count
- monthly total

### `STORE_SUMMARY`

Contains:

- report title
- period
- owner email
- aggregated store table
- total purchase count
- monthly total

All datasets use only:

- purchases of the `ReportJob` owner
- purchases from the requested `year/month`

## S3 Storage

Generated files are stored under keys like:

```text
reports/{userId}/{reportType-slug}/{year}-{month}-{format}-{uuid}.{ext}
```

Examples:

- `reports/5/monthly-spending/2026-03-csv-....csv`
- `reports/5/category-summary/2026-03-pdf-....pdf`
- `reports/5/store-summary/2026-03-xlsx-....xlsx`

## Download Flow

Endpoint:

- `GET /api/reports/{id}/download`

Behavior:

- only the owner can access it
- `404` for missing or foreign jobs
- `409` if the job is not ready
- `409` if the job is failed
- `200` for ready jobs with a presigned S3 URL

Response shape:

```json
{
  "reportJobId": 12,
  "reportType": "STORE_SUMMARY",
  "reportFormat": "XLSX",
  "status": "DONE",
  "fileName": "store-summary-2026-03.xlsx",
  "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "downloadUrl": "http://localhost:4566/...",
  "expiresAt": "2026-03-30T13:15:00Z"
}
```

## Local Verification

1. Start the environment:

```powershell
docker compose up -d --build
```

2. Authenticate and create purchases.

3. Create reports in different formats:

```powershell
$headers = @{
  Authorization = "Bearer <JWT_TOKEN>"
  ContentType = "application/json"
}

$csvBody = @{
  year = 2026
  month = 3
  reportType = "MONTHLY_SPENDING"
  reportFormat = "CSV"
} | ConvertTo-Json

$pdfBody = @{
  year = 2026
  month = 3
  reportType = "CATEGORY_SUMMARY"
  reportFormat = "PDF"
} | ConvertTo-Json

$xlsxBody = @{
  year = 2026
  month = 3
  reportType = "STORE_SUMMARY"
  reportFormat = "XLSX"
} | ConvertTo-Json
```

4. Poll `GET /api/reports/{id}` until `DONE`.

5. Read `GET /api/reports/{id}/download`.

6. Verify objects in S3:

```powershell
docker exec home-budget-localstack awslocal s3 ls s3://home-budget-files --recursive
```

## Current Limitations

- only three report types are implemented
- report layouts are intentionally simple and backend-oriented
- PDF and XLSX are generated for reproducibility, not for pixel-perfect presentation
- there is no report retention policy yet
- there is no retry or DLQ strategy for report failures
- notifications still use only email

## Why This Matters For The Product

This stage moves the backend from a single hardcoded export path to a reusable reporting subsystem.

That gives the product:

- multiple user-visible report variants
- multiple file formats for different usage contexts
- a stable async backbone for future report growth
- a clearer architecture for further extension without rewriting the queue flow
