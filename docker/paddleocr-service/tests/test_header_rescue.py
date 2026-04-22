import unittest

from PIL import Image

from header_rescue import HeaderBlockRescue
from preprocessing import ImageSize, PreprocessedReceiptImage
from response_mapping import MappedOcrLine, PaddleOcrResponseMapper


class HeaderBlockRescueTests(unittest.TestCase):

    def test_replaces_weak_top_block_with_better_header_crop_lines(self):
        rescue = HeaderBlockRescue(crop_fraction=0.30, upscale_factor=2.0)
        image = Image.new("RGB", (1000, 2400), "white")
        existing_lines = [
            mapped_line("MArA30I PANOH,", 0, bbox(380, 90, 840, 166)),
            mapped_line("nH360036026593", 1, bbox(364, 252, 707, 335)),
            mapped_line("KCO Kaca 09", 2, bbox(409, 309, 662, 386)),
            mapped_line("yek #_[1050:9.1120.262]", 3, bbox(107, 407, 615, 500)),
            mapped_line("01050000090112000262", 4, bbox(232, 510, 875, 621)),
            mapped_line("Hanin ra3.Coca-Co1a 1,75n nET 49.99 A", 5, bbox(115, 575, 1012, 714)),
        ]

        engine = FakeHeaderEngine([
            ocr_entry("MArA3NH NOVUS", 0.84, bbox(386 * 2, 92 * 2, 836 * 2, 166 * 2)),
            ocr_entry("KHB AAPHNUbKN PANOH", 0.74, bbox(180 * 2, 162 * 2, 910 * 2, 232 * 2)),
            ocr_entry("TANbHIBCbKA", 0.71, bbox(260 * 2, 236 * 2, 760 * 2, 292 * 2)),
            ocr_entry('TOB "HOBYC yKPAIHA', 0.82, bbox(250 * 2, 298 * 2, 780 * 2, 352 * 2)),
            ocr_entry("NH 360036026593", 0.93, bbox(364 * 2, 252 * 2, 707 * 2, 335 * 2)),
            ocr_entry("KCO Kaca 09", 0.92, bbox(409 * 2, 309 * 2, 662 * 2, 386 * 2)),
            ocr_entry("yeK #[1050:9.1120.262]", 0.89, bbox(107 * 2, 407 * 2, 615 * 2, 500 * 2)),
        ])

        result = rescue.rescue_page_lines(
            page_index=0,
            original_image=image,
            processed_page=processed_page(image),
            existing_lines=existing_lines,
            ocr_engine=engine,
            response_mapper=PaddleOcrResponseMapper(),
        )

        self.assertTrue(result.applied)
        self.assertEqual(result.strategy, "top_crop_raw_2x")
        self.assertGreater(result.score_after, result.score_before)
        self.assertEqual(
            [line.text for line in result.lines[:5]],
            [
                "MArA3NH NOVUS",
                "KHB AAPHNUbKN PANOH",
                "TANbHIBCbKA",
                'TOB "HOBYC yKPAIHA',
                "nH360036026593",
            ],
        )
        self.assertEqual(result.lines[5].text, "KCO Kaca 09")
        self.assertEqual(result.lines[6].text, "yek #_[1050:9.1120.262]")
        self.assertEqual(result.lines[7].text, "01050000090112000262")
        self.assertEqual(result.lines[8].text, "Hanin ra3.Coca-Co1a 1,75n nET 49.99 A")

    def test_skips_header_rescue_when_crop_is_not_better(self):
        rescue = HeaderBlockRescue(crop_fraction=0.30, upscale_factor=2.0)
        image = Image.new("RGB", (1000, 2400), "white")
        existing_lines = [
            mapped_line("FRESH MARKET", 0, bbox(120, 90, 620, 150)),
            mapped_line("MAIN STREET 1", 1, bbox(120, 170, 620, 220)),
            mapped_line("DATE 2026-04-22", 2, bbox(120, 250, 620, 300)),
            mapped_line("MILK 42.50", 3, bbox(120, 620, 820, 680)),
        ]

        engine = FakeHeaderEngine([
            ocr_entry("FRESH", 0.71, bbox(140 * 2, 100 * 2, 420 * 2, 160 * 2)),
        ])

        result = rescue.rescue_page_lines(
            page_index=0,
            original_image=image,
            processed_page=processed_page(image),
            existing_lines=existing_lines,
            ocr_engine=engine,
            response_mapper=PaddleOcrResponseMapper(),
        )

        self.assertFalse(result.applied)
        self.assertEqual([line.text for line in result.lines], [line.text for line in existing_lines])

    def test_replaces_header_even_when_processed_page_has_warped_geometry(self):
        rescue = HeaderBlockRescue(crop_fraction=0.30, upscale_factor=2.0)
        original = Image.new("RGB", (1441, 2560), "white")
        processed = Image.new("RGB", (1038, 2029), "white")
        existing_lines = [
            mapped_line("MArA30I", 0, bbox(380, 90, 540, 136)),
            mapped_line("PANOH,", 1, bbox(556, 92, 820, 146)),
            mapped_line("nH360036026593", 2, bbox(364, 252, 707, 335)),
            mapped_line("KCO Kaca 09", 3, bbox(409, 309, 662, 386)),
            mapped_line("yek #_[1050:9.1120.262]", 4, bbox(107, 407, 615, 500)),
        ]

        engine = FakeHeaderEngine([
            ocr_entry("MArA3NH NOVUS", 0.84, bbox(386 * 2, 92 * 2, 836 * 2, 166 * 2)),
            ocr_entry("KHBHAPHNUBK PANOH", 0.74, bbox(180 * 2, 162 * 2, 910 * 2, 232 * 2)),
            ocr_entry("TAN6HIBCbKA", 0.71, bbox(260 * 2, 236 * 2, 760 * 2, 292 * 2)),
            ocr_entry('TOB "HOBYC yKPAIHA', 0.82, bbox(250 * 2, 298 * 2, 780 * 2, 352 * 2)),
            ocr_entry("NH 360036026593", 0.93, bbox(364 * 2, 252 * 2, 707 * 2, 335 * 2)),
        ])

        result = rescue.rescue_page_lines(
            page_index=0,
            original_image=original,
            processed_page=processed_page(processed, strategy="strong"),
            existing_lines=existing_lines,
            ocr_engine=engine,
            response_mapper=PaddleOcrResponseMapper(),
        )

        self.assertTrue(result.applied)
        self.assertEqual(
            [line.text for line in result.lines[:4]],
            [
                "MArA3NH NOVUS",
                "KHBHAPHNUBK PANOH",
                "TAN6HIBCbKA",
                'TOB "HOBYC yKPAIHA',
            ],
        )
        rescue_tops = [min(point[1] for point in line.bbox) for line in result.lines[:4]]
        self.assertTrue(all(top < 252 for top in rescue_tops))
        self.assertEqual(result.lines[4].text, "nH360036026593")


class FakeHeaderEngine:
    def __init__(self, entries):
        self.entries = entries

    def extract_lines(self, image_array, profile_override=None, language_override=None):
        return [self.entries]


def mapped_line(text, order, box):
    return MappedOcrLine(text=text, confidence=0.95, order=order, bbox=box)


def processed_page(image, strategy="disabled"):
    return PreprocessedReceiptImage(
        image=image,
        applied=strategy != "disabled",
        size_before=ImageSize(width=image.width, height=image.height),
        size_after=ImageSize(width=image.width, height=image.height),
        strategy=strategy,
        steps_applied=tuple(),
        upscale_factor=1.0,
        crop_box=None,
        deskew_applied=False,
    )


def bbox(left, top, right, bottom):
    return ((float(left), float(top)), (float(right), float(top)), (float(right), float(bottom)), (float(left), float(bottom)))


def ocr_entry(text, confidence, box):
    return [list(point) for point in box], (text, confidence)


if __name__ == "__main__":
    unittest.main()
