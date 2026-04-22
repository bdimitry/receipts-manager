from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np
from PIL import Image

from preprocessing import PreprocessedReceiptImage
from response_mapping import MappedOcrLine, PaddleOcrResponseMapper


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
    def __init__(self, crop_fraction: float = 0.30, upscale_factor: float = 2.0) -> None:
        self.crop_fraction = crop_fraction
        self.upscale_factor = upscale_factor

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

        if processed_page.deskew_applied:
            return HeaderRescueResult(
                applied=False,
                strategy="skipped_deskewed_page",
                lines=tuple(existing_lines),
                rescue_lines=tuple(),
                score_before=0.0,
                score_after=0.0,
                replaced_count=0,
            )

        crop_height = max(1, int(original_image.height * self.crop_fraction))
        header_crop = original_image.crop((0, 0, original_image.width, crop_height))
        rescue_image = self._upscale(header_crop, self.upscale_factor)
        raw_result = ocr_engine.extract_lines(
            np.array(rescue_image.convert("RGB")),
            profile_override=profile_override,
            language_override=language_override,
        )
        rescue_lines = [
            self._map_to_processed_coordinates(line, original_image, processed_page)
            for line in response_mapper.map_page_lines(raw_result, order_offset=0)
            if self._keep_rescue_candidate(line)
        ]

        if not rescue_lines:
            return HeaderRescueResult(
                applied=False,
                strategy="top_crop_raw_2x",
                lines=tuple(existing_lines),
                rescue_lines=tuple(),
                score_before=0.0,
                score_after=0.0,
                replaced_count=0,
            )

        existing_header_prefix = self._header_prefix(existing_lines)
        rescue_header_prefix = self._normalize_header_geometry(
            self._header_prefix(rescue_lines),
            anchor_top=self._anchor_top(existing_lines, len(existing_header_prefix)),
        )

        score_before = self._score_header_prefix(existing_header_prefix)
        score_after = self._score_header_prefix(rescue_header_prefix)

        if len(rescue_header_prefix) < 2 or score_after <= score_before + 12:
            return HeaderRescueResult(
                applied=False,
                strategy="top_crop_raw_2x",
                lines=tuple(existing_lines),
                rescue_lines=tuple(rescue_lines),
                score_before=score_before,
                score_after=score_after,
                replaced_count=0,
            )

        remaining_lines = list(existing_lines[len(existing_header_prefix):])
        combined_lines = self._reindex([*rescue_header_prefix, *remaining_lines])

        return HeaderRescueResult(
            applied=True,
            strategy="top_crop_raw_2x",
            lines=tuple(combined_lines),
            rescue_lines=tuple(rescue_header_prefix),
            score_before=score_before,
            score_after=score_after,
            replaced_count=len(existing_header_prefix),
        )

    def _upscale(self, image: Image.Image, factor: float) -> Image.Image:
        if factor <= 1.0:
            return image
        width = int(round(image.width * factor))
        height = int(round(image.height * factor))
        return image.resize((width, height), Image.Resampling.LANCZOS)

    def _map_to_processed_coordinates(
        self,
        line: MappedOcrLine,
        original_image: Image.Image,
        processed_page: PreprocessedReceiptImage,
    ) -> MappedOcrLine:
        if not line.bbox:
            return line

        original_width = max(1.0, float(original_image.width))
        original_height = max(1.0, float(original_image.height))
        processed_width = float(processed_page.image.width)
        processed_height = float(processed_page.image.height)
        crop_left = processed_page.crop_box[0] if processed_page.crop_box else 0
        crop_top = processed_page.crop_box[1] if processed_page.crop_box else 0
        page_scale = processed_page.upscale_factor if processed_page.upscale_factor else 1.0

        if processed_page.crop_box:
            scaled_bbox = tuple(
                (
                    (x / self.upscale_factor) * page_scale - crop_left,
                    (y / self.upscale_factor) * page_scale - crop_top,
                )
                for x, y in line.bbox
            )
        else:
            width_scale = processed_width / original_width
            height_scale = processed_height / original_height
            scaled_bbox = tuple(
                (
                    (x / self.upscale_factor) * width_scale,
                    (y / self.upscale_factor) * height_scale,
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

        letter_count = sum(1 for character in text if character.isalpha())
        if letter_count == 0 and "#" not in text and "[" not in text:
            return False

        return True

    def _header_prefix(self, lines: Sequence[MappedOcrLine]) -> list[MappedOcrLine]:
        prefix: list[MappedOcrLine] = []
        for line in self._sort_lines(lines):
            if self._is_header_anchor(line.text):
                break
            prefix.append(line)
        return prefix

    def _is_header_anchor(self, text: str) -> bool:
        lowered = text.lower()
        digits = "".join(character for character in text if character.isdigit())
        return (
            "kco" in lowered
            or "kaca" in lowered
            or "kasa" in lowered
            or ("#" in text and "[" in text and "]" in text)
            or len(digits) >= 8
            or lowered.startswith("nh")
            or lowered.startswith("nh")
            or lowered.startswith("пн")
        )

    def _score_header_prefix(self, lines: Sequence[MappedOcrLine]) -> float:
        if not lines:
            return 0.0

        line_count = len(lines)
        alpha_count = sum(sum(1 for character in line.text if character.isalpha()) for line in lines)
        short_penalty = sum(1 for line in lines if len((line.text or "").strip()) < 5) * 6
        digit_penalty = sum(1 for line in lines if self._mostly_digits(line.text)) * 8
        return line_count * 10 + alpha_count * 0.45 - short_penalty - digit_penalty

    def _mostly_digits(self, text: str) -> bool:
        compact = (text or "").replace(" ", "")
        if not compact:
            return False
        digit_count = sum(1 for character in compact if character.isdigit())
        return digit_count >= max(6, len(compact) * 0.7)

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

    def _normalize_header_geometry(self, lines: Sequence[MappedOcrLine], anchor_top: float | None = None) -> list[MappedOcrLine]:
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
