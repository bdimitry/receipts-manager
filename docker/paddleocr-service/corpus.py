from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image


@dataclass(frozen=True)
class DiagnosticCase:
    name: str
    category: str
    expected_labels: tuple[str, ...]
    expected_numbers: tuple[str, ...]
    image: Image.Image

    def to_response(self) -> dict:
        return {
            "name": self.name,
            "category": self.category,
            "expectedLabels": list(self.expected_labels),
            "expectedNumbers": list(self.expected_numbers),
        }


def diagnostic_corpus() -> list[DiagnosticCase]:
    return [
        DiagnosticCase(
            name="clean-english-receipt",
            category="clean-receipt",
            expected_labels=("RECEIPT", "Date", "Balance", "Coffee", "Croissant", "TOTAL"),
            expected_numbers=("2026-04-06", "480.00", "120.50", "89.90", "210.40"),
            image=_english_receipt_sample(),
        ),
        DiagnosticCase(
            name="cyrillic-transaction",
            category="cyrillic-transaction",
            expected_labels=("ЧЕК", "Дата", "Баланс", "Кава", "Круасан", "РАЗОМ"),
            expected_numbers=("2026-04-06", "480.00", "120.50", "89.90", "210.40"),
            image=_cyrillic_transaction_sample(),
        ),
        DiagnosticCase(
            name="mixed-script-receipt",
            category="mixed-script",
            expected_labels=("SILPO", "Маркет", "Date", "Сума", "TOTAL"),
            expected_numbers=("2026-04-06", "132.60", "42.50", "28.90", "61.20"),
            image=_mixed_script_receipt_sample(),
        ),
        DiagnosticCase(
            name="bank-like-document",
            category="bank-transaction",
            expected_labels=("UKRSIBBANK", "Платіж", "Дата", "Одержувач", "Сума"),
            expected_numbers=("2026-04-06", "2480.75", "UA123456789012345678901234567"),
            image=_bank_like_document_sample(),
        ),
        DiagnosticCase(
            name="pdf-rendered-payment-page",
            category="pdf-rendered-page",
            expected_labels=("PAYMENT ORDER", "Date", "Account", "Reference", "Amount"),
            expected_numbers=("2026-04-06", "1840.25", "UA903052990000026007200123456"),
            image=_pdf_rendered_payment_page_sample(),
        ),
    ]


def _english_receipt_sample() -> Image.Image:
    lines = [
        "RECEIPT",
        "Date 2026-04-06",
        "Balance 480.00",
        "Coffee 120.50",
        "Croissant 89.90",
        "TOTAL 210.40",
    ]
    return _render_document(
        lines,
        background=(180, 188, 176),
        paper=(248, 248, 246),
        border=(90, 90, 90),
        rotation=2.5,
        noise=4,
        blur=3,
    )


def _cyrillic_transaction_sample() -> Image.Image:
    lines = [
        "ЧЕК",
        "Дата 2026-04-06",
        "Баланс 480.00",
        "Кава 120.50",
        "Круасан 89.90",
        "РАЗОМ 210.40",
    ]
    return _render_document(
        lines,
        background=(172, 180, 169),
        paper=(245, 245, 243),
        border=(95, 95, 95),
        rotation=-3.0,
        noise=5,
        blur=5,
    )


def _mixed_script_receipt_sample() -> Image.Image:
    lines = [
        "SILPO Маркет",
        "Date 2026-04-06",
        "Milk 42.50",
        "Хліб 28.90",
        "Яблука 61.20",
        "TOTAL Сума 132.60",
    ]
    return _render_document(
        lines,
        background=(165, 176, 164),
        paper=(244, 244, 241),
        border=(80, 80, 80),
        rotation=5.0,
        noise=8,
        blur=5,
    )


def _bank_like_document_sample() -> Image.Image:
    lines = [
        "UKRSIBBANK",
        "Платіж 2026-04-06",
        "Одержувач TOV SILPO-FOOD",
        "Рахунок UA123456789012345678901234567",
        "Сума 2480.75",
    ]
    return _render_document(
        lines,
        background=(168, 174, 178),
        paper=(242, 244, 246),
        border=(88, 88, 88),
        rotation=-4.5,
        noise=9,
        blur=5,
        font_scale=1.0,
        line_gap=145,
    )


def _pdf_rendered_payment_page_sample() -> Image.Image:
    lines = [
        "PAYMENT ORDER",
        "Date 2026-04-06",
        "Account UA903052990000026007200123456",
        "Reference Device repair",
        "Amount 1840.25",
    ]
    return _render_document(
        lines,
        background=(214, 216, 218),
        paper=(250, 250, 249),
        border=(105, 105, 105),
        rotation=0.8,
        noise=2,
        blur=3,
        font_scale=1.0,
        line_gap=155,
    )


def _render_document(
    lines: list[str],
    background: tuple[int, int, int],
    paper: tuple[int, int, int],
    border: tuple[int, int, int],
    rotation: float,
    noise: int,
    blur: int,
    font_scale: float = 1.1,
    line_gap: int = 150,
) -> Image.Image:
    canvas = np.full((1600, 1200, 3), background, dtype=np.uint8)
    cv2.rectangle(canvas, (250, 120), (920, 1480), paper, thickness=-1)
    cv2.rectangle(canvas, (250, 120), (920, 1480), border, thickness=3)

    y = 250
    for line in lines:
        cv2.putText(canvas, line, (300, y), cv2.FONT_HERSHEY_SIMPLEX, font_scale, (28, 28, 28), 3, cv2.LINE_AA)
        y += line_gap

    low_contrast = cv2.convertScaleAbs(canvas, alpha=0.88, beta=8)
    rng = np.random.default_rng(42)
    noise_layer = rng.normal(0, noise, low_contrast.shape).astype(np.int16)
    noisy = np.clip(low_contrast.astype(np.int16) + noise_layer, 0, 255).astype(np.uint8)
    blurred = cv2.GaussianBlur(noisy, (blur, blur), 0)

    height, width = blurred.shape[:2]
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), rotation, 1.0)
    rotated = cv2.warpAffine(
        blurred,
        matrix,
        (width, height),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=background,
    )
    return Image.fromarray(cv2.cvtColor(rotated, cv2.COLOR_BGR2RGB))
