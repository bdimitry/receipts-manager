# OCR Architecture Audit

Date: 2026-04-25  
Scope: read-only architecture audit of the current OCR pipeline  
Project: Home Budget & Receipts Manager

## 1. Executive Summary

The current OCR pipeline is already more mature than a simple `OCR -> regex parser` flow. It includes preprocessing, OCR profile routing, geometry-aware reconstruction, normalization, parsing, validation, persistence, and reporting boundaries.

At the same time, the pipeline still loses too much information too early and remains only partially aligned with the target design.

The biggest likely causes of low accuracy, especially on weak `44-54%` cases, are:

- OCR helper diagnostics and page-level metadata are not preserved end-to-end in the product flow.
- Geometry-aware reconstruction exists, but it still collapses the document into one main interpretation too early.
- The parser is only partially candidate-based and is still largely heuristic and regex-driven.
- Normalization is mostly line-level, not field-context-aware.
- Validation has warnings and a weak-quality flag, but no full confidence model or richer receipt processing states.
- There is no real human review / correction loop stored in the system.
- Metrics are not yet split cleanly by OCR, reconstruction, parsing, normalization, and validation layers.

Main conclusion:

- The system is not "too linear" in the simple sense.
- But it is still too linear in how it commits early to one interpretation and how it compresses OCR evidence before later stages can use it well.

## 2. Current Pipeline Map

### Upload and persistence

- [ReceiptController.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/controller/ReceiptController.java)
- [ReceiptService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptService.java)
- [Receipt.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/entity/Receipt.java)

Flow:

1. User uploads image or PDF.
2. Original file metadata is saved in PostgreSQL.
3. Original file content is stored in S3 via `s3Key`.
4. OCR is triggered asynchronously.

### Async OCR dispatch

- [ReceiptUploadedEvent.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptUploadedEvent.java)
- [ReceiptOcrDispatchListener.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrDispatchListener.java)
- [ReceiptOcrQueueProducer.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/client/ReceiptOcrQueueProducer.java)
- [ReceiptOcrQueueConsumer.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrQueueConsumer.java)

Flow:

1. Upload publishes a queue event after commit.
2. Consumer marks receipt `PROCESSING`.
3. Consumer downloads the original file from S3.
4. Consumer runs OCR flow and persists results.

### OCR routing

- [ReceiptOcrRoutingService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrRoutingService.java)
- [ReceiptOcrLanguageDetector.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrLanguageDetector.java)

Flow:

1. Run preview extraction on safe fallback profile.
2. Detect likely language/script.
3. Select a profile strategy such as `en+cyrillic`.
4. Score candidate profile outputs and choose the best one.

### Python OCR helper

- [app.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/app.py)
- [preprocessing.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/preprocessing.py)
- [ocr_engine.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/ocr_engine.py)
- [profiles.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/profiles.py)
- [response_mapping.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/response_mapping.py)
- [header_rescue.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/header_rescue.py)

Flow:

1. If the file is a PDF, convert it to images first.
2. Preprocess each page image.
3. Run PaddleOCR.
4. Map engine output to ordered OCR lines with bbox and confidence.
5. Optionally rescue weak top header block.
6. Return OCR payload and optional diagnostics.

### Java OCR ingestion

- [PaddleOcrClient.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/client/PaddleOcrClient.java)
- [PaddleOcrServiceResponse.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/client/PaddleOcrServiceResponse.java)
- [OcrExtractionResult.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/client/OcrExtractionResult.java)

Current ingestion preserves:

- `rawText`
- `lines[]`
  - `text`
  - `confidence`
  - `order`
  - `bbox`

It does not preserve the full helper diagnostics payload in product storage.

### Structural reconstruction

- [ReceiptOcrStructuralReconstructionService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java)
- [ReconstructedOcrDocument.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReconstructedOcrDocument.java)

Current responsibilities:

- row clustering by geometry
- reading order repair
- detached amount pairing
- summary pairing
- service/barcode isolation
- limited generic canonical cleanup

### Normalization

- [ReceiptOcrLineNormalizationService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrLineNormalizationService.java)
- [NormalizedOcrDocument.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/NormalizedOcrDocument.java)

Current responsibilities:

- punctuation and whitespace cleanup
- line tagging
- ignored line classification
- parser-ready line stream

### Parsing

- [ReceiptOcrParser.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrParser.java)
- [ParsedReceiptDocument.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ParsedReceiptDocument.java)

Current parsing targets:

- merchant
- purchase date
- total amount
- currency
- item rows

### Validation

- [ReceiptOcrValidationService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrValidationService.java)
- [ParsedReceiptValidationResult.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ParsedReceiptValidationResult.java)

Current validation outputs:

- warnings
- `weakParseQuality`

### Persistence and OCR API

- [ReceiptOcrService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrService.java)
- [ReceiptOcrResponse.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/dto/ReceiptOcrResponse.java)

Current persisted OCR artifacts:

- original file reference
- `rawOcrText`
- `reconstructedOcrLinesJson`
- `normalizedOcrLinesJson`
- `parserReadyText`
- parsed fields
- warnings
- weak parse flag
- OCR routing metadata

### Reporting/statistics

- [ReportDataService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReportDataService.java)

Important boundary:

- Reports are built from `Purchase`, not directly from OCR receipts.
- Weak OCR results do not automatically contaminate final statistics today.

## 3. Match Vs Target Design

| Target area | Match | Evidence |
|---|---|---|
| Input Image Pipeline | Partial | PDFs are converted to images in [app.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/app.py), original file is preserved via `s3Key` in [Receipt.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/entity/Receipt.java), but image diagnostics are not persisted as first-class receipt artifacts. |
| Preprocessing Strategy | Partial | Adaptive preprocessing exists in [preprocessing.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/preprocessing.py): crop, deskew, CLAHE, soft/strong strategy. No production variant comparison or long-receipt tiling. |
| OCR Helper Responsibility | Partial | Python helper is OCR-focused and not a business parser, which is good. But Java only keeps `rawText` and `lines[]`, not the full helper diagnostics payload. |
| OCR Engine Configuration | Partial | Profiles, language config, confidence, bbox, model/version description exist in helper code and diagnostics. But full engine config is not persisted per receipt in Java. |
| Geometry/Layout Reconstruction | Partial | Strong geometry-aware reconstruction exists in [ReceiptOcrStructuralReconstructionService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java). However, explicit document zones and low-confidence alternative interpretations are not preserved as structured downstream artifacts. |
| Parser Design | Partial | Merchant extraction is scored and filtered, but parsing overall remains heuristic-heavy in [ReceiptOcrParser.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrParser.java). No unified candidate pool with ranking by location, confidence, and arithmetic consistency. |
| Normalization | Partial | Normalization is conservative and safer than global hard replacements, but still mostly line-wide rather than field-context-aware. |
| Validation and Confidence | Partial | Validation checks business consistency in [ReceiptOcrValidationService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrValidationService.java), but there is no full confidence score and the receipt state model is minimal. |
| Human Review and Feedback Loop | Partial | Original image, raw OCR text, reconstructed lines, parsed result, and warnings are preserved. But raw OCR JSON, correction storage, and parsed-vs-corrected diff are missing. |
| Metrics and Evaluation | Partial | There are helper diagnostics, corpus scripts, and integration tests, but not one split metric system that attributes failure by OCR, reconstruction, parsing, normalization, and validation layers. |

## 4. Weak Points and Risks

### 1. Metadata loss between Python and Java

The helper can produce richer diagnostics than the Java product flow keeps. That weakens:

- debugging
- confidence scoring
- reviewability
- future learning from failures

Main evidence:

- [app.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/app.py)
- [PaddleOcrServiceResponse.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/client/PaddleOcrServiceResponse.java)

### 2. Parser is only partially candidate-based

The parser has scoring in some places, especially merchant selection, but it is still largely:

- sequential
- heuristic-driven
- regex-driven

This especially hurts weak cases where multiple plausible totals, merchants, or item interpretations exist.

Main evidence:

- [ReceiptOcrParser.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrParser.java)

### 3. Normalization is not field-context-aware enough

Normalization is safer than global destructive rewriting, which is good. But it still normalizes whole lines rather than field-specific values.

That means the system lacks dedicated logic for:

- money fields
- date fields
- currency fields
- merchant aliases
- numeric OCR confusions only inside numeric context

Main evidence:

- [ReceiptOcrLineNormalizationService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrLineNormalizationService.java)

### 4. Confidence model is too thin

The system has:

- warnings
- `weakParseQuality`

But it does not yet have:

- OCR confidence score
- parse confidence score
- business consistency confidence score
- richer processing statuses

Main evidence:

- [ReceiptOcrValidationService.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrValidationService.java)
- [ReceiptOcrStatus.java](C:/Users/dmitr/Documents/Receipts-Manager/src/main/java/com/blyndov/homebudgetreceiptsmanager/entity/ReceiptOcrStatus.java)

### 5. No true review / correction loop

The current system exposes OCR detail well, but it does not yet preserve:

- user-corrected result
- correction history
- parsed-vs-corrected diff

That makes continuous improvement harder.

### 6. Reconstruction still commits too early

Reconstruction is good and clearly useful, but it still produces one main parser-facing stream. It does not preserve structured low-confidence alternatives.

This becomes a problem when the same OCR fragment could plausibly belong to:

- item row
- summary row
- payment row

### 7. Metrics are not attribution-friendly

The project has:

- corpus scripts
- integration tests
- practical percentage estimates

But it does not yet have a systematic answer to:

- was this failure caused by OCR?
- reconstruction?
- normalization?
- parser?
- validation?

## 5. Recommended Staged Improvement Plan

Do not implement these here. These are proposed stages only.

### Stage 1. Preserve richer OCR artifacts end-to-end

Small effort, high impact:

- extend Java-side OCR contract
- persist helper-side preprocessing profile
- persist page-level metadata
- persist engine config snapshot
- persist selected diagnostics summary

### Stage 2. Introduce explicit document zones

Add structured document zones such as:

- header
- item list
- totals
- payment info
- footer

This gives later stages stronger context without immediately requiring ML zoning.

### Stage 3. Move parser toward a true candidate model

Start with:

- merchant candidates
- date candidates
- total candidates
- payment amount candidates

Then rank them using:

- labels
- location / zone
- OCR confidence
- arithmetic consistency
- line tags

### Stage 4. Make normalization field-context-aware

Split normalization by target field type:

- amount normalization
- date/time normalization
- currency normalization
- merchant normalization
- item-title cleanup

Avoid one general policy for all lines.

### Stage 5. Add structured confidence and richer statuses

Add:

- OCR confidence
- parse confidence
- business consistency confidence

And richer states such as:

- OCR_DONE
- PARSING
- PARSED_OK
- PARSED_LOW_CONFIDENCE
- NEEDS_REVIEW
- OCR_FAILED
- PARSING_FAILED

### Stage 6. Add human review and correction storage

Persist:

- raw OCR JSON snapshot
- correction payload
- corrected structured result
- diff between parsed and corrected data

### Stage 7. Build layer-split metrics

Measure:

- raw OCR quality
- reconstruction quality
- field extraction quality
- final trusted-data quality

Only after that should the team invest heavily in broader OCR strategy changes or major parser expansion.

## 6. Suggested Metrics

### Raw OCR metrics

- character error rate on synthetic fixtures
- word accuracy on controlled helper corpus
- label hit rate
- numeric hit rate
- line confidence distribution by zone

References:

- [diagnostics.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/diagnostics.py)
- [comparison.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/comparison.py)
- [corpus.py](C:/Users/dmitr/Documents/Receipts-Manager/docker/paddleocr-service/corpus.py)

### Reconstruction metrics

- line ordering accuracy
- merge/split accuracy
- detached amount recovery accuracy
- summary pairing accuracy
- service/barcode isolation accuracy

Suggested fixtures:

- [real-receipt-2-lines.txt](C:/Users/dmitr/Documents/Receipts-Manager/src/test/resources/fixtures/ocr/real-receipt-2-lines.txt)
- [real-receipt-4-lines.txt](C:/Users/dmitr/Documents/Receipts-Manager/src/test/resources/fixtures/ocr/real-receipt-4-lines.txt)
- [real-receipt-5-lines.txt](C:/Users/dmitr/Documents/Receipts-Manager/src/test/resources/fixtures/ocr/real-receipt-5-lines.txt)
- [real-receipt-6-lines.txt](C:/Users/dmitr/Documents/Receipts-Manager/src/test/resources/fixtures/ocr/real-receipt-6-lines.txt)

### Field extraction metrics

- merchant accuracy
- date accuracy
- total accuracy
- currency accuracy
- item extraction precision / recall
- warning precision for weak cases

### Final product metrics

- trusted-without-review rate
- needs-review rate
- corrected-vs-original parse delta
- report contamination rate once OCR is allowed to feed purchase data

## 7. Open Questions

These points cannot be determined fully from the codebase alone:

- How exactly the current headline percentages such as `88%` or `44-54%` are computed.
- Whether there is any external user correction workflow outside this repository.
- Whether OCR results are intended to remain advisory only, or later create or update purchases directly.
- What latency budget is acceptable for stronger multi-variant OCR strategies.
- How diverse the real incoming receipt corpus is beyond the current anchor set.
- What final product target matters most:
  - trusted autonomous extraction
  - strong review-assist extraction
  - or a hybrid mode

## Final Conclusion

The current pipeline is already more advanced than a simple OCR baseline and it contains real strengths:

- adaptive preprocessing
- profile routing
- geometry-aware reconstruction
- parser-ready normalized stream
- business validation
- persisted OCR artifacts for inspection

But most target areas are still only `Partial`.

The biggest architectural opportunities are:

1. preserve richer OCR evidence end-to-end
2. build a true candidate-based parser
3. make normalization field-context-aware
4. add a stronger confidence / review loop
5. split metrics by layer so quality loss is attributable

That is the most likely path to turn today's uneven OCR behavior into a more explainable and globally stronger system.
