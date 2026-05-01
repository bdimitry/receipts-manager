from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Sequence

import cv2
import numpy as np
from PIL import Image

from preprocessing import PreprocessedReceiptImage
from response_mapping import MappedOcrLine, PaddleOcrResponseMapper


AMOUNT_LIKE_PATTERN = re.compile(
    r"(?iu)^\d{1,5}(?:[ \u00A0]\d{3})*[\.,]\d{2}(?:\s*[A-Za-zА-Яа-яІіЇїЄєҐґ₴$€]+)?$"
)
QUANTITY_LIKE_PATTERN = re.compile(
    r"(?iu)^\d{1,4}(?:[\.,]\d{1,3})?\s*[A-Za-zА-Яа-яІіЇїЄєҐґ]{0,4}$"
)
LONG_DIGIT_PATTERN = re.compile(r"\d{8,}")
PUNCT_HEAVY_PATTERN = re.compile(r"^[\W_]+$")


@dataclass(frozen=True)
class HeaderRescueResult:
    applied: bool
    strategy: str
    lines: tuple[MappedOcrLine, ...]
    rescue_lines: tuple[MappedOcrLine, ...]
    score_before: float
    score_after: float
    replaced_count: int

    def to_response(self, page_index: int) -> dict:
        return {
            "pageIndex": page_index,
            "applied": self.applied,
            "strategy": self.strategy,
            "scoreBefore": round(self.score_before, 2),
            "scoreAfter": round(self.score_after, 2),
            "replacedCount": self.replaced_count,
            "lines": [line.to_response() for line in self.rescue_lines],
        }


class HeaderBlockRescue:
    def __init__(
        self,
        crop_fraction: float = 0.30,
        upscale_factor: float = 2.0,
        enhanced_upscale_factor: float = 3.0,
    ) -> None:
        self.crop_fraction = crop_fraction
        self.upscale_factor = upscale_factor
        self.enhanced_upscale_factor = enhanced_upscale_factor

    def rescue_page_lines(
        self,
        page_index: int,
        original_image: Image.Image,
        processed_page: PreprocessedReceiptImage,
        existing_lines: Sequence[MappedOcrLine],
        ocr_engine,
        response_mapper: PaddleOcrResponseMapper,
        profile_override: str | None = None,
        language_override: str | None = None,
    ) -> HeaderRescueResult:
        if page_index != 0 or not existing_lines:
            return HeaderRescueResult(
                applied=False,
                strategy="disabled",
                lines=tuple(existing_lines),
                rescue_lines=tuple(),
                score_before=0.0,
                score_after=0.0,
                replaced_count=0,
            )

        existing_header_prefix = self._header_prefix(existing_lines)
        if not existing_header_prefix:
            return HeaderRescueResult(
                applied=False,
                strategy="no_header_prefix",
                lines=tuple(existing_lines),
                rescue_lines=tuple(),
                score_before=0.0,
                score_after=0.0,
                replaced_count=0,
            )

        crop_box = self._header_crop_box(processed_page, existing_lines, existing_header_prefix)
        header_crop = processed_page.image.crop(crop_box)
        best_strategy = "processed_top_crop_raw"
        best_prefix: list[MappedOcrLine] = []
        best_score = 0.0
        best_rescue_lines: list[MappedOcrLine] = []

        for strategy, candidate_image, scale_factor, coordinate_space, candidate_crop_box in self._candidate_crops(
            original_image,
            processed_page,
            header_crop,
            crop_box,
            existing_header_prefix,
        ):
            raw_result = ocr_engine.extract_lines(
                np.array(candidate_image.convert("RGB")),
                profile_override=profile_override,
                language_override=language_override,
            )
            candidate_lines = [
                self._map_candidate_to_processed_coordinates(
                    line,
                    coordinate_space,
                    candidate_crop_box,
                    scale_factor,
                    original_image,
                    processed_page,
                )
                for line in response_mapper.map_page_lines(raw_result, order_offset=0)
                if self._keep_rescue_candidate(line)
            ]
            candidate_prefix = self._normalize_header_geometry(
                self._header_prefix(candidate_lines),
                anchor_top=self._anchor_top(existing_lines, len(existing_header_prefix)),
            )
            candidate_score = self._score_header_prefix(candidate_prefix)
            if candidate_score > best_score:
                best_score = candidate_score
                best_prefix = candidate_prefix
                best_strategy = strategy
                best_rescue_lines = candidate_lines

        score_before = self._score_header_prefix(existing_header_prefix)
        if len(best_prefix) < 2 or best_score <= score_before + 8:
            return HeaderRescueResult(
                applied=False,
                strategy=best_strategy,
                lines=tuple(existing_lines),
                rescue_lines=tuple(best_rescue_lines),
                score_before=score_before,
                score_after=best_score,
                replaced_count=0,
            )

        remaining_lines = list(existing_lines[len(existing_header_prefix):])
        combined_lines = self._reindex([*best_prefix, *remaining_lines])

        return HeaderRescueResult(
            applied=True,
            strategy=best_strategy,
            lines=tuple(combined_lines),
            rescue_lines=tuple(best_prefix),
            score_before=score_before,
            score_after=best_score,
            replaced_count=len(existing_header_prefix),
        )

    def _candidate_crops(
        self,
        original_image: Image.Image,
        processed_page: PreprocessedReceiptImage,
        header_crop: Image.Image,
        processed_crop_box: tuple[int, int, int, int],
        existing_header_prefix: Sequence[MappedOcrLine],
    ) -> list[tuple[str, Image.Image, float, str, tuple[int, int, int, int]]]:
        base_scale = self._header_scale(existing_header_prefix, header_crop.height)
        enhanced_scale = max(base_scale, self.enhanced_upscale_factor)
        original_crop_box = (
            0,
            0,
            original_image.width,
            max(96, min(original_image.height, int(original_image.height * self.crop_fraction))),
        )
        original_crop = original_image.crop(original_crop_box)
        return [
            ("processed_top_crop_raw", self._upscale(header_crop, base_scale), base_scale, "processed", processed_crop_box),
            (
                "processed_top_crop_enhanced",
                self._upscale(self._enhance_header_crop(header_crop), enhanced_scale),
                enhanced_scale,
                "processed",
                processed_crop_box,
            ),
            ("original_top_crop_raw", self._upscale(original_crop, self.upscale_factor), self.upscale_factor, "original", original_crop_box),
        ]

    def _header_crop_box(
        self,
        processed_page: PreprocessedReceiptImage,
        existing_lines: Sequence[MappedOcrLine],
        existing_header_prefix: Sequence[MappedOcrLine],
    ) -> tuple[int, int, int, int]:
        width = processed_page.image.width
        height = processed_page.image.height
        fallback_bottom = max(120, int(height * self.crop_fraction))
        anchor_top = self._anchor_top(existing_lines, len(existing_header_prefix))
        average_height = self._average_height(existing_header_prefix)
        margin = max(36.0, average_height * 1.9)
        if anchor_top is None:
            bottom = fallback_bottom
        else:
            bottom = int(max(fallback_bottom, anchor_top + margin))
        bottom = max(96, min(int(height * 0.45), bottom))
        return (0, 0, width, bottom)

    def _upscale(self, image: Image.Image, factor: float) -> Image.Image:
        if factor <= 1.0:
            return image
        width = int(round(image.width * factor))
        height = int(round(image.height * factor))
        return image.resize((width, height), Image.Resampling.LANCZOS)

    def _enhance_header_crop(self, image: Image.Image) -> Image.Image:
        rgb = np.array(image.convert("RGB"))
        gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
        denoised = cv2.fastNlMeansDenoising(gray, None, 5, 7, 21)
        clahe = cv2.createCLAHE(clipLimit=2.4, tileGridSize=(6, 6)).apply(denoised)
        contrast = cv2.addWeighted(denoised, 0.35, clahe, 0.65, 0)
        blurred = cv2.GaussianBlur(contrast, (0, 0), 1.1)
        sharpened = cv2.addWeighted(contrast, 1.55, blurred, -0.55, 0)
        return Image.fromarray(cv2.cvtColor(sharpened, cv2.COLOR_GRAY2RGB))

    def _header_scale(self, lines: Sequence[MappedOcrLine], crop_height: int) -> float:
        average_height = self._average_height(lines)
        if average_height <= 0:
            average_height = max(20.0, crop_height / 8.0)
        if average_height < 26:
            return max(self.upscale_factor, 3.0)
        if average_height < 34:
            return max(self.upscale_factor, 2.5)
        return self.upscale_factor

    def _average_height(self, lines: Sequence[MappedOcrLine]) -> float:
        heights = []
        for line in lines:
            if not line.bbox:
                continue
            top = min(point[1] for point in line.bbox)
            bottom = max(point[1] for point in line.bbox)
            heights.append(max(1.0, bottom - top))
        return sum(heights) / len(heights) if heights else 0.0

    def _map_candidate_to_processed_coordinates(
        self,
        line: MappedOcrLine,
        coordinate_space: str,
        crop_box: tuple[int, int, int, int],
        scale_factor: float,
        original_image: Image.Image,
        processed_page: PreprocessedReceiptImage,
    ) -> MappedOcrLine:
        if coordinate_space == "original":
            return self._map_from_original_to_processed_coordinates(
                line,
                crop_box,
                scale_factor,
                original_image,
                processed_page,
            )
        return self._map_processed_crop_to_processed_coordinates(line, crop_box, scale_factor)

    def _map_processed_crop_to_processed_coordinates(
        self,
        line: MappedOcrLine,
        crop_box: tuple[int, int, int, int],
        scale_factor: float,
    ) -> MappedOcrLine:
        if not line.bbox:
            return line

        crop_left, crop_top, _, _ = crop_box
        scaled_bbox = tuple(
            (
                (x / scale_factor) + crop_left,
                (y / scale_factor) + crop_top,
            )
            for x, y in line.bbox
        )
        return MappedOcrLine(
            text=line.text,
            confidence=line.confidence,
            order=line.order,
            bbox=scaled_bbox,
        )

    def _map_from_original_to_processed_coordinates(
        self,
        line: MappedOcrLine,
        crop_box: tuple[int, int, int, int],
        scale_factor: float,
        original_image: Image.Image,
        processed_page: PreprocessedReceiptImage,
    ) -> MappedOcrLine:
        if not line.bbox:
            return line

        crop_left, crop_top, _, _ = crop_box
        original_width = max(1.0, float(original_image.width))
        original_height = max(1.0, float(original_image.height))
        processed_width = float(processed_page.image.width)
        processed_height = float(processed_page.image.height)
        page_scale = processed_page.upscale_factor if processed_page.upscale_factor else 1.0

        if processed_page.crop_box:
            processed_left = processed_page.crop_box[0]
            processed_top = processed_page.crop_box[1]
            scaled_bbox = tuple(
                (
                    (((x / scale_factor) + crop_left) * page_scale) - processed_left,
                    (((y / scale_factor) + crop_top) * page_scale) - processed_top,
                )
                for x, y in line.bbox
            )
        else:
            width_scale = processed_width / original_width
            height_scale = processed_height / original_height
            scaled_bbox = tuple(
                (
                    (((x / scale_factor) + crop_left) * width_scale),
                    (((y / scale_factor) + crop_top) * height_scale),
                )
                for x, y in line.bbox
            )

        return MappedOcrLine(
            text=line.text,
            confidence=line.confidence,
            order=line.order,
            bbox=scaled_bbox,
        )

    def _keep_rescue_candidate(self, line: MappedOcrLine) -> bool:
        text = (line.text or "").strip()
        if not text:
            return False

        if self._is_header_anchor(text):
            return False

        compact = text.replace(" ", "")
        if compact.isdigit() and len(compact) < 6:
            return False

        if PUNCT_HEAVY_PATTERN.match(compact):
            return False

        letter_count = sum(1 for character in text if character.isalpha())
        if letter_count == 0 and "#" not in text and "[" not in text:
            return False

        return True

    def _header_prefix(self, lines: Sequence[MappedOcrLine]) -> list[MappedOcrLine]:
        prefix: list[MappedOcrLine] = []
        for line in self._sort_lines(lines):
            text = (line.text or "").strip()
            if not text:
                continue
            if self._is_header_anchor(text):
                break
            if prefix and (self._is_amount_like(text) or self._is_quantity_like(text) or self._looks_like_barcode_or_code(text)):
                break
            prefix.append(line)
            if len(prefix) >= 6:
                break
        return prefix

    def _is_header_anchor(self, text: str) -> bool:
        lowered = self._normalize(text)
        digits = "".join(character for character in text if character.isdigit())
        return (
            "kco" in lowered
            or "kaca" in lowered
            or "kasa" in lowered
            or ("#" in text and "[" in text and "]" in text)
            or len(digits) >= 8
            or lowered.startswith("nh")
            or lowered.startswith("пн")
        )

    def _score_header_prefix(self, lines: Sequence[MappedOcrLine]) -> float:
        if not lines:
            return 0.0

        average_confidence = sum((line.confidence or 0.0) for line in lines) / len(lines)
        alpha_count = sum(sum(1 for character in (line.text or "") if character.isalpha()) for line in lines)
        useful_line_bonus = sum(1 for line in lines if len(self._normalize(line.text).replace(" ", "")) >= 6) * 5
        keyword_bonus = sum(8 for line in lines if self._looks_like_header_keyword(line.text))
        short_penalty = sum(1 for line in lines if len((line.text or "").strip()) < 5) * 7
        digit_penalty = sum(1 for line in lines if self._mostly_digits(line.text)) * 10
        amount_penalty = sum(1 for line in lines if self._is_amount_like(line.text) or self._is_quantity_like(line.text)) * 12
        barcode_penalty = sum(1 for line in lines if self._looks_like_barcode_or_code(line.text)) * 10
        punctuation_penalty = sum(1 for line in lines if self._punctuation_ratio(line.text) > 0.2) * 4
        alignment_bonus = self._alignment_bonus(lines)

        return (
            len(lines) * 9
            + alpha_count * 0.28
            + average_confidence * 20
            + useful_line_bonus
            + keyword_bonus
            + alignment_bonus
            - short_penalty
            - digit_penalty
            - amount_penalty
            - barcode_penalty
            - punctuation_penalty
        )

    def _looks_like_header_keyword(self, text: str) -> bool:
        normalized = self._normalize(text)
        return any(
            probe in normalized
            for probe in (
                "maga3",
                "magaz",
                "mara3",
                "market",
                "store",
                "bank",
                "район",
                "raion",
                "вул",
                "street",
                "tob",
                "тов",
                "ukra",
                "apte",
                "teka",
                "апт",
            )
        )

    def _looks_like_barcode_or_code(self, text: str) -> bool:
        normalized = self._normalize(text)
        return (
            LONG_DIGIT_PATTERN.search(text.replace(" ", "")) is not None
            or "kod" in normalized
            or "koa" in normalized
            or "ean" in normalized
            or "штрих" in normalized
        )

    def _is_amount_like(self, text: str) -> bool:
        return AMOUNT_LIKE_PATTERN.match((text or "").strip()) is not None

    def _is_quantity_like(self, text: str) -> bool:
        compact = (text or "").strip()
        if not compact or self._is_amount_like(compact):
            return False
        return QUANTITY_LIKE_PATTERN.match(compact) is not None

    def _mostly_digits(self, text: str) -> bool:
        compact = (text or "").replace(" ", "")
        if not compact:
            return False
        digit_count = sum(1 for character in compact if character.isdigit())
        return digit_count >= max(6, len(compact) * 0.7)

    def _punctuation_ratio(self, text: str) -> float:
        compact = (text or "").strip()
        if not compact:
            return 0.0
        punctuation_count = sum(1 for character in compact if not character.isalnum() and not character.isspace())
        return punctuation_count / float(len(compact))

    def _alignment_bonus(self, lines: Sequence[MappedOcrLine]) -> float:
        lefts = [self._left(line) for line in lines if line.bbox]
        if len(lefts) < 2:
            return 0.0
        return 6.0 if max(lefts) - min(lefts) <= 180.0 else 0.0

    def _sort_lines(self, lines: Sequence[MappedOcrLine]) -> list[MappedOcrLine]:
        return sorted(lines, key=lambda line: (self._top(line), self._left(line), line.order))

    def _reindex(self, lines: Sequence[MappedOcrLine]) -> list[MappedOcrLine]:
        return [
            MappedOcrLine(
                text=line.text,
                confidence=line.confidence,
                order=index,
                bbox=line.bbox,
            )
            for index, line in enumerate(lines)
        ]

    def _normalize_header_geometry(
        self,
        lines: Sequence[MappedOcrLine],
        anchor_top: float | None = None,
    ) -> list[MappedOcrLine]:
        if not lines:
            return []

        line_boxes: list[tuple[MappedOcrLine, float, float, float]] = []
        total_height = 0.0
        for line in lines:
            if line.bbox:
                left = min(point[0] for point in line.bbox)
                right = max(point[0] for point in line.bbox)
                height = max(24.0, max(point[1] for point in line.bbox) - min(point[1] for point in line.bbox))
            else:
                left = 0.0
                right = 200.0
                height = 28.0
            line_boxes.append((line, left, right, height))
            total_height += height

        gap_height = max(0.0, float(len(line_boxes) - 1) * 10.0)
        if anchor_top is None:
            current_top = min(self._top(line) for line in lines)
        else:
            current_top = max(0.0, anchor_top - total_height - gap_height - 12.0)
        normalized: list[MappedOcrLine] = []

        for line, left, right, height in line_boxes:
            bottom = current_top + height
            bbox = (
                (left, current_top),
                (right, current_top),
                (right, bottom),
                (left, bottom),
            )
            normalized.append(
                MappedOcrLine(
                    text=line.text,
                    confidence=line.confidence,
                    order=line.order,
                    bbox=bbox,
                )
            )
            current_top = bottom + 10.0

        return normalized

    def _anchor_top(self, lines: Sequence[MappedOcrLine], header_prefix_length: int) -> float | None:
        if header_prefix_length >= len(lines):
            return None
        return self._top(lines[header_prefix_length])

    def _top(self, line: MappedOcrLine) -> float:
        if not line.bbox:
            return float(line.order)
        return min(point[1] for point in line.bbox)

    def _left(self, line: MappedOcrLine) -> float:
        if not line.bbox:
            return 0.0
        return min(point[0] for point in line.bbox)

    def _normalize(self, value: str | None) -> str:
        if not value:
            return ""
        return re.sub(r"\s+", " ", value).strip().lower()
