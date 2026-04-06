from __future__ import annotations

from dataclasses import dataclass
import re
from statistics import mean

from corpus import DiagnosticCase


CYRILLIC_RE = re.compile(r"[А-Яа-яІіЇїЄєҐґ]")
LATIN_RE = re.compile(r"[A-Za-z]")


@dataclass(frozen=True)
class ProfileCaseEvaluation:
    case_name: str
    profile_name: str
    label_hits: int
    label_total: int
    number_hits: int
    number_total: int
    mixed_script_lines: int
    broken_lines: int
    score: float

    def to_response(self) -> dict:
        return {
            "caseName": self.case_name,
            "profileName": self.profile_name,
            "labelHits": self.label_hits,
            "labelTotal": self.label_total,
            "numberHits": self.number_hits,
            "numberTotal": self.number_total,
            "mixedScriptLines": self.mixed_script_lines,
            "brokenLines": self.broken_lines,
            "score": round(self.score, 3),
        }


def evaluate_profile_case(case: DiagnosticCase, profile_name: str, mapped_raw_text: str, mapped_lines: list[dict]) -> ProfileCaseEvaluation:
    normalized_text = _normalize(mapped_raw_text)
    label_hits = sum(1 for label in case.expected_labels if _normalize(label) in normalized_text)
    number_hits = sum(1 for number in case.expected_numbers if _normalize(number) in normalized_text)
    mixed_script_lines = sum(1 for line in mapped_lines if _contains_mixed_script(line["text"]))
    broken_lines = sum(1 for line in mapped_lines if _looks_broken(line["text"]))

    label_ratio = label_hits / max(len(case.expected_labels), 1)
    number_ratio = number_hits / max(len(case.expected_numbers), 1)
    score = (label_ratio * 0.55 + number_ratio * 0.45) * 100.0
    score -= mixed_script_lines * 6.0
    score -= broken_lines * 4.0

    return ProfileCaseEvaluation(
        case_name=case.name,
        profile_name=profile_name,
        label_hits=label_hits,
        label_total=len(case.expected_labels),
        number_hits=number_hits,
        number_total=len(case.expected_numbers),
        mixed_script_lines=mixed_script_lines,
        broken_lines=broken_lines,
        score=score,
    )


def summarize_profile(profile_name: str, evaluations: list[ProfileCaseEvaluation]) -> dict:
    profile_evaluations = [evaluation for evaluation in evaluations if evaluation.profile_name == profile_name]
    return {
        "profileName": profile_name,
        "cases": [evaluation.to_response() for evaluation in profile_evaluations],
        "averageScore": round(mean(evaluation.score for evaluation in profile_evaluations), 3),
        "totalMixedScriptLines": sum(evaluation.mixed_script_lines for evaluation in profile_evaluations),
        "totalBrokenLines": sum(evaluation.broken_lines for evaluation in profile_evaluations),
    }


def choose_recommended_profile(summaries: list[dict]) -> dict:
    ranked = sorted(
        summaries,
        key=lambda summary: (
            summary["averageScore"],
            -summary["totalMixedScriptLines"],
            -summary["totalBrokenLines"],
        ),
        reverse=True,
    )
    return ranked[0]


def _normalize(value: str) -> str:
    return re.sub(r"[^0-9A-Za-zА-Яа-яІіЇїЄєҐґ]+", "", value).lower()


def _contains_mixed_script(value: str) -> bool:
    return bool(CYRILLIC_RE.search(value) and LATIN_RE.search(value))


def _looks_broken(value: str) -> bool:
    token_count = len([token for token in re.split(r"\s+", value.strip()) if token])
    return token_count == 1 and len(value.strip()) > 18
