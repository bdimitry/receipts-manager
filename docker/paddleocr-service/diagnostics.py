from __future__ import annotations

import argparse
import json

import cv2
import numpy as np
from PIL import Image

from ocr_engine import PaddleOcrEngine
from preprocessing import ReceiptImagePreprocessor
from response_mapping import PaddleOcrResponseMapper


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local PaddleOCR quality diagnostics on generated receipt-like samples.")
    parser.add_argument("--langs", nargs="+", default=["cyrillic", "en", "latin"], help="PaddleOCR languages to compare.")
    parser.add_argument("--preprocess", choices=["true", "false"], default="true", help="Whether to enable preprocessing.")
    args = parser.parse_args()

    preprocessor = ReceiptImagePreprocessor(enabled=args.preprocess == "true", target_long_edge=1600)
    mapper = PaddleOcrResponseMapper()

    for sample_name, image in [
        ("synthetic-items", synthetic_receipt_items_sample()),
        ("synthetic-labels", synthetic_receipt_labels_sample()),
    ]:
        print(f"=== SAMPLE {sample_name} ===")
        processed = preprocessor.preprocess(image)
        print(
            json.dumps(
                {
                    "preprocessingApplied": processed.applied,
                    "stepsApplied": list(processed.steps_applied),
                    "sizeBefore": {"width": processed.size_before.width, "height": processed.size_before.height},
                    "sizeAfter": {"width": processed.size_after.width, "height": processed.size_after.height},
                },
                ensure_ascii=False,
            )
        )

        for lang in args.langs:
            engine = PaddleOcrEngine(language=lang, use_angle_cls=False)
            raw_result = engine.extract_lines(np.array(processed.image))
            raw_lines = mapper.map_raw_engine_lines(raw_result)
            mapped_lines = [line.to_response() for line in mapper.map_page_lines(raw_result)]

            payload = {
                "language": lang,
                "engineConfig": engine.describe(),
                "rawEngineText": "\n".join(line["text"] for line in raw_lines),
                "rawEngineLines": raw_lines,
                "mappedLines": mapped_lines,
                "mappedRawText": "\n".join(line["text"] for line in mapped_lines),
            }
            print(json.dumps(payload, ensure_ascii=False))


def synthetic_receipt_items_sample() -> Image.Image:
    canvas = np.full((1600, 1200, 3), 172, dtype=np.uint8)
    cv2.rectangle(canvas, (260, 120), (880, 1480), (245, 245, 245), thickness=-1)
    cv2.rectangle(canvas, (260, 120), (880, 1480), (70, 70, 70), thickness=3)

    lines = [
        "SILPO MARKET",
        "MILK 42.50",
        "BREAD 28.90",
        "APPLES 61.20",
        "TOTAL 132.60",
    ]
    return _finish_receipt_canvas(canvas, lines)


def synthetic_receipt_labels_sample() -> Image.Image:
    canvas = np.full((1600, 1200, 3), 178, dtype=np.uint8)
    cv2.rectangle(canvas, (250, 120), (900, 1480), (248, 248, 246), thickness=-1)
    cv2.rectangle(canvas, (250, 120), (900, 1480), (85, 85, 85), thickness=3)

    lines = [
        "RECEIPT",
        "Date 2026-04-06",
        "Balance 480.00",
        "Coffee 120.50",
        "Croissant 89.90",
        "TOTAL 210.40",
    ]
    return _finish_receipt_canvas(canvas, lines)


def _finish_receipt_canvas(canvas: np.ndarray, lines: list[str]) -> Image.Image:
    y = 250
    for line in lines:
        cv2.putText(canvas, line, (315, y), cv2.FONT_HERSHEY_SIMPLEX, 1.15, (30, 30, 30), 3, cv2.LINE_AA)
        y += 150

    low_contrast = cv2.convertScaleAbs(canvas, alpha=0.86, beta=10)
    noise = np.random.default_rng(42).normal(0, 6, low_contrast.shape).astype(np.int16)
    noisy = np.clip(low_contrast.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    blurred = cv2.GaussianBlur(noisy, (5, 5), 0)

    height, width = blurred.shape[:2]
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), 6, 1.0)
    rotated = cv2.warpAffine(
        blurred,
        matrix,
        (width, height),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(150, 165, 155),
    )
    return Image.fromarray(cv2.cvtColor(rotated, cv2.COLOR_BGR2RGB))


if __name__ == "__main__":
    main()
