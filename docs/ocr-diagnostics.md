# OCR Diagnostics

## Purpose

This document explains how to inspect the current PaddleOCR baseline on the `dmitr/experiment-paddleocr` branch without mixing OCR diagnosis with receipt parsing.

The goal of this stage is to answer three questions clearly:

- which PaddleOCR model configuration is actually active
- what raw OCR output the engine produces before business logic
- whether quality is lost inside the OCR engine itself or later during mapping

## Active PaddleOCR Configuration

In the current local helper, PaddleOCR is used with:

- OCR version: `PP-OCRv4`
- detector: `DB`
- recognizer: `SVTR_LCNet`
- detector model: `Multilingual_PP-OCRv3_det_infer`
- recognizer model: `cyrillic_PP-OCRv3_rec_infer`
- classifier model: `ch_ppocr_mobile_v2.0_cls_infer`
- default language: `cyrillic`
- default angle classification: `false`

This matters because the recognizer is the biggest source of script bias. A Cyrillic recognizer can preserve digits and many receipt rows, but it may still substitute Latin glyphs with visually similar Cyrillic ones on English-heavy samples.

## Diagnostic Endpoints

The Paddle helper now exposes two diagnostic-friendly entry points:

- `GET /diagnostics/config`
- `POST /ocr?debug=true`

`GET /diagnostics/config` returns the active default OCR configuration and preprocessing defaults.

`POST /ocr?debug=true` returns:

- normal OCR payload:
  - `rawText`
  - `lines[]`
- diagnostics:
  - `engineConfig`
  - `rawEngineLines[]`
  - `rawEngineText`

This lets you compare:

1. raw PaddleOCR output
2. mapped line output
3. final `rawText` assembly

## Where Quality Currently Degrades

The current diagnosis shows that the main quality loss is already present inside the OCR engine on some inputs, not in the line mapper.

What we observed:

- for clean Latin-heavy synthetic samples, `cyrillic` can still split lines more aggressively and may substitute visually similar glyphs
- for the same samples, `en` often produces cleaner full-line recognition
- mapped `lines[]` and assembled `rawText` mostly preserve the engine text faithfully
- the mapper mainly changes ordering and line grouping, not character identity

That means the current risk is not “post-processing corrupts good OCR,” but rather “the default recognizer choice is not ideal for every receipt script mix.”

## Reproducible Local Scenario

1. Start the stack:

```powershell
docker compose up -d --build
```

2. Inspect current config:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8083/diagnostics/config"
```

3. Inspect raw engine output versus mapped output:

```powershell
curl -X POST "http://localhost:8083/ocr?preprocess=true&debug=true" `
  -F "file=@C:/temp/receipt.png;type=image/png"
```

4. Run the bundled comparison script across multiple language configs:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --langs cyrillic en latin --preprocess true
```

The script generates two clean receipt-like samples:

- `synthetic-items`
- `synthetic-labels`

For each language it prints:

- `engineConfig`
- `rawEngineText`
- `rawEngineLines`
- `mappedLines`
- `mappedRawText`

## Practical Conclusion For The Next Step

At this point the project is ready for the next OCR stage only with an important caveat:

- line-based output is good enough to build normalization and parser experiments
- but the default recognizer choice must be treated as a quality variable, not as a fixed truth

Before deeper receipt parsing, the next investigation should focus on one of these paths:

- choosing a better default recognizer language for the target receipt corpus
- allowing a controlled multi-profile OCR comparison strategy
- adding script-aware routing for different receipt types
