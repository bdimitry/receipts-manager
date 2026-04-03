from __future__ import annotations

import threading

from paddleocr import PaddleOCR


class PaddleOcrEngine:
    def __init__(self, language: str, use_angle_cls: bool) -> None:
        self.language = language
        self.use_angle_cls = use_angle_cls
        self._ocr = None
        self._lock = threading.Lock()

    def warm_up(self) -> None:
        self._engine()

    def extract_lines(self, image_array):
        try:
            with self._lock:
                return self._engine().ocr(image_array, cls=self.use_angle_cls)
        except Exception:
            engine = self._reset_engine()
            with self._lock:
                return engine.ocr(image_array, cls=self.use_angle_cls)

    def _engine(self):
        if self._ocr is None:
            with self._lock:
                if self._ocr is None:
                    self._ocr = self._build_engine()
        return self._ocr

    def _reset_engine(self):
        with self._lock:
            self._ocr = self._build_engine()
            return self._ocr

    def _build_engine(self):
        return PaddleOCR(
            use_angle_cls=self.use_angle_cls,
            lang=self.language,
            use_gpu=False,
            show_log=False,
        )
