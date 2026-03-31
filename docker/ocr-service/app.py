import io
import os

import fitz
import pytesseract
from flask import Flask, jsonify, request
from PIL import Image
from PIL import ImageOps


app = Flask(__name__)
OCR_LANGUAGES = os.getenv("OCR_LANGUAGES", "ukr+rus+eng")
OCR_TESSERACT_CONFIG = os.getenv("OCR_TESSERACT_CONFIG", "--oem 3 --psm 6")


def _prepare_image(image):
    grayscale = image.convert("L")
    contrast = ImageOps.autocontrast(grayscale)
    return contrast.point(lambda pixel: 0 if pixel < 180 else 255, mode="1")


def _extract_from_pdf(content):
    document = fitz.open(stream=content, filetype="pdf")
    chunks = []
    for page in document:
        pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
        image = Image.open(io.BytesIO(pixmap.tobytes("png")))
        chunks.append(
            pytesseract.image_to_string(
                _prepare_image(image),
                lang=OCR_LANGUAGES,
                config=OCR_TESSERACT_CONFIG,
            ).strip()
        )
    return "\n\n".join(chunk for chunk in chunks if chunk)


def _extract_from_image(content):
    image = Image.open(io.BytesIO(content))
    return pytesseract.image_to_string(
        _prepare_image(image),
        lang=OCR_LANGUAGES,
        config=OCR_TESSERACT_CONFIG,
    ).strip()


@app.get("/health")
def health():
    return jsonify({"status": "UP"})


@app.post("/ocr/extract")
def extract():
    file = request.files.get("file")
    if file is None or file.filename == "":
        return jsonify({"message": "file is required"}), 400

    content = file.read()
    if not content:
        return jsonify({"message": "file is empty"}), 400

    content_type = (file.content_type or "").lower()
    try:
        if content_type == "application/pdf":
            text = _extract_from_pdf(content)
        else:
            text = _extract_from_image(content)
        return jsonify({"text": text})
    except Exception as exception:
        return jsonify({"message": f"OCR extraction failed: {exception}"}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8081")))
