import io
import os
import threading

import fitz
import numpy as np
from flask import Flask, jsonify, request
from paddleocr import PaddleOCR
from PIL import Image


app = Flask(__name__)
PADDLE_OCR_LANG = os.getenv("PADDLE_OCR_LANG", "cyrillic")
PADDLE_OCR_USE_ANGLE_CLS = os.getenv("PADDLE_OCR_USE_ANGLE_CLS", "false").lower() == "true"
_ocr = None
_ocr_lock = threading.Lock()


def _engine():
    global _ocr
    if _ocr is None:
        with _ocr_lock:
            if _ocr is None:
                _ocr = PaddleOCR(
                    use_angle_cls=PADDLE_OCR_USE_ANGLE_CLS,
                    lang=PADDLE_OCR_LANG,
                    use_gpu=False,
                    show_log=False,
                )
    return _ocr


def _page_images(content, content_type):
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


def _extract_lines(content, content_type):
    lines = []
    for image in _page_images(content, content_type):
        result = _engine().ocr(np.array(image), cls=PADDLE_OCR_USE_ANGLE_CLS)
        for page in result or []:
            for entry in page or []:
                if len(entry) < 2:
                    continue
                text, confidence = entry[1]
                text = (text or "").strip()
                if not text:
                    continue
                lines.append(
                    {
                        "text": text,
                        "confidence": round(float(confidence), 4) if confidence is not None else None,
                    }
                )
    return lines


@app.get("/health")
def health():
    return jsonify({"status": "UP", "backend": "PaddleOCR"})


@app.post("/ocr")
def ocr():
    file = request.files.get("file")
    if file is None or file.filename == "":
        return jsonify({"message": "file is required"}), 400

    content = file.read()
    if not content:
        return jsonify({"message": "file is empty"}), 400

    content_type = (file.content_type or "").lower()
    try:
        lines = _extract_lines(content, content_type)
        raw_text = "\n".join(line["text"] for line in lines)
        return jsonify({"rawText": raw_text, "lines": lines})
    except Exception as exception:
        return jsonify({"message": f"PaddleOCR extraction failed: {exception}"}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8083")))
