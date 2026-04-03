from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class MappedOcrLine:
    text: str
    confidence: float | None
    order: int
    bbox: tuple[tuple[float, float], ...] | None

    def to_response(self) -> dict:
        return {
            "text": self.text,
            "confidence": self.confidence,
            "order": self.order,
            "bbox": [[x, y] for x, y in self.bbox] if self.bbox else None,
        }


@dataclass(frozen=True)
class _CandidateLine:
    text: str
    confidence: float | None
    bbox: tuple[tuple[float, float], ...] | None
    top: float
    left: float
    height: float


class PaddleOcrResponseMapper:
    def map_page_lines(self, ocr_result, order_offset: int = 0) -> list[MappedOcrLine]:
        ordered_candidates = self._order_candidates(self._extract_candidates(ocr_result))
        return [
            MappedOcrLine(
                text=candidate.text,
                confidence=candidate.confidence,
                order=order_offset + index,
                bbox=candidate.bbox,
            )
            for index, candidate in enumerate(ordered_candidates)
        ]

    def _extract_candidates(self, ocr_result) -> list[_CandidateLine]:
        candidates: list[_CandidateLine] = []
        for ocr_page in ocr_result or []:
            for entry in ocr_page or []:
                candidate = self._candidate_from_entry(entry)
                if candidate is not None:
                    candidates.append(candidate)
        return candidates

    def _candidate_from_entry(self, entry) -> _CandidateLine | None:
        if not entry or len(entry) < 2:
            return None

        payload = entry[1] or ()
        if len(payload) < 2:
            return None

        text, confidence = payload[0], payload[1]
        text = (text or "").strip()
        if not text:
            return None

        bbox = self._bbox_points(entry[0])
        top = min((point[1] for point in bbox), default=0.0)
        left = min((point[0] for point in bbox), default=0.0)
        bottom = max((point[1] for point in bbox), default=top)
        height = max(bottom - top, 1.0)

        return _CandidateLine(
            text=text,
            confidence=round(float(confidence), 4) if confidence is not None else None,
            bbox=bbox or None,
            top=top,
            left=left,
            height=height,
        )

    def _bbox_points(self, raw_bbox) -> tuple[tuple[float, float], ...]:
        points: list[tuple[float, float]] = []
        for point in raw_bbox or []:
            if not point or len(point) < 2:
                continue
            points.append((float(point[0]), float(point[1])))
        return tuple(points)

    def _order_candidates(self, candidates: list[_CandidateLine]) -> list[_CandidateLine]:
        if not candidates:
            return []

        top_sorted = sorted(candidates, key=lambda candidate: (candidate.top, candidate.left))
        rows: list[list[_CandidateLine]] = []

        for candidate in top_sorted:
            if not rows:
                rows.append([candidate])
                continue

            current_row = rows[-1]
            row_top = min(item.top for item in current_row)
            tolerance = max(max(item.height for item in current_row), candidate.height) * 0.6

            if candidate.top <= row_top + tolerance:
                current_row.append(candidate)
            else:
                rows.append([candidate])

        ordered: list[_CandidateLine] = []
        for row in rows:
            ordered.extend(sorted(row, key=lambda candidate: candidate.left))

        return ordered
