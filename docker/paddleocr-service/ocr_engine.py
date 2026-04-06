from __future__ import annotations

from pathlib import Path
import threading

from paddleocr import PaddleOCR


class PaddleOcrEngine:
    def __init__(self, language: str, use_angle_cls: bool) -> None:
        self.language = language
        self.use_angle_cls = use_angle_cls
        self._engines: dict[tuple[str, bool], PaddleOCR] = {}
        self._lock = threading.Lock()

    def warm_up(self) -> None:
        self._engine()

    def extract_lines(self, image_array, language_override: str | None = None):
        try:
            with self._lock:
                return self._engine(language_override).ocr(image_array, cls=self.use_angle_cls)
        except Exception:
            engine = self._reset_engine(language_override)
            with self._lock:
                return engine.ocr(image_array, cls=self.use_angle_cls)

    def describe(self, language_override: str | None = None) -> dict:
        ocr = self._engine(language_override)
        args = ocr.args
        return {
            "language": args.lang,
            "useAngleCls": bool(args.use_angle_cls),
            "detAlgorithm": args.det_algorithm,
            "recAlgorithm": args.rec_algorithm,
            "ocrVersion": args.ocr_version,
            "detModelDir": args.det_model_dir,
            "recModelDir": args.rec_model_dir,
            "clsModelDir": args.cls_model_dir,
            "detModelName": Path(args.det_model_dir).name if args.det_model_dir else None,
            "recModelName": Path(args.rec_model_dir).name if args.rec_model_dir else None,
            "clsModelName": Path(args.cls_model_dir).name if args.cls_model_dir else None,
        }

    def _engine(self, language_override: str | None = None):
        key = self._engine_key(language_override)
        if key not in self._engines:
            with self._lock:
                if key not in self._engines:
                    self._engines[key] = self._build_engine(key[0])
        return self._engines[key]

    def _reset_engine(self, language_override: str | None = None):
        key = self._engine_key(language_override)
        with self._lock:
            self._engines[key] = self._build_engine(key[0])
            return self._engines[key]

    def _build_engine(self, language: str):
        return PaddleOCR(
            use_angle_cls=self.use_angle_cls,
            lang=language,
            use_gpu=False,
            show_log=False,
        )

    def _engine_key(self, language_override: str | None) -> tuple[str, bool]:
        return ((language_override or self.language).strip(), self.use_angle_cls)
