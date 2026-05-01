from __future__ import annotations

import io
import os

import fitz
import numpy as np
from flask import Flask, current_app, jsonify, request
from PIL import Image

from header_rescue import HeaderBlockRescue
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
    application.config["HEADER_BLOCK_RESCUE"] = HeaderBlockRescue()

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
            page_images = _page_images(content, content_type)
            processed_pages = _processed_page_images(page_images, preprocess_enabled)
            raw_engine_pages = _raw_engine_pages(processed_pages, profile_override, language_override)
            mapped_lines, header_rescue_results = _extract_lines(
                page_images,
                processed_pages,
                raw_engine_pages,
                profile_override,
                language_override,
            )
            lines = [line.to_response() for line in mapped_lines]
            raw_text = "\n".join(line["text"] for line in lines)
            engine_config = current_app.config["OCR_ENGINE"].describe(profile_override, language_override)
            payload = {
                "rawText": raw_text,
                "lines": lines,
                "profile": profile_override or current_app.config["OCR_DEFAULT_PROFILE"],
                "engine": _engine_snapshot(engine_config),
                "preprocessing": _preprocessing_snapshot(processed_pages),
                "preprocessingApplied": any(page.applied for page in processed_pages),
                "headerRescueApplied": any(result.applied for result in header_rescue_results),
                "pages": [
                    {
                        **page.to_response(index),
                        "headerRescueApplied": header_rescue_results[index].applied,
                        "headerRescueStrategy": header_rescue_results[index].strategy if header_rescue_results[index].applied else None,
                    }
                    for index, page in enumerate(processed_pages)
                ],
            }

            if debug_enabled:
                payload["diagnostics"] = _build_diagnostics(
                    page_images,
                    processed_pages,
                    raw_engine_pages,
                    profile_override,
                    language_override,
                )

            return jsonify(payload)
        except Exception as exception:
            return jsonify({"message": f"PaddleOCR extraction failed: {exception}"}), 500

    return application


def _engine_snapshot(engine_config: dict) -> dict:
    model_names = [
        value
        for value in (
            engine_config.get("detModelName"),
            engine_config.get("recModelName"),
            engine_config.get("clsModelName"),
        )
        if value
    ]
    return {
        "name": "PaddleOCR",
        "version": engine_config.get("ocrVersion"),
        "model": "+".join(model_names) if model_names else None,
        "language": engine_config.get("language"),
        "profile": engine_config.get("profile"),
        "config": engine_config,
    }


def _preprocessing_snapshot(processed_pages) -> dict:
    steps = []
    warnings = []
    profiles = []
    for page in processed_pages:
        profiles.append(page.strategy)
        for step in page.steps_applied:
            if step not in steps:
                steps.append(step)
        if page.quality_before and page.quality_before.laplacian_variance < 80:
            warnings.append("possible_blur")
        if page.quality_before and page.quality_before.contrast_std < 35:
            warnings.append("low_contrast")

    unique_profiles = []
    for profile in profiles:
        if profile and profile not in unique_profiles:
            unique_profiles.append(profile)

    return {
        "applied": any(page.applied for page in processed_pages),
        "profile": "+".join(unique_profiles) if unique_profiles else None,
        "steps": steps,
        "warnings": sorted(set(warnings)),
    }


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


def _processed_page_images(page_images: list[Image.Image], preprocess_enabled: bool | None):
    preprocessor: ReceiptImagePreprocessor = current_app.config["IMAGE_PREPROCESSOR"]
    return [
        preprocessor.preprocess(image, enabled_override=preprocess_enabled)
        for image in page_images
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


def _extract_lines(
    page_images: list[Image.Image],
    processed_pages,
    raw_engine_pages,
    profile_override: str | None = None,
    language_override: str | None = None,
):
    ocr_engine: PaddleOcrEngine = current_app.config["OCR_ENGINE"]
    response_mapper: PaddleOcrResponseMapper = current_app.config["OCR_RESPONSE_MAPPER"]
    header_block_rescue: HeaderBlockRescue = current_app.config["HEADER_BLOCK_RESCUE"]
    lines = []
    header_rescue_results = []
    order_offset = 0
    for page_index, raw_engine_page in enumerate(raw_engine_pages):
        page_lines = response_mapper.map_page_lines(raw_engine_page)
        header_rescue_result = header_block_rescue.rescue_page_lines(
            page_index=page_index,
            original_image=page_images[page_index],
            processed_page=processed_pages[page_index],
            existing_lines=page_lines,
            ocr_engine=ocr_engine,
            response_mapper=response_mapper,
            profile_override=profile_override,
            language_override=language_override,
        )
        page_lines = [
            type(line)(
                text=line.text,
                confidence=line.confidence,
                order=order_offset + index,
                bbox=line.bbox,
            )
            for index, line in enumerate(header_rescue_result.lines)
        ]
        lines.extend(page_lines)
        order_offset += len(page_lines)
        header_rescue_results.append(header_rescue_result)
    return lines, header_rescue_results


def _build_diagnostics(
    page_images: list[Image.Image],
    processed_pages,
    raw_engine_pages,
    profile_override: str | None,
    language_override: str | None,
) -> dict:
    ocr_engine: PaddleOcrEngine = current_app.config["OCR_ENGINE"]
    response_mapper: PaddleOcrResponseMapper = current_app.config["OCR_RESPONSE_MAPPER"]
    raw_engine_lines = []

    for page_index, raw_engine_page in enumerate(raw_engine_pages):
        raw_lines = response_mapper.map_raw_engine_lines(raw_engine_page)
        for line in raw_lines:
            raw_engine_lines.append({"pageIndex": page_index, **line})

    mapped_lines, header_rescue_results = _extract_lines(
        page_images,
        processed_pages,
        raw_engine_pages,
        profile_override,
        language_override,
    )
    return {
        "engineConfig": ocr_engine.describe(profile_override, language_override),
        "rawEngineLines": raw_engine_lines,
        "rawEngineText": "\n".join(line["text"] for line in raw_engine_lines),
        "mappedLines": [line.to_response() for line in mapped_lines],
        "mappedRawText": "\n".join(line.text for line in mapped_lines),
        "headerRescue": [result.to_response(index) for index, result in enumerate(header_rescue_results)],
    }


def _resolve_default_profile_name() -> str:
    if PADDLE_OCR_LANG and PADDLE_OCR_LANG in OCR_PROFILES:
        return PADDLE_OCR_LANG
    return resolve_profile(PADDLE_OCR_PROFILE).name


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8083")))
