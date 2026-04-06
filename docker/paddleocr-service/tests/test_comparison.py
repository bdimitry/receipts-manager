import unittest

from comparison import choose_recommended_profile, evaluate_profile_case, summarize_profile
from corpus import diagnostic_corpus


class ComparisonTests(unittest.TestCase):

    def test_evaluation_penalizes_mixed_script_and_rewards_expected_hits(self):
        case = diagnostic_corpus()[0]

        en_evaluation = evaluate_profile_case(
            case,
            profile_name="en",
            mapped_raw_text="RECEIPT\nDate 2026-04-06\nBalance 480.00\nCoffee 120.50\nCroissant 89.90\nTOTAL 210.40",
            mapped_lines=[
                {"text": "RECEIPT"},
                {"text": "Date 2026-04-06"},
                {"text": "Balance 480.00"},
                {"text": "Coffee 120.50"},
                {"text": "Croissant 89.90"},
                {"text": "TOTAL 210.40"},
            ],
        )
        cyrillic_evaluation = evaluate_profile_case(
            case,
            profile_name="cyrillic",
            mapped_raw_text="RECEIРT\nDatc 2026-04-06\nBalanсe 480.00\nCottee 120.50\nCroissant 89.90\nTOTAL 210.40",
            mapped_lines=[
                {"text": "RECEIРT"},
                {"text": "Datc 2026-04-06"},
                {"text": "Balanсe 480.00"},
                {"text": "Cottee 120.50"},
                {"text": "Croissant 89.90"},
                {"text": "TOTAL 210.40"},
            ],
        )

        self.assertGreater(en_evaluation.score, cyrillic_evaluation.score)
        summaries = [
            summarize_profile("en", [en_evaluation, cyrillic_evaluation]),
            summarize_profile("cyrillic", [en_evaluation, cyrillic_evaluation]),
        ]
        self.assertEqual(choose_recommended_profile(summaries)["profileName"], "en")


if __name__ == "__main__":
    unittest.main()
