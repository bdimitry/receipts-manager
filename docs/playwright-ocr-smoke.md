# Playwright OCR Smoke

## Purpose

This document defines the required browser-level OCR regression pass for `Home Budget & Receipts Manager`.

The goal is simple:

- after backend and integration tests pass
- launch the real application
- upload a real receipt
- inspect the parsed result in the browser

This catches regressions that unit, parser, and DTO tests can miss.

## When This Check Is Required

Run this flow after any change that touches:

- OCR configuration
- preprocessing
- normalization
- parser logic
- receipt detail rendering
- receipt upload flow
- auth or receipt APIs that affect the live receipt journey

## Primary Tooling

Preferred:

- Playwright MCP against the live app

Fallbacks:

- local Playwright run from `frontend`
- manual browser walkthrough plus backend/log inspection

## Preconditions

1. Relevant backend tests are already green.
2. Local stack is running:

```powershell
docker compose up -d --build
```

3. Frontend and backend are reachable:

- frontend: `http://localhost:3000`
- backend health: `http://localhost:8080/api/health`

## Receipt Corpus

Use real files from:

- `C:\Users\dmitr\Pictures\чеки`

Mandatory regression candidates:

1. `C:\Users\dmitr\Pictures\чеки\1.jpg`
2. `C:\Users\dmitr\Pictures\чеки\2.jpg`
3. `C:\Users\dmitr\Pictures\чеки\3.jpg`
4. `C:\Users\dmitr\Pictures\чеки\4.jpg`
5. `C:\Users\dmitr\Pictures\чеки\5.jpg`
6. `C:\Users\dmitr\Pictures\чеки\6.pdf`

Use this fixed set as the default browser regression corpus unless a task explicitly narrows the scope for a smaller targeted check.

## Browser Workflow

1. Open the live frontend.
2. Log in with a working local user.
3. Navigate to the receipt upload flow.
4. Upload a real receipt file from the corpus.
5. Wait until OCR processing completes.
6. Open the created receipt detail page.
7. Inspect:
   - OCR status
   - parsed store
   - parsed amount
   - parsed purchase date
   - parsed line items
   - raw OCR text
8. Compare the visible result to the actual receipt image.

## What To Record

For every browser regression pass, record:

- receipt file used
- parsed store shown in UI
- parsed amount shown in UI
- parsed date shown in UI
- whether line items look plausible
- whether the result is:
  - improved
  - unchanged
  - regressed

## Pass/Fail Guidance

Treat the browser pass as failed when:

- parsed store is clearly wrong
- parsed amount is actually a date fragment or another obvious false positive
- service or payment lines are shown as purchased items
- the upload flow or detail page breaks
- OCR completed state is shown but parsed output is clearly worse than baseline

## Follow-Up Rule

If browser-visible output conflicts with unit or integration tests:

- trust the browser regression
- keep the issue open
- add or strengthen tests until the regression becomes reproducible automatically

## Related Docs

- [development-workflow.md](development-workflow.md)
- [runbook.md](runbook.md)
- [agent-roster.md](agent-roster.md)
- [skills-catalog.md](skills-catalog.md)
