import unittest

import cv2
import numpy as np
from PIL import Image

from preprocessing import ReceiptImagePreprocessor


class ReceiptImagePreprocessorTests(unittest.TestCase):

    def test_preprocessing_reduces_skew_and_crops_background_for_receipt_like_photo(self):
        image = synthetic_receipt_photo()

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image)

        self.assertTrue(result.applied)
        self.assertIn("contrast", result.steps_applied)
        self.assertIn("threshold", result.steps_applied)
        self.assertIn("crop_receipt", result.steps_applied)
        self.assertLess(result.size_after.width * result.size_after.height, result.size_before.width * result.size_before.height)
        self.assertGreater(image_contrast_score(result.image), image_contrast_score(image))

    def test_preprocessing_can_be_disabled_for_baseline_comparison(self):
        image = synthetic_receipt_photo()

        preprocessor = ReceiptImagePreprocessor(enabled=True, target_long_edge=1600)
        result = preprocessor.preprocess(image, enabled_override=False)

        self.assertFalse(result.applied)
        self.assertEqual(result.steps_applied, tuple())
        self.assertEqual(result.size_after.width, result.size_before.width)
        self.assertEqual(result.size_after.height, result.size_before.height)


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


if __name__ == "__main__":
    unittest.main()
