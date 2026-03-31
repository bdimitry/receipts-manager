# Notification Flow

## Purpose

This document explains the multi-channel notification subsystem used after asynchronous report processing.

It is intended for:

- junior developers who need a clear flow map
- middle and senior engineers who need implementation boundaries
- tech leads who need extension points and trade-offs
- engineering managers who need scope clarity
- product owners who need a simple explanation of current user value

## Supported Channels

The system currently supports:

- `EMAIL`
- `TELEGRAM`

User-specific configuration is stored on `User`:

- `email`
- `telegramChatId`
- `preferredNotificationChannel`

## Channel Selection Strategy

Chosen strategy:

- send to the preferred configured channel first
- if that channel is unavailable or delivery fails, try the other configured channel
- if all channels fail, log the error and keep the report state unchanged

Why this strategy was chosen:

- it stays simple enough for the current monolith
- it improves delivery resilience without adding a notification microservice
- it does not let notification problems corrupt `ReportJob` lifecycle

## What Is Implemented

- `NotificationService` abstraction used by report processing
- `NotificationDispatcher` orchestration layer
- `EmailNotificationService`
- `TelegramNotificationService`
- user-scoped notification settings API
- local MailHog verification for email
- local Telegram Bot API compatible mock for Telegram

## When Notifications Are Sent

Notifications are triggered by `ReportJobQueueConsumer`.

On success:

1. the job moves to `PROCESSING`
2. the report is generated and uploaded to S3
3. the job moves to `DONE`
4. `NotificationDispatcher` chooses the preferred channel
5. delivery is attempted
6. if needed, fallback delivery is attempted through the alternative channel

On failure:

1. processing throws an exception
2. the job moves to `FAILED`
3. `errorMessage` is stored
4. `NotificationDispatcher` sends a failure notification through the selected channel

## Message Content

Success notification includes:

- that the report is ready
- report type
- report format
- reporting period
- report job id
- the API path to get the download contract

Failure notification includes:

- that report generation failed
- report format
- reporting period
- report job id
- the API path to inspect current status

## Settings API

Protected endpoints:

- `GET /api/users/me/notification-settings`
- `PUT /api/users/me/notification-settings`

Example request:

```json
{
  "preferredNotificationChannel": "TELEGRAM",
  "telegramChatId": "555000111"
}
```

Validation rule:

- `TELEGRAM` requires a non-empty `telegramChatId`

## Failure Behavior

Important rule:

- notification delivery must not break report processing

That means:

- if report generation succeeds, the job remains `DONE` even if all notifications fail
- if report generation fails, the job remains `FAILED` even if all notifications fail
- notification errors are visible in logs and covered by tests

## Logging

The system logs:

- which channel was selected
- why fallback is attempted
- send attempts
- send success
- send failure

This makes it possible to trace one `ReportJob` through generation and notification delivery.

## How To Verify Locally

### 1. Start The Environment

```powershell
docker compose up -d --build
```

Useful URLs:

- MailHog: [http://localhost:8025](http://localhost:8025)
- Telegram mock messages: [http://localhost:8082/messages](http://localhost:8082/messages)

### 2. Configure Notification Settings

```powershell
Invoke-RestMethod -Method Put `
  -Uri "http://localhost:8080/api/users/me/notification-settings" `
  -Headers @{ Authorization = "Bearer <JWT>"; ContentType = "application/json" } `
  -Body '{"preferredNotificationChannel":"TELEGRAM","telegramChatId":"555000111"}'
```

Switch back to email:

```powershell
Invoke-RestMethod -Method Put `
  -Uri "http://localhost:8080/api/users/me/notification-settings" `
  -Headers @{ Authorization = "Bearer <JWT>"; ContentType = "application/json" } `
  -Body '{"preferredNotificationChannel":"EMAIL"}'
```

### 3. Create A Report Job

Use the existing report endpoints and wait until the job becomes `DONE` or `FAILED`.

### 4. Inspect The Result

For email:

- open MailHog and verify the recipient, subject, and body

For Telegram:

- open [http://localhost:8082/messages](http://localhost:8082/messages)
- confirm `chat_id` and `text`

### 5. Verify The Report Still Works

If the job is `DONE`, call:

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/reports/<id>/download" `
  -Headers @{ Authorization = "Bearer <JWT_TOKEN>" }
```

## Testing Coverage

Relevant integration-test classes:

- `NotificationSettingsIntegrationTests`
- `NotificationChannelDispatchIntegrationTests`
- `TelegramFailureReportNotificationIntegrationTests`
- `TelegramNotificationFallbackIntegrationTests`
- `ReportJobGenerationIntegrationTests`
- `ReportJobFailureIntegrationTests`
- `ReportNotificationFailureIntegrationTests`

These tests verify:

- successful email delivery
- successful Telegram delivery
- preferred-channel selection
- owner-only targeting
- fallback from Telegram to email
- notification failure not breaking `ReportJob` lifecycle
- compatibility of notifications with the existing report flow
