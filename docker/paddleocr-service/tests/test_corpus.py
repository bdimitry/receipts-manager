import unittest

from corpus import diagnostic_corpus


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
            ],
        )
        self.assertTrue(all(case.expected_labels for case in cases))
        self.assertTrue(all(case.expected_numbers for case in cases))


if __name__ == "__main__":
    unittest.main()
