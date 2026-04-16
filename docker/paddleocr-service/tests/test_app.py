import io
import os
import sys
import unittest
from pathlib import Path

from PIL import Image

os.environ["PADDLE_OCR_SKIP_WARMUP"] = "true"

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app import create_app
from preprocessing import ReceiptImagePreprocessor
from tests.test_preprocessing import synthetic_receipt_photo


class PaddleOcrAppTests(unittest.TestCase):

    def test_diagnostics_config_reports_active_engine_setup(self):
        engine = FakeEngine()
        app = create_app(
            ocr_engine=engine,
            image_preprocessor=ReceiptImagePreprocessor(enabled=True, target_long_edge=1600),
        )
        client = app.test_client()

        response = client.get("/diagnostics/config")

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(body["backend"], "PaddleOCR")
        self.assertEqual(body["activeProfile"], "en")
        self.assertEqual(body["defaultConfig"]["profile"], "en")
        self.assertEqual(body["defaultConfig"]["language"], "en")
        self.assertEqual(body["defaultConfig"]["recModelName"], "en_PP-OCRv4_rec_infer")
        self.assertEqual([profile["name"] for profile in body["availableProfiles"]], ["en", "cyrillic", "latin"])
        self.assertTrue(body["preprocessingEnabled"])

    def test_ocr_endpoint_returns_lines_and_preprocessing_metadata(self):
        engine = FakeEngine()
        app = create_app(
            ocr_engine=engine,
            image_preprocessor=ReceiptImagePreprocessor(enabled=True, target_long_edge=1600),
        )
        client = app.test_client()

        payload = image_to_png_bytes(synthetic_receipt_photo())
        response = client.post(
            "/ocr?preprocess=true",
            data={"file": (io.BytesIO(payload), "receipt.png")},
            content_type="multipart/form-data",
        )

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(body["rawText"], "STORE\nMILK 42.50\nTOTAL 132.60")
        self.assertEqual(body["profile"], "en")
        self.assertNotIn("normalizedLines", body)
        self.assertTrue(body["preprocessingApplied"])
        self.assertEqual(len(body["lines"]), 3)
        self.assertEqual(
            [line["text"] for line in body["lines"]],
            ["STORE", "MILK 42.50", "TOTAL 132.60"],
        )
        self.assertEqual([line["order"] for line in body["lines"]], [0, 1, 2])
        self.assertEqual(body["lines"][0]["bbox"], [[10.0, 20.0], [220.0, 20.0], [220.0, 60.0], [10.0, 60.0]])
        self.assertGreater(len(body["pages"][0]["stepsApplied"]), 0)
        self.assertGreater(engine.calls, 0)

    def test_ocr_debug_mode_exposes_raw_engine_output_and_lang_override(self):
        engine = FakeEngine()
        app = create_app(
            ocr_engine=engine,
            image_preprocessor=ReceiptImagePreprocessor(enabled=True, target_long_edge=1600),
        )
        client = app.test_client()

        payload = image_to_png_bytes(synthetic_receipt_photo())
        response = client.post(
            "/ocr?preprocess=true&debug=true&lang=en",
            data={"file": (io.BytesIO(payload), "receipt.png")},
            content_type="multipart/form-data",
        )

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(body["profile"], "en")
        self.assertEqual(body["diagnostics"]["engineConfig"]["language"], "en")
        self.assertEqual(body["diagnostics"]["rawEngineLines"][0]["text"], "MILK 42.50")
        self.assertEqual(body["diagnostics"]["rawEngineText"], "MILK 42.50\nTOTAL 132.60\nSTORE")
        self.assertEqual(body["diagnostics"]["mappedRawText"], "STORE\nMILK 42.50\nTOTAL 132.60")
        self.assertEqual(body["diagnostics"]["mappedLines"][1]["text"], "MILK 42.50")
        self.assertNotIn("normalizedLines", body["diagnostics"])
        self.assertEqual(engine.language_overrides, ["en"])

    def test_ocr_profile_override_uses_requested_profile(self):
        engine = FakeEngine()
        app = create_app(
            ocr_engine=engine,
            image_preprocessor=ReceiptImagePreprocessor(enabled=True, target_long_edge=1600),
        )
        client = app.test_client()

        payload = image_to_png_bytes(synthetic_receipt_photo())
        response = client.post(
            "/ocr?preprocess=true&debug=true&profile=cyrillic",
            data={"file": (io.BytesIO(payload), "receipt.png")},
            content_type="multipart/form-data",
        )

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertEqual(body["profile"], "cyrillic")
        self.assertEqual(body["diagnostics"]["engineConfig"]["profile"], "cyrillic")
        self.assertEqual(engine.profile_overrides, ["cyrillic"])

    def test_ocr_endpoint_allows_disabling_preprocessing_for_baseline_comparison(self):
        engine = FakeEngine()
        app = create_app(
            ocr_engine=engine,
            image_preprocessor=ReceiptImagePreprocessor(enabled=True, target_long_edge=1600),
        )
        client = app.test_client()

        payload = image_to_png_bytes(synthetic_receipt_photo())
        response = client.post(
            "/ocr?preprocess=false",
            data={"file": (io.BytesIO(payload), "receipt.png")},
            content_type="multipart/form-data",
        )

        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertFalse(body["preprocessingApplied"])
        self.assertEqual(body["pages"][0]["stepsApplied"], [])


class FakeEngine:
    def __init__(self):
        self.calls = 0
        self.profile_overrides = []
        self.language_overrides = []

    def warm_up(self):
        return None

    def describe(self, profile_override=None, language_override=None):
        profile = profile_override or "en"
        language = language_override or ("cyrillic" if profile == "cyrillic" else profile)
        rec_model = "en_PP-OCRv4_rec_infer" if language == "en" else f"{language}_PP-OCRv3_rec_infer"
        return {
            "profile": profile,
            "language": language,
            "useAngleCls": False,
            "detAlgorithm": "DB",
            "recAlgorithm": "SVTR_LCNet",
            "ocrVersion": "PP-OCRv4",
            "detModelDir": "/models/det",
            "recModelDir": f"/models/{rec_model}",
            "clsModelDir": "/models/cls",
            "detModelName": "Multilingual_PP-OCRv3_det_infer",
            "recModelName": rec_model,
            "clsModelName": "ch_ppocr_mobile_v2.0_cls_infer",
        }

    def extract_lines(self, image_array, profile_override=None, language_override=None):
        self.calls += 1
        if profile_override is not None:
            self.profile_overrides.append(profile_override)
        if language_override is not None:
            self.language_overrides.append(language_override)
        return [[
            [[[15, 160], [200, 160], [200, 200], [15, 200]], ("MILK 42.50", 0.9888)],
            [[[15, 250], [240, 250], [240, 288], [15, 288]], ("TOTAL 132.60", 0.9821)],
            [[[10, 20], [220, 20], [220, 60], [10, 60]], ("STORE", 0.9912)],
        ]]


def image_to_png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


if __name__ == "__main__":
    unittest.main()
