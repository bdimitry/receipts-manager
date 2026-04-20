from __future__ import annotations

import argparse
import io
import json
from pathlib import Path

import fitz
import numpy as np
from PIL import Image

from comparison import choose_recommended_profile, evaluate_profile_case, summarize_profile
from corpus import diagnostic_corpus
from ocr_engine import PaddleOcrEngine
from preprocessing import ReceiptImagePreprocessor
from response_mapping import PaddleOcrResponseMapper


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local PaddleOCR quality diagnostics on the baseline diagnostic corpus.")
    parser.add_argument("--profiles", nargs="+", default=["en", "cyrillic", "latin"], help="OCR profiles to compare.")
    parser.add_argument("--preprocess", choices=["true", "false"], default="true", help="Whether to enable preprocessing.")
    parser.add_argument("--local-corpus-dir", default="C:/Users/dmitr/Pictures/чеки", help="Optional local directory with real receipt images for manual diagnostics.")
    args = parser.parse_args()

    preprocessor = ReceiptImagePreprocessor(enabled=args.preprocess == "true", target_long_edge=1600)
    mapper = PaddleOcrResponseMapper()
    evaluations = []

    local_corpus_dir = Path(args.local_corpus_dir)
    if local_corpus_dir.exists():
        print("=== LOCAL CORPUS ===")
        print(json.dumps({"path": str(local_corpus_dir), "files": sorted(path.name for path in local_corpus_dir.iterdir() if path.is_file())}, ensure_ascii=False))
        for sample_path in sorted(path for path in local_corpus_dir.iterdir() if path.is_file()):
            _run_local_sample(sample_path, args.profiles, preprocessor, mapper)

    for case in diagnostic_corpus():
        print(f"=== SAMPLE {case.name} ===")
        print(json.dumps(case.to_response(), ensure_ascii=False))
        processed = preprocessor.preprocess(case.image)
        print(
            json.dumps(
                {
                    "preprocessingApplied": processed.applied,
                    "strategy": processed.strategy,
                    "stepsApplied": list(processed.steps_applied),
                    "sizeBefore": {"width": processed.size_before.width, "height": processed.size_before.height},
                    "sizeAfter": {"width": processed.size_after.width, "height": processed.size_after.height},
                },
                ensure_ascii=False,
            )
        )

        for profile_name in args.profiles:
            engine = PaddleOcrEngine(profile_name=profile_name)
            raw_result = engine.extract_lines(np.array(processed.image))
            raw_lines = mapper.map_raw_engine_lines(raw_result)
            mapped_line_models = mapper.map_page_lines(raw_result)
            mapped_lines = [line.to_response() for line in mapped_line_models]
            evaluation = evaluate_profile_case(
                case,
                profile_name=profile_name,
                mapped_raw_text="\n".join(line["text"] for line in mapped_lines),
                mapped_lines=mapped_lines,
            )
            evaluations.append(evaluation)

            payload = {
                "profile": profile_name,
                "engineConfig": engine.describe(),
                "rawEngineText": "\n".join(line["text"] for line in raw_lines),
                "rawEngineLines": raw_lines,
                "mappedLines": mapped_lines,
                "mappedRawText": "\n".join(line["text"] for line in mapped_lines),
                "evaluation": evaluation.to_response(),
            }
            print(json.dumps(payload, ensure_ascii=False))

    summaries = [summarize_profile(profile_name, evaluations) for profile_name in args.profiles]
    print("=== SUMMARY ===")
    print(json.dumps({"profiles": summaries, "recommended": choose_recommended_profile(summaries)}, ensure_ascii=False))


def _run_local_sample(sample_path: Path, profiles: list[str], preprocessor: ReceiptImagePreprocessor, mapper: PaddleOcrResponseMapper) -> None:
    print(f"=== LOCAL SAMPLE {sample_path.name} ===")
    for processed in [preprocessor.preprocess(image) for image in _load_images(sample_path)]:
        print(
            json.dumps(
                {
                    "preprocessingApplied": processed.applied,
                    "strategy": processed.strategy,
                    "stepsApplied": list(processed.steps_applied),
                    "sizeBefore": {"width": processed.size_before.width, "height": processed.size_before.height},
                    "sizeAfter": {"width": processed.size_after.width, "height": processed.size_after.height},
                },
                ensure_ascii=False,
            )
        )
        for profile_name in profiles:
            engine = PaddleOcrEngine(profile_name=profile_name)
            raw_result = engine.extract_lines(np.array(processed.image))
            raw_lines = mapper.map_raw_engine_lines(raw_result)
            mapped_lines = [line.to_response() for line in mapper.map_page_lines(raw_result)]
            print(
                json.dumps(
                    {
                        "sample": sample_path.name,
                        "profile": profile_name,
                        "engineConfig": engine.describe(),
                        "rawEngineText": "\n".join(line["text"] for line in raw_lines),
                        "rawEngineLines": raw_lines,
                        "mappedLines": mapped_lines,
                        "mappedRawText": "\n".join(line["text"] for line in mapped_lines),
                    },
                    ensure_ascii=False,
                )
            )


def _load_images(sample_path: Path) -> list[Image.Image]:
    if sample_path.suffix.lower() == ".pdf":
        document = fitz.open(sample_path)
        try:
            images = []
            for page in document:
                pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
                images.append(Image.open(io.BytesIO(pixmap.tobytes("png"))).convert("RGB"))
            return images
        finally:
            document.close()

    return [Image.open(sample_path).convert("RGB")]


if __name__ == "__main__":
    main()
