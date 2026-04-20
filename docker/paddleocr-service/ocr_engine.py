from __future__ import annotations

from pathlib import Path
import threading

from paddleocr import PaddleOCR

from profiles import OcrProfile, resolve_profile


class PaddleOcrEngine:
    def __init__(self, profile_name: str) -> None:
        self.profile_name = profile_name
        self._engines: dict[tuple[str, bool], PaddleOCR] = {}
        self._lock = threading.RLock()

    def warm_up(self) -> None:
        self._engine(resolve_profile(self.profile_name))

    def extract_lines(self, image_array, profile_override: str | None = None, language_override: str | None = None):
        try:
            with self._lock:
                profile = self._profile(profile_override, language_override)
                return self._engine(profile).ocr(image_array, cls=profile.use_angle_cls)
        except Exception:
            engine = self._reset_engine(profile_override, language_override)
            with self._lock:
                profile = self._profile(profile_override, language_override)
                return engine.ocr(image_array, cls=profile.use_angle_cls)

    def describe(self, profile_override: str | None = None, language_override: str | None = None) -> dict:
        profile = self._profile(profile_override, language_override)
        ocr = self._engine(profile)
        args = ocr.args
        return {
            "profile": profile.name,
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

    def _engine(self, profile: OcrProfile):
        key = self._engine_key(profile)
        if key not in self._engines:
            with self._lock:
                if key not in self._engines:
                    self._engines[key] = self._build_engine(profile)
        return self._engines[key]

    def _reset_engine(self, profile_override: str | None = None, language_override: str | None = None):
        profile = self._profile(profile_override, language_override)
        key = self._engine_key(profile)
        with self._lock:
            self._engines[key] = self._build_engine(profile)
            return self._engines[key]

    def _build_engine(self, profile: OcrProfile):
        return PaddleOCR(
            use_angle_cls=profile.use_angle_cls,
            lang=profile.paddle_lang,
            use_gpu=False,
            show_log=False,
        )

    def _profile(self, profile_override: str | None, language_override: str | None) -> OcrProfile:
        if language_override is not None:
            return OcrProfile(
                name=f"lang:{language_override.strip()}",
                paddle_lang=language_override.strip(),
                use_angle_cls=resolve_profile(profile_override or self.profile_name).use_angle_cls,
                description="Direct Paddle language override for diagnostics.",
                intended_corpus="Ad-hoc diagnostics only.",
            )
        return resolve_profile(profile_override or self.profile_name)

    def _engine_key(self, profile: OcrProfile) -> tuple[str, bool]:
        return (profile.paddle_lang, profile.use_angle_cls)
