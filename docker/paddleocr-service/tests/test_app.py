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
        self.assertEqual(body["rawText"], "STORE\nTOTAL 132.60")
        self.assertTrue(body["preprocessingApplied"])
        self.assertEqual(len(body["lines"]), 2)
        self.assertGreater(len(body["pages"][0]["stepsApplied"]), 0)
        self.assertGreater(engine.calls, 0)

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

    def warm_up(self):
        return None

    def extract_lines(self, image_array):
        self.calls += 1
        return [[
            [None, ("STORE", 0.9912)],
            [None, ("TOTAL 132.60", 0.9821)],
        ]]


def image_to_png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


if __name__ == "__main__":
    unittest.main()
