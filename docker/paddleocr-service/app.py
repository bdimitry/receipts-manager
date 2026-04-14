from __future__ import annotations

import io
import os

import fitz
import numpy as np
from flask import Flask, current_app, jsonify, request
from PIL import Image

from normalization import ReceiptOcrLineNormalizer
from ocr_engine import PaddleOcrEngine
from preprocessing import ReceiptImagePreprocessor
from profiles import DEFAULT_OCR_PROFILE, OCR_PROFILES, available_profiles, resolve_profile
from response_mapping import PaddleOcrResponseMapper


PADDLE_OCR_PROFILE = os.getenv("PADDLE_OCR_PROFILE", DEFAULT_OCR_PROFILE)
PADDLE_OCR_LANG = os.getenv("PADDLE_OCR_LANG", "").strip()
PADDLE_OCR_PREPROCESSING_ENABLED = os.getenv("PADDLE_OCR_PREPROCESSING_ENABLED", "true").lower() == "true"
PADDLE_OCR_TARGET_LONG_EDGE = int(os.getenv("PADDLE_OCR_TARGET_LONG_EDGE", "1600"))
PADDLE_OCR_SKIP_WARMUP = os.getenv("PADDLE_OCR_SKIP_WARMUP", "false").lower() == "true"


def create_app(
    ocr_engine: PaddleOcrEngine | None = None,
    image_preprocessor: ReceiptImagePreprocessor | None = None,
    line_normalizer: ReceiptOcrLineNormalizer | None = None,
) -> Flask:
    application = Flask(__name__)
    default_profile_name = _resolve_default_profile_name()
    application.config["OCR_ENGINE"] = ocr_engine or PaddleOcrEngine(profile_name=default_profile_name)
    application.config["OCR_DEFAULT_PROFILE"] = default_profile_name
    application.config["IMAGE_PREPROCESSOR"] = image_preprocessor or ReceiptImagePreprocessor(
        enabled=PADDLE_OCR_PREPROCESSING_ENABLED,
        target_long_edge=PADDLE_OCR_TARGET_LONG_EDGE,
    )
    application.config["OCR_RESPONSE_MAPPER"] = PaddleOcrResponseMapper()
    application.config["OCR_LINE_NORMALIZER"] = line_normalizer or ReceiptOcrLineNormalizer()

    if not PADDLE_OCR_SKIP_WARMUP:
        application.config["OCR_ENGINE"].warm_up()

    @application.get("/health")
    def health():
        return jsonify({"status": "UP", "backend": "PaddleOCR"})

    @application.get("/diagnostics/config")
    def diagnostics_config():
        ocr_engine: PaddleOcrEngine = current_app.config["OCR_ENGINE"]
        return jsonify(
            {
                "backend": "PaddleOCR",
                "activeProfile": current_app.config["OCR_DEFAULT_PROFILE"],
                "defaultConfig": ocr_engine.describe(),
                "availableProfiles": [profile.to_response() for profile in available_profiles()],
                "preprocessingEnabled": PADDLE_OCR_PREPROCESSING_ENABLED,
                "targetLongEdge": PADDLE_OCR_TARGET_LONG_EDGE,
            }
        )

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
        debug_enabled = _resolve_boolean_flag(request, "debug")
        profile_override = _resolve_profile_override(request)
        language_override = _resolve_language_override(request)

        try:
            processed_pages = _processed_page_images(content, content_type, preprocess_enabled)
            raw_engine_pages = _raw_engine_pages(processed_pages, profile_override, language_override)
            mapped_lines = _extract_lines(raw_engine_pages)
            normalized_lines = _normalize_lines(mapped_lines)
            lines = [line.to_response() for line in mapped_lines]
            raw_text = "\n".join(line["text"] for line in lines)
            payload = {
                "rawText": raw_text,
                "lines": lines,
                "normalizedLines": [line.to_response() for line in normalized_lines],
                "profile": profile_override or current_app.config["OCR_DEFAULT_PROFILE"],
                "preprocessingApplied": any(page.applied for page in processed_pages),
                "pages": [page.to_response(index) for index, page in enumerate(processed_pages)],
            }

            if debug_enabled:
                payload["diagnostics"] = _build_diagnostics(raw_engine_pages, profile_override, language_override)

            return jsonify(payload)
        except Exception as exception:
            return jsonify({"message": f"PaddleOCR extraction failed: {exception}"}), 500

    return application


def _resolve_boolean_flag(http_request, parameter_name: str) -> bool | None:
    raw_value = http_request.args.get(parameter_name)
    if raw_value is None:
        raw_value = http_request.form.get(parameter_name)

    if raw_value is None:
        return None

    return raw_value.strip().lower() in {"1", "true", "yes", "on"}


def _resolve_preprocess_override(http_request) -> bool | None:
    return _resolve_boolean_flag(http_request, "preprocess")


def _resolve_profile_override(http_request) -> str | None:
    raw_value = http_request.args.get("profile")
    if raw_value is None:
        raw_value = http_request.form.get("profile")
    if raw_value is None:
        return None
    normalized = raw_value.strip().lower()
    if not normalized:
        return None
    try:
        resolve_profile(normalized)
        return normalized
    except ValueError:
        return None


def _resolve_language_override(http_request) -> str | None:
    raw_value = http_request.args.get("lang")
    if raw_value is None:
        raw_value = http_request.form.get("lang")

    if raw_value is None:
        return None

    normalized = raw_value.strip()
    return normalized or None


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


def _raw_engine_pages(processed_pages, profile_override: str | None, language_override: str | None):
    ocr_engine = current_app.config["OCR_ENGINE"]
    return [
        ocr_engine.extract_lines(
            np.array(page.image),
            profile_override=profile_override,
            language_override=language_override,
        )
        for page in processed_pages
    ]


def _extract_lines(raw_engine_pages):
    response_mapper: PaddleOcrResponseMapper = current_app.config["OCR_RESPONSE_MAPPER"]
    lines = []
    order_offset = 0
    for raw_engine_page in raw_engine_pages:
        page_lines = response_mapper.map_page_lines(raw_engine_page, order_offset=order_offset)
        lines.extend(page_lines)
        order_offset += len(page_lines)
    return lines


def _normalize_lines(mapped_lines):
    line_normalizer: ReceiptOcrLineNormalizer = current_app.config["OCR_LINE_NORMALIZER"]
    return line_normalizer.normalize_lines(mapped_lines)


def _build_diagnostics(raw_engine_pages, profile_override: str | None, language_override: str | None) -> dict:
    ocr_engine: PaddleOcrEngine = current_app.config["OCR_ENGINE"]
    response_mapper: PaddleOcrResponseMapper = current_app.config["OCR_RESPONSE_MAPPER"]
    raw_engine_lines = []

    for page_index, raw_engine_page in enumerate(raw_engine_pages):
        raw_lines = response_mapper.map_raw_engine_lines(raw_engine_page)
        for line in raw_lines:
            raw_engine_lines.append({"pageIndex": page_index, **line})

    mapped_lines = _extract_lines(raw_engine_pages)
    normalized_lines = _normalize_lines(mapped_lines)
    return {
        "engineConfig": ocr_engine.describe(profile_override, language_override),
        "rawEngineLines": raw_engine_lines,
        "rawEngineText": "\n".join(line["text"] for line in raw_engine_lines),
        "normalizedLines": [line.to_response() for line in normalized_lines],
        "normalizedText": "\n".join(
            line.normalized_text for line in normalized_lines if not line.ignored and line.normalized_text
        ),
    }


def _resolve_default_profile_name() -> str:
    if PADDLE_OCR_LANG and PADDLE_OCR_LANG in OCR_PROFILES:
        return PADDLE_OCR_LANG
    return resolve_profile(PADDLE_OCR_PROFILE).name


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8083")))
