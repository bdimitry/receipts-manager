import unittest
from pathlib import Path

from normalization import ReceiptOcrLineNormalizer
from response_mapping import MappedOcrLine


FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"


class ReceiptOcrLineNormalizerTests(unittest.TestCase):
    def setUp(self):
        self.normalizer = ReceiptOcrLineNormalizer()

    def test_normalizer_cleans_basic_punctuation_noise_without_guessing_words(self):
        normalized = self.normalizer.normalize_lines(
            [
                mapped_line("CASH.RECEIPT", 0),
                mapped_line("Dolor.Sit", 1),
                mapped_line("0.40,", 2),
                mapped_line("2. шт х 104.00.:", 3),
            ]
        )

        self.assertEqual(normalized[0].normalized_text, "CASH RECEIPT")
        self.assertEqual(normalized[1].normalized_text, "Dolor Sit")
        self.assertEqual(normalized[2].normalized_text, "0.40")
        self.assertEqual(normalized[3].normalized_text, "2. шт x 104.00")
        self.assertIn("price_like", normalized[3].tags)
        self.assertIn("content_like", normalized[3].tags)

    def test_normalizer_flags_barcode_and_noise_lines_from_realistic_receipt_fixture(self):
        lines = [mapped_line(text, order) for order, text in enumerate(load_fixture("noisy-ukrainian-receipt-lines.txt"))]

        normalized = self.normalizer.normalize_lines(lines)

        barcode_line = next(line for line in normalized if "4023080005494" in line.original_text)
        self.assertIn("barcode_like", barcode_line.tags)
        self.assertTrue(barcode_line.ignored)

        amount_line = next(line for line in normalized if line.original_text == "CyHKOM: 5.60")
        self.assertEqual(amount_line.normalized_text, "CyHKOM: 5.60")
        self.assertIn("price_like", amount_line.tags)
        self.assertIn("content_like", amount_line.tags)
        self.assertFalse(amount_line.ignored)

    def test_normalizer_marks_header_and_preserves_bank_lines_for_future_parser(self):
        lines = [mapped_line(text, order) for order, text in enumerate(load_fixture("bank-like-noisy-lines.txt"))]

        normalized = self.normalizer.normalize_lines(lines)

        header_line = normalized[0]
        self.assertIn("header_like", header_line.tags)
        self.assertFalse(header_line.ignored)

        account_line = next(line for line in normalized if "A73" in line.original_text)
        self.assertFalse(account_line.ignored)
        self.assertNotIn("barcode_like", account_line.tags)

    def test_clean_synthetic_lines_remain_stable(self):
        normalized = self.normalizer.normalize_lines(
            [
                mapped_line("RECEIPT", 0),
                mapped_line("Date 2026-04-06", 1),
                mapped_line("Balance 480.00", 2),
                mapped_line("Coffee 120.50", 3),
                mapped_line("Croissant 89.90", 4),
                mapped_line("TOTAL 210.40", 5),
            ]
        )

        self.assertEqual(
            [line.normalized_text for line in normalized],
            [
                "RECEIPT",
                "Date 2026-04-06",
                "Balance 480.00",
                "Coffee 120.50",
                "Croissant 89.90",
                "TOTAL 210.40",
            ],
        )
        self.assertTrue(all(not line.ignored for line in normalized))

    def test_normalizer_keeps_traceability_between_original_and_normalized_lines(self):
        line = self.normalizer.normalize_lines([mapped_line("THANK.YOU", 0)])[0]

        self.assertEqual(line.original_text, "THANK.YOU")
        self.assertEqual(line.normalized_text, "THANK YOU")
        self.assertEqual(line.order, 0)
        self.assertFalse(line.ignored)
        self.assertIn("header_like", line.tags)


def mapped_line(text: str, order: int) -> MappedOcrLine:
    return MappedOcrLine(text=text, confidence=0.98, order=order, bbox=None)


def load_fixture(name: str) -> list[str]:
    return [line for line in (FIXTURES_DIR / name).read_text(encoding="utf-8").splitlines() if line.strip()]


if __name__ == "__main__":
    unittest.main()
