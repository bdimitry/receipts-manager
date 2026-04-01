# Notification Flow

## Purpose

This document explains the multi-channel notification subsystem used after asynchronous report processing, including real email attachment delivery and Telegram bot-based connect flow.

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
- `telegramConnectedAt`
- `preferredNotificationChannel`

Temporary Telegram link state is stored in:

- `TelegramConnectToken`
- `TelegramPollingState`

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
- `TelegramConnectService`
- `TelegramPollingService`
- user-scoped notification settings API
- real SMTP delivery when SMTP env variables are configured
- MailHog fallback for local development
- Telegram deep-link connection via bot polling
- Telegram document delivery for ready reports
- local Telegram Bot API compatible mock for development and integration tests

## When Notifications Are Sent

Notifications are triggered by `ReportJobQueueConsumer`.

On success:

1. the job moves to `PROCESSING`
2. the report is generated and uploaded to S3
3. the job moves to `DONE`
4. `NotificationDispatcher` chooses the preferred channel
5. delivery is attempted
6. if email is selected and the report is ready, the generated file is attached to the email
7. if Telegram is selected and the report is ready, the generated file is sent with `sendDocument`
8. if needed, fallback delivery is attempted through the alternative channel

On failure:

1. processing throws an exception
2. the job moves to `FAILED`
3. `errorMessage` is stored
4. `NotificationDispatcher` sends a failure notification through the selected channel

## Message Content

Success delivery includes:

- that the report is ready
- report type
- report format
- reporting period
- report job id
- the generated file itself for email and Telegram when the job is `DONE`
- the API path to get the download contract as secondary guidance

Failure notification includes:

- that report generation failed
- report format
- reporting period
- report job id
- the API path to inspect current status

## Email Delivery

Implementation notes:

- recipient is always `User.email`
- `MimeMessageHelper` is used instead of `SimpleMailMessage`
- attachment content comes from the existing report file flow through `ReportJobService.buildReadyFileContent(...)`
- filename and content type are preserved for `CSV`, `PDF`, and `XLSX`

SMTP modes:

- real SMTP when `SPRING_MAIL_HOST` and related credentials are configured
- local MailHog fallback in the default Docker setup

## Telegram Connect Flow

The user never enters `telegramChatId` manually.

Flow:

1. frontend calls `POST /api/users/me/telegram/connect-session`
2. backend creates a one-time token bound to the current user
3. backend returns:
   - bot username
   - deep-link `https://t.me/<bot_username>?start=<token>`
   - expiration timestamp
4. user opens the bot and presses `Start`
5. `TelegramPollingService` reads `getUpdates`
6. backend extracts `/start <token>`
7. token is validated, marked as used, and the chat id is stored on the user
8. frontend polls `GET /api/users/me/telegram/connection` until the status becomes connected

Connect token guarantees:

- one-time
- expiration-based
- bound to a specific user

Polling behavior:

- polling is configurable
- processed offsets are stored in `TelegramPollingState`
- repeated updates are not reprocessed indefinitely

## Telegram Delivery

For ready reports:

- Telegram uses `sendDocument`
- the caption contains report type, format, and period
- `CSV`, `PDF`, and `XLSX` are all sent as files

For failure notifications:

- Telegram falls back to text because there is no ready file to deliver

## User-Facing API

Protected endpoints:

- `GET /api/users/me/notification-settings`
- `PUT /api/users/me/notification-settings`
- `POST /api/users/me/telegram/connect-session`
- `GET /api/users/me/telegram/connection`

Settings update example:

```json
{
  "preferredNotificationChannel": "TELEGRAM"
}
```

Validation rule:

- `TELEGRAM` requires an already connected Telegram account

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
- Telegram mock documents: [http://localhost:8082/documents](http://localhost:8082/documents)

### 2. Configure Notification Settings

```powershell
Invoke-RestMethod -Method Put `
  -Uri "http://localhost:8080/api/users/me/notification-settings" `
  -Headers @{ Authorization = "Bearer <JWT>"; ContentType = "application/json" } `
  -Body '{"preferredNotificationChannel":"EMAIL"}'
```

### 3. Connect Telegram

Create a connect session:

```powershell
$session = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/users/me/telegram/connect-session" `
  -Headers @{ Authorization = "Bearer <JWT>" }
```

The response contains the deep-link. In local mock mode you can simulate the bot start update with:

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8082/updates" `
  -ContentType "application/json" `
  -Body (@{
    update_id = 1001
    message = @{
      message_id = 1
      text = "/start $($session.deepLink.Split('=')[-1])"
      chat = @{ id = "555000111" }
    }
  } | ConvertTo-Json -Depth 5)
```

Then confirm connection status:

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://localhost:8080/api/users/me/telegram/connection" `
  -Headers @{ Authorization = "Bearer <JWT>" }
```

After the account is connected, select Telegram as the preferred channel:

```powershell
Invoke-RestMethod -Method Put `
  -Uri "http://localhost:8080/api/users/me/notification-settings" `
  -Headers @{ Authorization = "Bearer <JWT>"; ContentType = "application/json" } `
  -Body '{"preferredNotificationChannel":"TELEGRAM"}'
```

### 4. Create A Report Job

Use the existing report endpoints and wait until the job becomes `DONE` or `FAILED`.

### 5. Inspect The Result

For email:

- open MailHog or your real inbox
- verify recipient, subject, text body, and attached report file

For Telegram:

- open [http://localhost:8082/messages](http://localhost:8082/messages)
- confirm status/caption text
- open [http://localhost:8082/documents](http://localhost:8082/documents)
- confirm document filename, content type, and chat id

### 6. Verify The Report Still Works

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

- email attachment delivery to `User.email`
- Telegram connect token generation
- Telegram polling-based chat linking
- Telegram connected status API
- Telegram document delivery for ready reports
- preferred-channel selection
- owner-only targeting
- fallback from Telegram to email
- notification failure not breaking `ReportJob` lifecycle
- compatibility of notifications with the existing report flow
