from __future__ import annotations

import io
import os

import fitz
import numpy as np
from flask import Flask, current_app, jsonify, request
from PIL import Image

from ocr_engine import PaddleOcrEngine
from preprocessing import ReceiptImagePreprocessor
from response_mapping import PaddleOcrResponseMapper


PADDLE_OCR_LANG = os.getenv("PADDLE_OCR_LANG", "cyrillic")
PADDLE_OCR_USE_ANGLE_CLS = os.getenv("PADDLE_OCR_USE_ANGLE_CLS", "false").lower() == "true"
PADDLE_OCR_PREPROCESSING_ENABLED = os.getenv("PADDLE_OCR_PREPROCESSING_ENABLED", "true").lower() == "true"
PADDLE_OCR_TARGET_LONG_EDGE = int(os.getenv("PADDLE_OCR_TARGET_LONG_EDGE", "1600"))
PADDLE_OCR_SKIP_WARMUP = os.getenv("PADDLE_OCR_SKIP_WARMUP", "false").lower() == "true"


def create_app(
    ocr_engine: PaddleOcrEngine | None = None,
    image_preprocessor: ReceiptImagePreprocessor | None = None,
) -> Flask:
    application = Flask(__name__)
    application.config["OCR_ENGINE"] = ocr_engine or PaddleOcrEngine(
        language=PADDLE_OCR_LANG,
        use_angle_cls=PADDLE_OCR_USE_ANGLE_CLS,
    )
    application.config["IMAGE_PREPROCESSOR"] = image_preprocessor or ReceiptImagePreprocessor(
        enabled=PADDLE_OCR_PREPROCESSING_ENABLED,
        target_long_edge=PADDLE_OCR_TARGET_LONG_EDGE,
    )
    application.config["OCR_RESPONSE_MAPPER"] = PaddleOcrResponseMapper()

    if not PADDLE_OCR_SKIP_WARMUP:
        application.config["OCR_ENGINE"].warm_up()

    @application.get("/health")
    def health():
        return jsonify({"status": "UP", "backend": "PaddleOCR"})

    @application.post("/ocr")
    def ocr():
        file = request.files.get("file")
        if file is None or file.filename == "":
            return jsonify({"message": "file is required"}), 400

        content = file.read()
        if not content:
            return jsonify({"message": "file is empty"}), 400

        content_type = (file.content_type or "").lower()
        preprocess_enabled = _resolve_preprocess_override(request)

        try:
            processed_pages = _processed_page_images(content, content_type, preprocess_enabled)
            lines = _extract_lines(processed_pages)
            raw_text = "\n".join(line["text"] for line in lines)
            return jsonify(
                {
                    "rawText": raw_text,
                    "lines": lines,
                    "preprocessingApplied": any(page.applied for page in processed_pages),
                    "pages": [page.to_response(index) for index, page in enumerate(processed_pages)],
                }
            )
        except Exception as exception:
            return jsonify({"message": f"PaddleOCR extraction failed: {exception}"}), 500

    return application


def _resolve_preprocess_override(http_request) -> bool | None:
    raw_value = http_request.args.get("preprocess")
    if raw_value is None:
        raw_value = http_request.form.get("preprocess")

    if raw_value is None:
        return None

    return raw_value.strip().lower() in {"1", "true", "yes", "on"}


def _page_images(content: bytes, content_type: str) -> list[Image.Image]:
    if content_type == "application/pdf":
        document = fitz.open(stream=content, filetype="pdf")
        try:
            images = []
            for page in document:
                pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
                images.append(Image.open(io.BytesIO(pixmap.tobytes("png"))).convert("RGB"))
            return images
        finally:
            document.close()

    return [Image.open(io.BytesIO(content)).convert("RGB")]


def _processed_page_images(content: bytes, content_type: str, preprocess_enabled: bool | None):
    preprocessor: ReceiptImagePreprocessor = current_app.config["IMAGE_PREPROCESSOR"]
    return [
        preprocessor.preprocess(image, enabled_override=preprocess_enabled)
        for image in _page_images(content, content_type)
    ]


def _extract_lines(processed_pages) -> list[dict]:
    ocr_engine = current_app.config["OCR_ENGINE"]
    response_mapper: PaddleOcrResponseMapper = current_app.config["OCR_RESPONSE_MAPPER"]
    lines = []
    order_offset = 0
    for page in processed_pages:
        result = ocr_engine.extract_lines(np.array(page.image))
        page_lines = response_mapper.map_page_lines(result, order_offset=order_offset)
        lines.extend(line.to_response() for line in page_lines)
        order_offset += len(page_lines)
    return lines


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8083")))
