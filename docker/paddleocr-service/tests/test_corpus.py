import unittest

from corpus import diagnostic_corpus
from normalization import ReceiptOcrLineNormalizer
from response_mapping import MappedOcrLine


class DiagnosticCorpusTests(unittest.TestCase):

    def test_corpus_contains_expected_document_set(self):
        cases = diagnostic_corpus()

        self.assertEqual(
            [case.name for case in cases],
            [
                "clean-english-receipt",
                "cyrillic-transaction",
                "mixed-script-receipt",
                "bank-like-document",
                "pdf-rendered-payment-page",
            ],
        )
        self.assertTrue(all(case.expected_labels for case in cases))
        self.assertTrue(all(case.expected_numbers for case in cases))

    def test_corpus_reference_lines_stay_stable_after_conservative_normalization(self):
        normalizer = ReceiptOcrLineNormalizer()
        clean_reference_lines = [
            MappedOcrLine(text="RECEIPT", confidence=0.99, order=0, bbox=None),
            MappedOcrLine(text="Date 2026-04-06", confidence=0.99, order=1, bbox=None),
            MappedOcrLine(text="Coffee 120.50", confidence=0.99, order=2, bbox=None),
            MappedOcrLine(text="TOTAL 210.40", confidence=0.99, order=3, bbox=None),
        ]

        normalized = normalizer.normalize_lines(clean_reference_lines)

        self.assertEqual(
            [line.normalized_text for line in normalized],
            ["RECEIPT", "Date 2026-04-06", "Coffee 120.50", "TOTAL 210.40"],
        )
        self.assertTrue(all(not line.ignored for line in normalized))


if __name__ == "__main__":
    unittest.main()
