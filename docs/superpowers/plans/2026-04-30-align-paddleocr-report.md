# Align PaddleOCR Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the project OCR implementation into alignment with `C:\Users\dmitr\Downloads\deep-research-report.md` by migrating the helper from the current PaddleOCR 2.8/PP-OCRv4-style baseline toward explicit PaddleOCR 3.x/PP-OCRv5 profile, preprocessing, diagnostics, and benchmark behavior.

**Architecture:** Keep Spring as the product-facing OCR orchestrator and Java post-OCR owner. Replace the Python helper's hidden `PaddleOCR(lang=...)` defaults with explicit, report-aligned engine profiles that expose PP-OCRv5 model names, document preprocessing switches, DB/postprocess knobs, and runtime mode metadata. Preserve the existing legacy path as a controlled fallback until the PP-OCRv5 path is verified on the corpus.

**Tech Stack:** Spring Boot Java backend, Flask Python OCR helper, PaddleOCR/PaddleX, Docker Compose, unittest, JUnit/AssertJ, LocalStack/PostgreSQL integration tests.

---

## Current Gap Summary

The report describes a target PaddleOCR 3.x/PP-OCRv5/PaddleX-style setup. The project currently runs `paddleocr==2.8.1`, `paddlepaddle==2.6.2`, CPU-only `PaddleOCR(lang=..., use_gpu=False)`, and profiles named by language rather than by server/mobile model families. The project already matches the report in a few important areas: Paddle is the default backend, Tesseract is legacy fallback, preprocessing is traceable and can be disabled, profile routing is quality-aware, and Java owns post-OCR reconstruction/normalization/parser logic.

This plan fixes the mismatch in a safe order: first make the target profile model explicit and testable, then migrate dependency/runtime configuration, then add diagnostics and benchmark gates, then update documentation.

## File Structure

- Modify `docker/paddleocr-service/requirements.txt`: upgrade PaddleOCR dependencies or add a clearly named v3-compatible dependency set.
- Create `docker/paddleocr-service/engine_profiles.py`: define report-aligned engine profiles such as `high_accuracy`, `balanced`, `low_latency`, and map existing language routes onto them.
- Modify `docker/paddleocr-service/profiles.py`: keep language profiles but add engine profile, preprocessing policy, and model-family metadata.
- Modify `docker/paddleocr-service/ocr_engine.py`: build the OCR engine from explicit profile config instead of hidden PaddleOCR defaults.
- Modify `docker/paddleocr-service/preprocessing.py`: split receipt preprocessing from document OCR preprocessing controls and make unwarping/orientation policy explicit.
- Modify `docker/paddleocr-service/app.py`: expose profile metadata, DB knobs, preprocessing policy, runtime engine, and benchmark config through `/diagnostics/config`.
- Modify `docker/paddleocr-service/diagnostics.py`: output corpus metrics by profile and preprocessing mode in a stable machine-readable form.
- Modify `docker-compose.yml` and `.env.example`: add target profile/runtime environment variables.
- Modify `src/main/java/.../client/PaddleOcrClient.java`: preserve existing request contract while passing selected engine/profile options when needed.
- Modify `src/main/java/.../service/ReceiptOcrRoutingService.java`: keep candidate language routing, but route through report-aligned engine profiles.
- Modify existing Python and Java tests under `docker/paddleocr-service/tests` and `src/test/java/...`.
- Modify `docs/ocr-flow.md`, `docs/ocr-diagnostics.md`, and `README.md`: state the real PP-OCRv5 alignment and remaining limitations.

---

### Task 1: Add Explicit Report-Aligned Engine Profiles

**Files:**
- Create: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\engine_profiles.py`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\profiles.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_profiles.py`

- [ ] **Step 1: Write failing tests for engine profiles**

Append these tests to `docker/paddleocr-service/tests/test_profiles.py`:

```python
from engine_profiles import DEFAULT_ENGINE_PROFILE, resolve_engine_profile


class OcrEngineProfilesTests(unittest.TestCase):

    def test_default_engine_profile_is_balanced_receipt_baseline(self):
        profile = resolve_engine_profile(None)

        self.assertEqual(DEFAULT_ENGINE_PROFILE, "balanced")
        self.assertEqual(profile.name, "balanced")
        self.assertEqual(profile.det_model_name, "PP-OCRv5_mobile_det")
        self.assertEqual(profile.rec_model_name, "PP-OCRv5_server_rec")
        self.assertFalse(profile.use_doc_orientation)
        self.assertFalse(profile.use_doc_unwarping)
        self.assertEqual(profile.det_limit_type, "min")
        self.assertEqual(profile.det_limit_side_len, 960)

    def test_high_accuracy_profile_matches_report_server_pair(self):
        profile = resolve_engine_profile("high_accuracy")

        self.assertEqual(profile.det_model_name, "PP-OCRv5_server_det")
        self.assertEqual(profile.rec_model_name, "PP-OCRv5_server_rec")
        self.assertEqual(profile.det_limit_side_len, 1280)
        self.assertEqual(profile.det_db_score_mode, "slow")

    def test_low_latency_profile_matches_report_mobile_pair(self):
        profile = resolve_engine_profile("low_latency")

        self.assertEqual(profile.det_model_name, "PP-OCRv5_mobile_det")
        self.assertEqual(profile.rec_model_name, "PP-OCRv5_mobile_rec")
        self.assertEqual(profile.det_limit_side_len, 960)
        self.assertEqual(profile.det_db_score_mode, "fast")

    def test_unknown_engine_profile_is_rejected(self):
        with self.assertRaises(ValueError):
            resolve_engine_profile("expensive_magic")
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest docker/paddleocr-service/tests/test_profiles.py -v
```

Expected: FAIL because `engine_profiles` does not exist.

- [ ] **Step 3: Create `engine_profiles.py`**

Create `docker/paddleocr-service/engine_profiles.py`:

```python
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class OcrEngineProfile:
    name: str
    description: str
    det_model_name: str
    rec_model_name: str
    use_doc_orientation: bool
    use_doc_unwarping: bool
    use_textline_orientation: bool
    det_limit_type: str
    det_limit_side_len: int
    det_db_thresh: float
    det_db_box_thresh: float
    det_db_unclip_ratio: float
    det_db_score_mode: str

    def to_response(self) -> dict:
        return {
            "name": self.name,
            "description": self.description,
            "detModelName": self.det_model_name,
            "recModelName": self.rec_model_name,
            "useDocOrientation": self.use_doc_orientation,
            "useDocUnwarping": self.use_doc_unwarping,
            "useTextlineOrientation": self.use_textline_orientation,
            "detLimitType": self.det_limit_type,
            "detLimitSideLen": self.det_limit_side_len,
            "detDbThresh": self.det_db_thresh,
            "detDbBoxThresh": self.det_db_box_thresh,
            "detDbUnclipRatio": self.det_db_unclip_ratio,
            "detDbScoreMode": self.det_db_score_mode,
        }


ENGINE_PROFILES: dict[str, OcrEngineProfile] = {
    "high_accuracy": OcrEngineProfile(
        name="high_accuracy",
        description="Report-aligned PP-OCRv5 server detector and server recognizer for maximum quality.",
        det_model_name="PP-OCRv5_server_det",
        rec_model_name="PP-OCRv5_server_rec",
        use_doc_orientation=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        det_limit_type="min",
        det_limit_side_len=1280,
        det_db_thresh=0.3,
        det_db_box_thresh=0.6,
        det_db_unclip_ratio=1.5,
        det_db_score_mode="slow",
    ),
    "balanced": OcrEngineProfile(
        name="balanced",
        description="Report-aligned receipt baseline: mobile detector with server recognizer.",
        det_model_name="PP-OCRv5_mobile_det",
        rec_model_name="PP-OCRv5_server_rec",
        use_doc_orientation=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        det_limit_type="min",
        det_limit_side_len=960,
        det_db_thresh=0.3,
        det_db_box_thresh=0.6,
        det_db_unclip_ratio=1.5,
        det_db_score_mode="fast",
    ),
    "low_latency": OcrEngineProfile(
        name="low_latency",
        description="Report-aligned mobile detector and mobile recognizer for CPU/edge latency.",
        det_model_name="PP-OCRv5_mobile_det",
        rec_model_name="PP-OCRv5_mobile_rec",
        use_doc_orientation=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        det_limit_type="min",
        det_limit_side_len=960,
        det_db_thresh=0.3,
        det_db_box_thresh=0.6,
        det_db_unclip_ratio=1.5,
        det_db_score_mode="fast",
    ),
}

DEFAULT_ENGINE_PROFILE = "balanced"


def available_engine_profiles() -> list[OcrEngineProfile]:
    return list(ENGINE_PROFILES.values())


def resolve_engine_profile(profile_name: str | None) -> OcrEngineProfile:
    normalized = (profile_name or DEFAULT_ENGINE_PROFILE).strip().lower()
    if normalized not in ENGINE_PROFILES:
        supported = ", ".join(sorted(ENGINE_PROFILES))
        raise ValueError(f"Unsupported OCR engine profile '{profile_name}'. Supported profiles: {supported}")
    return ENGINE_PROFILES[normalized]
```

- [ ] **Step 4: Run profile tests**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -p "test_profiles.py" -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add docker/paddleocr-service/engine_profiles.py docker/paddleocr-service/tests/test_profiles.py
git commit -m "feat: define report-aligned OCR engine profiles"
```

---

### Task 2: Make OCR Engine Use Explicit PP-OCRv5 Profile Metadata

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\ocr_engine.py`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\app.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_ocr_engine.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_app.py`

- [ ] **Step 1: Write failing engine test**

Add this test to `docker/paddleocr-service/tests/test_ocr_engine.py`:

```python
    @patch("ocr_engine.PaddleOCR")
    def test_engine_profile_metadata_is_exposed_in_description(self, paddle_ocr_mock):
        paddle_ocr_mock.return_value.ocr.return_value = [[[]]]
        engine = PaddleOcrEngine(profile_name="en", engine_profile_name="high_accuracy")

        description = engine.describe()

        self.assertEqual(description["engineProfile"], "high_accuracy")
        self.assertEqual(description["detModelName"], "PP-OCRv5_server_det")
        self.assertEqual(description["recModelName"], "PP-OCRv5_server_rec")
        self.assertEqual(description["detLimitSideLen"], 1280)
        self.assertEqual(description["detDbScoreMode"], "slow")
```

- [ ] **Step 2: Update app diagnostics test expectation**

In `docker/paddleocr-service/tests/test_app.py`, update `FakeEngine.describe()` to return these fields:

```python
            "engineProfile": "balanced",
            "detModelName": "PP-OCRv5_mobile_det",
            "recModelName": "PP-OCRv5_server_rec",
            "detLimitType": "min",
            "detLimitSideLen": 960,
            "detDbThresh": 0.3,
            "detDbBoxThresh": 0.6,
            "detDbUnclipRatio": 1.5,
            "detDbScoreMode": "fast",
```

Then update `test_diagnostics_config_reports_active_engine_setup`:

```python
        self.assertEqual(body["defaultConfig"]["engineProfile"], "balanced")
        self.assertEqual(body["defaultConfig"]["detModelName"], "PP-OCRv5_mobile_det")
        self.assertEqual(body["defaultConfig"]["recModelName"], "PP-OCRv5_server_rec")
        self.assertEqual(body["defaultConfig"]["detLimitSideLen"], 960)
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -p "test_ocr_engine.py" -v
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -p "test_app.py" -v
```

Expected: FAIL because `PaddleOcrEngine.__init__` does not accept `engine_profile_name`.

- [ ] **Step 4: Implement explicit profile metadata in `ocr_engine.py`**

Update constructor and `describe()`:

```python
from engine_profiles import resolve_engine_profile


class PaddleOcrEngine:
    def __init__(self, profile_name: str, engine_profile_name: str | None = None) -> None:
        self.profile_name = profile_name
        self.engine_profile_name = engine_profile_name
        self._engines: dict[tuple[str, bool, str], PaddleOCR] = {}
        self._lock = threading.RLock()
```

Inside `describe()` add:

```python
        engine_profile = resolve_engine_profile(self.engine_profile_name)
```

Return these fields in the dict:

```python
            "engineProfile": engine_profile.name,
            "detModelName": engine_profile.det_model_name,
            "recModelName": engine_profile.rec_model_name,
            "detLimitType": engine_profile.det_limit_type,
            "detLimitSideLen": engine_profile.det_limit_side_len,
            "detDbThresh": engine_profile.det_db_thresh,
            "detDbBoxThresh": engine_profile.det_db_box_thresh,
            "detDbUnclipRatio": engine_profile.det_db_unclip_ratio,
            "detDbScoreMode": engine_profile.det_db_score_mode,
            "legacyDetModelDir": args.det_model_dir,
            "legacyRecModelDir": args.rec_model_dir,
            "legacyClsModelDir": args.cls_model_dir,
```

Update `_engine_key()`:

```python
    def _engine_key(self, profile: OcrProfile) -> tuple[str, bool, str]:
        engine_profile = resolve_engine_profile(self.engine_profile_name)
        return (profile.paddle_lang, profile.use_angle_cls, engine_profile.name)
```

- [ ] **Step 5: Wire env var in `app.py`**

Add:

```python
PADDLE_OCR_ENGINE_PROFILE = os.getenv("PADDLE_OCR_ENGINE_PROFILE", "balanced")
```

Update engine creation:

```python
application.config["OCR_ENGINE"] = ocr_engine or PaddleOcrEngine(
    profile_name=default_profile_name,
    engine_profile_name=PADDLE_OCR_ENGINE_PROFILE,
)
```

Add diagnostics fields:

```python
"activeEngineProfile": PADDLE_OCR_ENGINE_PROFILE,
```

- [ ] **Step 6: Run tests**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add docker/paddleocr-service/ocr_engine.py docker/paddleocr-service/app.py docker/paddleocr-service/tests/test_ocr_engine.py docker/paddleocr-service/tests/test_app.py
git commit -m "feat: expose explicit OCR engine profile metadata"
```

---

### Task 3: Upgrade Runtime Configuration Toward PaddleOCR 3.x

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\requirements.txt`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker-compose.yml`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\.env.example`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_app.py`

- [ ] **Step 1: Add config expectations to diagnostics test**

In `test_diagnostics_config_reports_active_engine_setup`, assert:

```python
        self.assertEqual(body["activeEngineProfile"], "balanced")
```

- [ ] **Step 2: Update dependencies**

Replace `docker/paddleocr-service/requirements.txt` with:

```text
Flask==3.1.0
numpy==1.26.4
opencv-python-headless==4.10.0.84
Pillow==10.4.0
PyMuPDF==1.25.5
paddlepaddle==3.0.0
paddleocr==3.0.0
paddlex==3.0.0
```

If Docker build fails because a specific package version is unavailable for the local Python/platform, pin to the nearest official PaddleOCR 3.x-compatible versions and record the exact resolved versions in `docs/ocr-diagnostics.md`.

- [ ] **Step 3: Add Docker env vars**

In `docker-compose.yml` under `paddleocr-service.environment`, add:

```yaml
      PADDLE_OCR_ENGINE_PROFILE: ${PADDLE_OCR_ENGINE_PROFILE:-balanced}
      PADDLE_OCR_RUNTIME: ${PADDLE_OCR_RUNTIME:-paddle_static}
      PADDLE_OCR_ENABLE_HPI: ${PADDLE_OCR_ENABLE_HPI:-false}
```

In `.env.example`, add:

```text
PADDLE_OCR_ENGINE_PROFILE=balanced
PADDLE_OCR_RUNTIME=paddle_static
PADDLE_OCR_ENABLE_HPI=false
```

- [ ] **Step 4: Run Docker build**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
```

Expected: image builds successfully. If dependency resolution fails, do not weaken the plan silently; pin the compatible 3.x versions and rerun.

- [ ] **Step 5: Run Python service tests**

Run:

```powershell
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

Expected: PASS or fail only on API changes from PaddleOCR 3.x. API-change failures move to Task 4.

- [ ] **Step 6: Commit**

```powershell
git add docker/paddleocr-service/requirements.txt docker-compose.yml .env.example
git commit -m "chore: configure PaddleOCR 3 runtime"
```

---

### Task 4: Adapt Engine Construction To PaddleOCR 3.x API

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\ocr_engine.py`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\engine_profiles.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_ocr_engine.py`

- [ ] **Step 1: Extend engine mock test to validate constructor kwargs**

In `test_engine_profile_metadata_is_exposed_in_description`, after `description = engine.describe()`, add:

```python
        kwargs = paddle_ocr_mock.call_args.kwargs
        self.assertEqual(kwargs["text_detection_model_name"], "PP-OCRv5_server_det")
        self.assertEqual(kwargs["text_recognition_model_name"], "PP-OCRv5_server_rec")
        self.assertEqual(kwargs["text_det_limit_type"], "min")
        self.assertEqual(kwargs["text_det_limit_side_len"], 1280)
        self.assertFalse(kwargs["use_doc_orientation_classify"])
        self.assertFalse(kwargs["use_doc_unwarping"])
```

- [ ] **Step 2: Run test to verify failure**

Run:

```powershell
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -p "test_ocr_engine.py" -v
```

Expected: FAIL because `_build_engine` still uses legacy kwargs.

- [ ] **Step 3: Update `_build_engine()`**

Replace `_build_engine()` in `ocr_engine.py` with:

```python
    def _build_engine(self, profile: OcrProfile):
        engine_profile = resolve_engine_profile(self.engine_profile_name)
        return PaddleOCR(
            lang=profile.paddle_lang,
            use_doc_orientation_classify=engine_profile.use_doc_orientation,
            use_doc_unwarping=engine_profile.use_doc_unwarping,
            use_textline_orientation=engine_profile.use_textline_orientation,
            text_detection_model_name=engine_profile.det_model_name,
            text_recognition_model_name=engine_profile.rec_model_name,
            text_det_limit_type=engine_profile.det_limit_type,
            text_det_limit_side_len=engine_profile.det_limit_side_len,
            text_det_thresh=engine_profile.det_db_thresh,
            text_det_box_thresh=engine_profile.det_db_box_thresh,
            text_det_unclip_ratio=engine_profile.det_db_unclip_ratio,
        )
```

Keep a temporary compatibility fallback only if PaddleOCR 3.x raises `TypeError` in local smoke tests:

```python
        try:
            return PaddleOCR(...)
        except TypeError:
            return PaddleOCR(use_angle_cls=profile.use_angle_cls, lang=profile.paddle_lang, use_gpu=False, show_log=False)
```

If fallback is added, expose `"runtimeCompatibilityMode": "legacy_kwargs_fallback"` in `describe()` so the mismatch is visible.

- [ ] **Step 4: Run helper tests**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add docker/paddleocr-service/ocr_engine.py docker/paddleocr-service/engine_profiles.py docker/paddleocr-service/tests/test_ocr_engine.py
git commit -m "feat: construct PaddleOCR from PP-OCRv5 engine profiles"
```

---

### Task 5: Align Preprocessing Policy With Report

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\preprocessing.py`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\app.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_preprocessing.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_app.py`

- [ ] **Step 1: Add preprocessing policy tests**

Add to `test_preprocessing.py`:

```python
    def test_clean_scan_policy_does_not_apply_perspective_warp(self):
        image = load_fixture_image(self.FIXTURE_ROOT / "5.jpg")

        preprocessor = ReceiptImagePreprocessor(
            enabled=True,
            target_long_edge=1600,
            allow_document_unwarping=False,
        )
        result = preprocessor.preprocess(image)

        self.assertNotIn("document_unwarp", result.steps_applied)
        self.assertNotIn("crop_receipt", result.steps_applied)
        self.assertEqual(result.strategy, "soft")

    def test_photo_policy_allows_receipt_crop_and_unwarp(self):
        image = synthetic_receipt_photo()

        preprocessor = ReceiptImagePreprocessor(
            enabled=True,
            target_long_edge=1600,
            allow_document_unwarping=True,
        )
        result = preprocessor.preprocess(image)

        self.assertTrue(result.applied)
        self.assertTrue("crop_receipt" in result.steps_applied or "document_unwarp" in result.steps_applied)
```

- [ ] **Step 2: Run preprocessing tests to verify failure**

Run:

```powershell
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -p "test_preprocessing.py" -v
```

Expected: FAIL because constructor does not accept `allow_document_unwarping`.

- [ ] **Step 3: Implement explicit unwarping policy**

Update constructor:

```python
class ReceiptImagePreprocessor:
    def __init__(
        self,
        enabled: bool = True,
        target_long_edge: int = 1600,
        allow_document_unwarping: bool = False,
    ) -> None:
        self.enabled = enabled
        self.target_long_edge = target_long_edge
        self.allow_document_unwarping = allow_document_unwarping
```

Update `_crop_receipt_region()` so four-point warp only runs when allowed:

```python
            if self.allow_document_unwarping and len(approximation) == 4:
                warped = _four_point_warp(image, approximation.reshape(4, 2).astype("float32"))
                if warped is not None:
                    return warped, True, None, "document_unwarp"
```

Change `_crop_receipt_region()` return shape to include step name:

```python
    def _crop_receipt_region(self, image: np.ndarray) -> tuple[np.ndarray, bool, tuple[int, int, int, int] | None, str | None]:
```

When rectangular crop is used:

```python
                    return cropped, True, (x, y, box_width, box_height), "crop_receipt"
```

When no crop:

```python
        return image, False, None, None
```

Update caller:

```python
        working, cropped, crop_box, crop_step = self._crop_receipt_region(working)
        if cropped and crop_step:
            steps.append(crop_step)
```

- [ ] **Step 4: Wire env var**

In `app.py`, add:

```python
PADDLE_OCR_ALLOW_DOCUMENT_UNWARPING = os.getenv("PADDLE_OCR_ALLOW_DOCUMENT_UNWARPING", "false").lower() == "true"
```

Pass it:

```python
ReceiptImagePreprocessor(
    enabled=PADDLE_OCR_PREPROCESSING_ENABLED,
    target_long_edge=PADDLE_OCR_TARGET_LONG_EDGE,
    allow_document_unwarping=PADDLE_OCR_ALLOW_DOCUMENT_UNWARPING,
)
```

Add to diagnostics:

```python
"allowDocumentUnwarping": PADDLE_OCR_ALLOW_DOCUMENT_UNWARPING,
```

- [ ] **Step 5: Run tests**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

Expected: PASS after adjusting existing tests that currently expect `crop_receipt` on clean fixture `5.jpg`.

- [ ] **Step 6: Commit**

```powershell
git add docker/paddleocr-service/preprocessing.py docker/paddleocr-service/app.py docker/paddleocr-service/tests/test_preprocessing.py docker/paddleocr-service/tests/test_app.py
git commit -m "feat: make document unwarping an explicit OCR policy"
```

---

### Task 6: Preserve Spring Routing While Passing Engine Profile Intent

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\src\main\java\com\blyndov\homebudgetreceiptsmanager\client\OcrRequestOptions.java`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\src\main\java\com\blyndov\homebudgetreceiptsmanager\client\PaddleOcrClient.java`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\src\main\java\com\blyndov\homebudgetreceiptsmanager\service\ReceiptOcrRoutingService.java`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\src\test\java\com\blyndov\homebudgetreceiptsmanager\PaddleOcrClientTests.java`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\src\test\java\com\blyndov\homebudgetreceiptsmanager\ReceiptOcrRoutingServiceTests.java`

- [ ] **Step 1: Update request option tests**

In `PaddleOcrClientTests.extractResultPassesProfileOverrideToPaddleService`, use:

```java
new OcrRequestOptions("cyrillic", "balanced")
```

and assert:

```java
assertThat(lastRequestPath.get()).isEqualTo("/ocr?profile=cyrillic&engineProfile=balanced");
```

- [ ] **Step 2: Update `OcrRequestOptions`**

Replace the record with:

```java
package com.blyndov.homebudgetreceiptsmanager.client;

public record OcrRequestOptions(String profile, String engineProfile) {

    public static OcrRequestOptions defaultOptions() {
        return new OcrRequestOptions(null, null);
    }

    public OcrRequestOptions(String profile) {
        this(profile, null);
    }
}
```

- [ ] **Step 3: Pass `engineProfile` query param**

In `PaddleOcrClient.extractPaddleResponse`, add:

```java
if (options != null && StringUtils.hasText(options.engineProfile())) {
    uriBuilder.queryParam("engineProfile", options.engineProfile());
}
```

- [ ] **Step 4: Keep routing default balanced**

In `ReceiptOcrRoutingService`, add:

```java
private static final String DEFAULT_ENGINE_PROFILE = "balanced";
```

Update `extractWithProfile`:

```java
new OcrRequestOptions(profile, DEFAULT_ENGINE_PROFILE)
```

- [ ] **Step 5: Run Java tests**

Run:

```powershell
.\mvnw.cmd -Dtest=PaddleOcrClientTests,ReceiptOcrRoutingServiceTests test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/blyndov/homebudgetreceiptsmanager/client/OcrRequestOptions.java src/main/java/com/blyndov/homebudgetreceiptsmanager/client/PaddleOcrClient.java src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrRoutingService.java src/test/java/com/blyndov/homebudgetreceiptsmanager/PaddleOcrClientTests.java src/test/java/com/blyndov/homebudgetreceiptsmanager/ReceiptOcrRoutingServiceTests.java
git commit -m "feat: pass OCR engine profile through Spring routing"
```

---

### Task 7: Add Machine-Readable Corpus Benchmark Output

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\diagnostics.py`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\comparison.py`
- Test: `C:\Users\dmitr\Documents\Receipts-Manager\docker\paddleocr-service\tests\test_comparison.py`

- [ ] **Step 1: Add test for summary shape**

Add to `test_comparison.py`:

```python
    def test_comparison_summary_exposes_report_aligned_metrics(self):
        summary = {
            "profile": "en",
            "engineProfile": "balanced",
            "preprocess": True,
            "cases": 5,
            "expectedLabelHits": 8,
            "expectedNumericHits": 9,
            "mixedScriptLines": 1,
            "brokenLongLines": 2,
            "averageConfidence": 0.91,
        }

        self.assertEqual(summary["engineProfile"], "balanced")
        self.assertIn("expectedLabelHits", summary)
        self.assertIn("averageConfidence", summary)
```

- [ ] **Step 2: Implement JSON summary output in `diagnostics.py`**

Add CLI argument:

```python
parser.add_argument("--json-summary", action="store_true", help="Print machine-readable benchmark summary JSON.")
```

Collect per-profile totals:

```python
summary_rows.append({
    "profile": profile_name,
    "engineProfile": os.getenv("PADDLE_OCR_ENGINE_PROFILE", "balanced"),
    "preprocess": args.preprocess == "true",
    "cases": case_count,
    "expectedLabelHits": label_hits,
    "expectedNumericHits": numeric_hits,
    "mixedScriptLines": mixed_script_lines,
    "brokenLongLines": broken_long_lines,
    "averageConfidence": round(confidence_sum / max(confidence_count, 1), 4),
})
```

At the end:

```python
if args.json_summary:
    print(json.dumps({"summaries": summary_rows}, ensure_ascii=False, indent=2))
```

- [ ] **Step 3: Run helper tests**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

Expected: PASS.

- [ ] **Step 4: Run diagnostics command on live helper**

Start helper:

```powershell
docker compose up -d --build paddleocr-service
```

Run:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic polish german latin --preprocess true --json-summary
```

Expected: JSON summary prints one row per profile.

- [ ] **Step 5: Commit**

```powershell
git add docker/paddleocr-service/diagnostics.py docker/paddleocr-service/comparison.py docker/paddleocr-service/tests/test_comparison.py
git commit -m "feat: emit OCR corpus benchmark summaries"
```

---

### Task 8: Update Documentation To Match Real Implementation

**Files:**
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docs\ocr-flow.md`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\docs\ocr-diagnostics.md`
- Modify: `C:\Users\dmitr\Documents\Receipts-Manager\README.md`

- [ ] **Step 1: Update `docs/ocr-diagnostics.md` active config section**

Replace the current PP-OCRv4 baseline bullets with:

```markdown
Current selected baseline profile for the standard OCR branch:

- language fallback profile: `en`
- engine profile: `balanced`
- OCR family: `PP-OCRv5`
- detector target: `PP-OCRv5_mobile_det`
- recognizer target: `PP-OCRv5_server_rec`
- detector scale policy: `limit_type=min`, `limit_side_len=960`
- DB postprocess defaults: `det_db_thresh=0.3`, `det_db_box_thresh=0.6`, `det_db_unclip_ratio=1.5`, `score_mode=fast`
- document orientation: disabled by default
- document unwarping: disabled by default unless explicitly enabled for photo-document evaluation
- textline orientation: disabled by default
```

- [ ] **Step 2: Update `docs/ocr-flow.md` preprocessing section**

Add:

```markdown
The current branch follows the research-report rule that document unwarping is not a universal default. Clean scans and PDF-like pages run through the soft preprocessing path without perspective unwarping. Phone-photo receipt evaluation can explicitly enable document unwarping through `PADDLE_OCR_ALLOW_DOCUMENT_UNWARPING=true` or request-level diagnostics.
```

- [ ] **Step 3: Update `README.md` OCR backend section**

Add under OCR Backends:

```markdown
The Paddle helper is now configured around report-aligned engine profiles:

- `high_accuracy`: `PP-OCRv5_server_det` + `PP-OCRv5_server_rec`
- `balanced`: `PP-OCRv5_mobile_det` + `PP-OCRv5_server_rec`
- `low_latency`: `PP-OCRv5_mobile_det` + `PP-OCRv5_mobile_rec`

Spring still performs language/profile routing (`en`, `cyrillic`, `polish`, `german`, `latin`) and quality scoring. The engine profile controls OCR model-family/runtime intent; the language profile controls script/language recognition context.
```

- [ ] **Step 4: Verify docs mention no stale PP-OCRv4 baseline**

Run:

```powershell
Select-String -Path docs\ocr-flow.md,docs\ocr-diagnostics.md,README.md -Pattern "PP-OCRv4|en_PP-OCRv4_rec_infer|paddleocr==2.8.1"
```

Expected: no stale baseline claims remain unless explicitly marked as legacy fallback/history.

- [ ] **Step 5: Commit**

```powershell
git add docs/ocr-flow.md docs/ocr-diagnostics.md README.md
git commit -m "docs: align OCR documentation with PP-OCRv5 plan"
```

---

### Task 9: Full Verification

**Files:**
- No code files changed in this task.

- [ ] **Step 1: Build helper image**

Run:

```powershell
docker build -t receipts-manager-paddleocr-service-test docker/paddleocr-service
```

Expected: build succeeds.

- [ ] **Step 2: Run helper tests**

Run:

```powershell
docker run --rm -w /app receipts-manager-paddleocr-service-test:latest python -m unittest discover -s tests -v
```

Expected: PASS.

- [ ] **Step 3: Run focused Java tests**

Run:

```powershell
.\mvnw.cmd -Dtest=PaddleOcrClientTests,ReceiptOcrRoutingServiceTests,ReceiptOcrProcessingIntegrationTests test
```

Expected: PASS.

- [ ] **Step 4: Start local OCR stack**

Run:

```powershell
docker compose up -d postgres localstack paddleocr-service app --build
```

Expected: `home-budget-paddleocr-service` and `home-budget-app` become healthy/running.

- [ ] **Step 5: Inspect diagnostics config**

Run:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8083/diagnostics/config"
```

Expected response contains:

```text
activeProfile: en
activeEngineProfile: balanced
defaultConfig.engineProfile: balanced
defaultConfig.detModelName: PP-OCRv5_mobile_det
defaultConfig.recModelName: PP-OCRv5_server_rec
preprocessingEnabled: true
allowDocumentUnwarping: false
```

- [ ] **Step 6: Run corpus comparison**

Run:

```powershell
docker exec home-budget-paddleocr-service python diagnostics.py --profiles en cyrillic polish german latin --preprocess true --json-summary
```

Expected: JSON summary prints stable metrics for all profiles.

- [ ] **Step 7: Commit verification notes if docs changed**

If verification revealed docs-only adjustments:

```powershell
git add docs/ocr-flow.md docs/ocr-diagnostics.md README.md
git commit -m "docs: record OCR verification notes"
```

---

## Self-Review

Spec coverage:

- PP-OCRv5 server/mobile profile recommendations: covered by Tasks 1, 2, 4, and 8.
- Optional orientation/unwarping instead of universal preprocessing: covered by Task 5.
- DB postprocess and detector scale controls: covered by Tasks 1, 2, and 4.
- PaddleX/HPI/benchmark direction: partially covered by Tasks 3 and 7. Full HPI/TensorRT enablement remains intentionally gated behind diagnostics because the local stack is CPU-first.
- Existing product routing and Java post-OCR ownership: preserved by Task 6.
- Documentation alignment: covered by Task 8.

Remaining explicit limitation:

- The report's HPI/TensorRT production path should not be turned on blindly in this local Docker stack. This plan exposes runtime intent and keeps `PADDLE_OCR_ENABLE_HPI=false` by default until a GPU-compatible environment is available and benchmarked.
