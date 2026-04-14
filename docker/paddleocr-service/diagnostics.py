from __future__ import annotations

import argparse
import json

import numpy as np

from comparison import choose_recommended_profile, evaluate_profile_case, summarize_profile
from corpus import diagnostic_corpus
from normalization import ReceiptOcrLineNormalizer
from ocr_engine import PaddleOcrEngine
from preprocessing import ReceiptImagePreprocessor
from response_mapping import PaddleOcrResponseMapper


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local PaddleOCR quality diagnostics on the baseline diagnostic corpus.")
    parser.add_argument("--profiles", nargs="+", default=["en", "cyrillic", "latin"], help="OCR profiles to compare.")
    parser.add_argument("--preprocess", choices=["true", "false"], default="true", help="Whether to enable preprocessing.")
    args = parser.parse_args()

    preprocessor = ReceiptImagePreprocessor(enabled=args.preprocess == "true", target_long_edge=1600)
    mapper = PaddleOcrResponseMapper()
    normalizer = ReceiptOcrLineNormalizer()
    evaluations = []

    for case in diagnostic_corpus():
        print(f"=== SAMPLE {case.name} ===")
        print(json.dumps(case.to_response(), ensure_ascii=False))
        processed = preprocessor.preprocess(case.image)
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

        for profile_name in args.profiles:
            engine = PaddleOcrEngine(profile_name=profile_name)
            raw_result = engine.extract_lines(np.array(processed.image))
            raw_lines = mapper.map_raw_engine_lines(raw_result)
            mapped_line_models = mapper.map_page_lines(raw_result)
            mapped_lines = [line.to_response() for line in mapped_line_models]
            normalized_lines = [line.to_response() for line in normalizer.normalize_lines(mapped_line_models)]
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
                "normalizedLines": normalized_lines,
                "normalizedText": "\n".join(
                    line["normalizedText"] for line in normalized_lines if not line["ignored"] and line["normalizedText"]
                ),
                "evaluation": evaluation.to_response(),
            }
            print(json.dumps(payload, ensure_ascii=False))

    summaries = [summarize_profile(profile_name, evaluations) for profile_name in args.profiles]
    print("=== SUMMARY ===")
    print(json.dumps({"profiles": summaries, "recommended": choose_recommended_profile(summaries)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
