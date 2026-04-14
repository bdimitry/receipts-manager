from __future__ import annotations

from dataclasses import dataclass
import re

from response_mapping import MappedOcrLine


CYRILLIC_RANGE = r"\u0400-\u04FF\u0500-\u052F"
LETTER_RE = re.compile(rf"[A-Za-z{CYRILLIC_RANGE}]")
AMOUNT_RE = re.compile(r"\b\d+[.,]\d{2}\b")
LONG_DIGITS_RE = re.compile(r"\d{10,}")
WORD_SEPARATOR_RE = re.compile(rf"(?<=[A-Za-z{CYRILLIC_RANGE}])[.:;|](?=[A-Za-z{CYRILLIC_RANGE}])")
PUNCT_SPACING_RE = re.compile(r"\s*([=:])\s*")
MULTI_PUNCT_RE = re.compile(r"([.,:;=\-]){2,}")
MULTIPLIER_RE = re.compile(r"(?:(?<=\s)|(?<=\d)|(?<=\b))[x\u0445\u0425\u00D7](?:(?=\s)|(?=\d)|(?=\b))")
SERVICE_HINT_RE = re.compile(
    r"("
    r"receipt|cash|date|document|thank|total|subtotal|balance|sale|discount|special|sum|"
    r"\u0434\u0430\u0442\u0430|"
    r"\u0447\u0435\u043a|"
    r"\u043a\u0430\u0441\u0430|"
    r"\u043a\u0430\u0441\u0441\u0430|"
    r"\u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442|"
    r"\u0441\u0443\u043c\u0430|"
    r"\u0440\u0430\u0437\u043e\u043c|"
    r"\u0446\u0456\u043d\u0430|"
    r"\u0441\u043f\u0435\u0446"
    r")",
    re.IGNORECASE,
)
HEADER_HINT_RE = re.compile(
    r"("
    r"receipt|cash|date|document|thank|store|market|"
    r"\u0434\u0430\u0442\u0430|"
    r"\u0447\u0435\u043a|"
    r"\u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442|"
    r"\u043c\u0430\u0433\u0430\u0437\u0438\u043d|"
    r"\u043c\u0430\u0440\u043a\u0435\u0442"
    r")",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class NormalizedOcrLine:
    original_text: str
    normalized_text: str
    order: int
    confidence: float | None
    bbox: tuple[tuple[float, float], ...] | None
    tags: tuple[str, ...]
    ignored: bool

    def to_response(self) -> dict:
        return {
            "originalText": self.original_text,
            "normalizedText": self.normalized_text,
            "order": self.order,
            "confidence": self.confidence,
            "bbox": [[x, y] for x, y in self.bbox] if self.bbox else None,
            "tags": list(self.tags),
            "ignored": self.ignored,
        }


class ReceiptOcrLineNormalizer:
    def normalize_lines(self, lines: list[MappedOcrLine]) -> list[NormalizedOcrLine]:
        return [self._normalize_line(line, line.order, len(lines)) for line in lines]

    def _normalize_line(self, line: MappedOcrLine, order: int, total_lines: int) -> NormalizedOcrLine:
        normalized_text = self._normalize_text(line.text)
        tags = self._classify(normalized_text, order, total_lines)
        ignored = "noise" in tags or "barcode_like" in tags
        return NormalizedOcrLine(
            original_text=line.text,
            normalized_text=normalized_text,
            order=line.order,
            confidence=line.confidence,
            bbox=line.bbox,
            tags=tags,
            ignored=ignored,
        )

    def _normalize_text(self, text: str) -> str:
        normalized = (text or "").strip()
        normalized = re.sub(r"\s+", " ", normalized)
        normalized = WORD_SEPARATOR_RE.sub(" ", normalized)
        normalized = MULTIPLIER_RE.sub("x", normalized)
        normalized = MULTI_PUNCT_RE.sub(lambda match: match.group(1), normalized)
        normalized = normalized.replace(" ,", ",").replace(" .", ".").replace(" :", ":").replace(" ;", ";")
        normalized = PUNCT_SPACING_RE.sub(lambda match: f"{match.group(1)} ", normalized)
        normalized = self._normalize_amount_suffix(normalized)
        normalized = self._strip_line_edges(normalized)
        normalized = re.sub(r"\s+", " ", normalized).strip()
        return normalized

    def _normalize_amount_suffix(self, value: str) -> str:
        normalized = re.sub(r"(?<=\d)\s*[,.;:]+\s*$", "", value)
        normalized = re.sub(r"\s+=\s*$", "", normalized)
        return normalized

    def _strip_line_edges(self, value: str) -> str:
        normalized = value.strip()
        normalized = re.sub(r"^[\s`'\".,;:|=_-]+", "", normalized)
        normalized = re.sub(r"[\s`'\";:|=_-]+$", "", normalized)
        return normalized

    def _classify(self, normalized_text: str, order: int, total_lines: int) -> tuple[str, ...]:
        if not normalized_text:
            return ("noise",)

        tags: list[str] = []
        alpha_count = len(LETTER_RE.findall(normalized_text))
        digit_count = len(re.findall(r"\d", normalized_text))
        has_amount = bool(AMOUNT_RE.search(normalized_text))
        has_long_digits = bool(LONG_DIGITS_RE.search(normalized_text))
        compact = self._compact(normalized_text)

        if alpha_count == 0 and digit_count < 3:
            tags.append("noise")

        if len(normalized_text) <= 2 and not has_amount:
            tags.append("noise")

        if has_long_digits and alpha_count < 8:
            tags.append("barcode_like")

        if compact and len(compact) > 24 and digit_count >= max(alpha_count * 2, 12):
            tags.append("barcode_like")

        if has_amount:
            tags.append("price_like")

        if SERVICE_HINT_RE.search(normalized_text):
            tags.append("service_like")

        if order < max(3, min(total_lines, 4)) and (HEADER_HINT_RE.search(normalized_text) or (not has_amount and alpha_count >= 3)):
            tags.append("header_like")

        if not any(tag in tags for tag in ("noise", "barcode_like", "header_like", "service_like")):
            tags.append("content_like")
        elif "price_like" in tags and "barcode_like" not in tags and "noise" not in tags:
            tags.append("content_like")

        return tuple(dict.fromkeys(tags))

    def _compact(self, value: str) -> str:
        return re.sub(rf"[^0-9A-Za-z{CYRILLIC_RANGE}]+", "", value)
