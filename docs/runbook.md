# Runbook

## Purpose

This runbook helps a developer quickly understand where to look when the local full-stack flow is not behaving as expected.

## Start And Stop

Start:

```powershell
docker compose up -d --build
```

Stop:

```powershell
docker compose down
```

## Main URLs

- frontend: `http://localhost:3000`
- backend health: `http://localhost:8080/api/health`
- Swagger: `http://localhost:8080/swagger-ui.html`
- MailHog: `http://localhost:8025`
- Telegram mock: `http://localhost:8082/messages`

## Watch Logs

Frontend:

```powershell
docker compose logs -f frontend
```

Backend:

```powershell
docker compose logs -f app
```

Run the full local verification before pushing:

```powershell
./scripts/verify-all.ps1
```

What it does:

- runs the full backend Maven suite
- runs frontend unit tests
- runs Playwright browser smoke
- runs the frontend production build
- supports `MAVEN_CMD` if Maven is installed outside PATH
- uses a Dockerized Playwright fallback if local `npm` is unavailable

Infrastructure:

```powershell
docker compose logs -f localstack
docker compose logs -f ocr-service
docker compose logs -f mailhog
docker compose logs -f telegram-mock
```

## If The Frontend Does Not Open

Check:

- `docker compose ps`
- frontend logs
- `docker compose logs -f frontend`

Typical causes:

- frontend image failed to build
- backend is not reachable from the Nginx proxy

## If Login Loops Back To The Same Screen

Check:

- backend `401` responses in browser devtools or backend logs
- JWT token presence in local storage
- `GET /api/users/me` behavior

Typical causes:

- backend is down
- token is stale
- auth endpoint returned an unexpected error

## If Dashboard Looks Empty

Check:

- `GET /api/purchases`
- `GET /api/receipts`
- `GET /api/reports`

Remember:

- the dashboard currently aggregates from existing endpoints
- if there are no purchases for the current month, the donut chart intentionally shows an empty state

## Inspect Queues

Report queue:

```powershell
docker exec home-budget-localstack awslocal sqs receive-message `
  --queue-url http://localhost:4566/000000000000/report-generation-queue
```

OCR queue:

```powershell
docker exec home-budget-localstack awslocal sqs receive-message `
  --queue-url http://localhost:4566/000000000000/receipt-ocr-queue
```

## Inspect S3

```powershell
docker exec home-budget-localstack awslocal s3 ls s3://home-budget-files --recursive
```

Read a generated report:

```powershell
docker exec home-budget-localstack awslocal s3 cp `
  s3://home-budget-files/reports/<userId>/<file> -
```

## If OCR Looks Stuck

Check:

- receipt status in `GET /api/receipts/{id}`
- OCR detail in `GET /api/receipts/{id}/ocr`
- backend logs around receipt OCR consumer
- OCR helper logs

## If Report Job Looks Stuck

Check:

- `GET /api/reports/{id}`
- report queue contents
- backend logs around report consumer and generation
- if GitHub Actions failed, download `backend-surefire-reports` from the workflow artifacts

CI stability note:

- report integration tests keep the real queue and scheduler
- the test environment no longer adds a 1-second SQS long-poll delay before every receive cycle
- GitHub Actions now uploads diagnostic artifacts for easier failure analysis

## If Download Is Disabled In The UI

Expected reasons:

- report is still `NEW`
- report is still `PROCESSING`
- report is `FAILED`

Check:

- report detail panel in the frontend
- `GET /api/reports/{id}`

## If Notification Is Missing

Check:

- MailHog UI
- Telegram mock messages
- backend logs for notification dispatch and fallback

Remember:

- notification failure should not break a finished report job
