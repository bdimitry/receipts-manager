import unittest
from pathlib import Path
import io

import cv2
import numpy as np
from PIL import Image
import fitz

from preprocessing import ReceiptImagePreprocessor
from corpus import diagnostic_corpus


class ReceiptImagePreprocessorTests(unittest.TestCase):

    FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "preprocessing-corpus"

    def test_preprocessing_reduces_skew_and_crops_background_for_receipt_like_photo(self):
        image = synthetic_receipt_photo()

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image)

        self.assertTrue(result.applied)
        self.assertIn("contrast", result.steps_applied)
        self.assertIn("crop_receipt", result.steps_applied)
        self.assertIn(result.strategy, {"soft", "balanced", "strong"})
        self.assertLess(result.size_after.width * result.size_after.height, result.size_before.width * result.size_before.height)
        self.assertGreaterEqual(image_contrast_score(result.image), image_contrast_score(image) * 0.9)
        self.assertGreater(grayscale_unique_levels(result.image), 32)

    def test_preprocessing_can_be_disabled_for_baseline_comparison(self):
        image = synthetic_receipt_photo()

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image, enabled_override=False)

        self.assertFalse(result.applied)
        self.assertEqual(result.steps_applied, tuple())
        self.assertEqual(result.size_after.width, result.size_before.width)
        self.assertEqual(result.size_after.height, result.size_before.height)
        self.assertEqual(result.strategy, "disabled")

    def test_clean_baseline_receipt_avoids_destructive_thresholding(self):
        image = load_fixture_image(self.FIXTURE_ROOT / "5.jpg")

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image)

        self.assertTrue(result.applied)
        self.assertEqual(result.strategy, "soft")
        self.assertNotIn("threshold_guidance", result.steps_applied)
        self.assertGreater(grayscale_unique_levels(result.image), 32)
        self.assertLess(result.size_after.width, 900)
        self.assertLess(abs(float(np.array(result.image.convert("L")).mean()) - float(np.array(image.convert("L")).mean())), 55.0)

    def test_pdf_like_page_uses_soft_path_and_preserves_grayscale_detail(self):
        image = load_fixture_image(self.FIXTURE_ROOT / "6.pdf")

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image)

        self.assertTrue(result.applied)
        self.assertEqual(result.strategy, "soft")
        self.assertNotIn("threshold_guidance", result.steps_applied)
        self.assertGreater(grayscale_unique_levels(result.image), 32)

    def test_hard_real_receipts_keep_stronger_processing_path(self):
        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)

        receipt_two = preprocessor.preprocess(load_fixture_image(self.FIXTURE_ROOT / "2.jpg"))
        receipt_four = preprocessor.preprocess(load_fixture_image(self.FIXTURE_ROOT / "4.jpg"))

        self.assertEqual(receipt_two.strategy, "strong")
        self.assertIn("threshold_guidance", receipt_two.steps_applied)
        self.assertIn("crop_receipt", receipt_two.steps_applied)
        self.assertEqual(receipt_four.strategy, "strong")
        self.assertIn("threshold_guidance", receipt_four.steps_applied)
        self.assertIn("upscale", receipt_four.steps_applied)

    def test_clean_pdf_rendered_corpus_sample_stays_soft_and_readable(self):
        image = next(case.image for case in diagnostic_corpus() if case.name == "pdf-rendered-payment-page")

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image)

        self.assertEqual(result.strategy, "soft")
        self.assertNotIn("threshold_guidance", result.steps_applied)
        self.assertGreater(grayscale_unique_levels(result.image), 64)


def synthetic_receipt_photo():
    canvas = np.full((1600, 1200, 3), 172, dtype=np.uint8)
    cv2.rectangle(canvas, (260, 120), (880, 1480), (245, 245, 245), thickness=-1)
    cv2.rectangle(canvas, (260, 120), (880, 1480), (70, 70, 70), thickness=3)

    lines = [
        "SILPO MARKET",
        "MILK 42.50",
        "BREAD 28.90",
        "APPLES 61.20",
        "TOTAL 132.60",
    ]
    y = 280
    for line in lines:
        cv2.putText(canvas, line, (330, y), cv2.FONT_HERSHEY_SIMPLEX, 1.25, (35, 35, 35), 3, cv2.LINE_AA)
        y += 170

    low_contrast = cv2.convertScaleAbs(canvas, alpha=0.82, beta=12)
    noise = np.random.default_rng(42).normal(0, 7, low_contrast.shape).astype(np.int16)
    noisy = np.clip(low_contrast.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    blurred = cv2.GaussianBlur(noisy, (5, 5), 0)

    height, width = blurred.shape[:2]
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), 8, 1.0)
    rotated = cv2.warpAffine(
        blurred,
        matrix,
        (width, height),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(150, 165, 155),
    )

    return Image.fromarray(cv2.cvtColor(rotated, cv2.COLOR_BGR2RGB))


def image_contrast_score(image: Image.Image) -> float:
    grayscale = cv2.cvtColor(np.array(image.convert("RGB")), cv2.COLOR_RGB2GRAY)
    return float(grayscale.std())


def grayscale_unique_levels(image: Image.Image) -> int:
    grayscale = cv2.cvtColor(np.array(image.convert("RGB")), cv2.COLOR_RGB2GRAY)
    return int(len(np.unique(grayscale)))


def load_fixture_image(path: Path) -> Image.Image:
    if path.suffix.lower() == ".pdf":
        document = fitz.open(path)
        try:
            page = document[0]
            pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
            return Image.open(io.BytesIO(pixmap.tobytes("png"))).convert("RGB")
        finally:
            document.close()

    return Image.open(path).convert("RGB")


if __name__ == "__main__":
    unittest.main()
