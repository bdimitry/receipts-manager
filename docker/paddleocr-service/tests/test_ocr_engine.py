import unittest
from unittest.mock import patch

import numpy as np

from ocr_engine import PaddleOcrEngine


class PaddleOcrEngineTests(unittest.TestCase):

    @patch("ocr_engine.PaddleOCR")
    def test_non_default_profile_can_be_initialized_after_default_profile(self, paddle_ocr_mock):
        paddle_ocr_mock.return_value.ocr.return_value = [[[]]]
        engine = PaddleOcrEngine(profile_name="en")

        engine.warm_up()
        description = engine.describe(profile_override="cyrillic")
        engine.extract_lines(np.zeros((8, 8, 3), dtype=np.uint8), profile_override="cyrillic")

        self.assertEqual(description["profile"], "cyrillic")
        self.assertTrue(any(call.kwargs["lang"] == "en" for call in paddle_ocr_mock.call_args_list))
        self.assertTrue(any(call.kwargs["lang"] == "cyrillic" for call in paddle_ocr_mock.call_args_list))


if __name__ == "__main__":
    unittest.main()
