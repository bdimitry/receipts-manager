import unittest

from response_mapping import PaddleOcrResponseMapper


class PaddleOcrResponseMapperTests(unittest.TestCase):

    def test_mapper_sorts_lines_top_to_bottom_then_left_to_right(self):
        mapper = PaddleOcrResponseMapper()

        result = mapper.map_page_lines([[
            [
                [[320, 210], [460, 210], [460, 245], [320, 245]],
                ("TOTAL 132.60", 0.97),
            ],
            [
                [[110, 40], [340, 40], [340, 80], [110, 80]],
                ("SILPO MARKET", 0.99),
            ],
            [
                [[120, 130], [260, 130], [260, 165], [120, 165]],
                ("MILK 42.50", 0.98),
            ],
            [
                [[290, 132], [445, 132], [445, 166], [290, 166]],
                ("BREAD 28.90", 0.981),
            ],
        ]])

        self.assertEqual([line.text for line in result], [
            "SILPO MARKET",
            "MILK 42.50",
            "BREAD 28.90",
            "TOTAL 132.60",
        ])
        self.assertEqual([line.order for line in result], [0, 1, 2, 3])
        self.assertEqual(result[1].bbox, ((120.0, 130.0), (260.0, 130.0), (260.0, 165.0), (120.0, 165.0)))


if __name__ == "__main__":
    unittest.main()
