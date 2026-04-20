from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class OcrProfile:
    name: str
    paddle_lang: str
    use_angle_cls: bool
    description: str
    intended_corpus: str

    def to_response(self) -> dict:
        return {
            "name": self.name,
            "paddleLang": self.paddle_lang,
            "useAngleCls": self.use_angle_cls,
            "description": self.description,
            "intendedCorpus": self.intended_corpus,
        }


OCR_PROFILES: dict[str, OcrProfile] = {
    "en": OcrProfile(
        name="en",
        paddle_lang="en",
        use_angle_cls=False,
        description="English-centric receipt profile with stronger Latin label stability.",
        intended_corpus="English-heavy and mixed transactional documents with Latin headers, dates, and totals.",
    ),
    "cyrillic": OcrProfile(
        name="cyrillic",
        paddle_lang="cyrillic",
        use_angle_cls=False,
        description="Cyrillic-focused profile for Ukrainian/Russian text blocks and local transaction terms.",
        intended_corpus="Cyrillic-heavy receipts and payment documents.",
    ),
    "polish": OcrProfile(
        name="polish",
        paddle_lang="latin",
        use_angle_cls=False,
        description="Polish-oriented strategy using Paddle's Latin recognizer as the closest practical profile.",
        intended_corpus="Polish receipts with Latin-script labels, dates, and totals.",
    ),
    "german": OcrProfile(
        name="german",
        paddle_lang="german",
        use_angle_cls=False,
        description="German-oriented profile for receipts with German labels, dates, and amount markers.",
        intended_corpus="German receipts and payment summaries.",
    ),
    "latin": OcrProfile(
        name="latin",
        paddle_lang="latin",
        use_angle_cls=False,
        description="Latin-script profile useful for diagnostics on non-Cyrillic labels.",
        intended_corpus="Latin-only receipt and label experiments.",
    ),
}


DEFAULT_OCR_PROFILE = "en"


def available_profiles() -> list[OcrProfile]:
    return list(OCR_PROFILES.values())


def resolve_profile(profile_name: str | None) -> OcrProfile:
    normalized = (profile_name or DEFAULT_OCR_PROFILE).strip().lower()
    if normalized not in OCR_PROFILES:
        supported = ", ".join(sorted(OCR_PROFILES))
        raise ValueError(f"Unsupported OCR profile '{profile_name}'. Supported profiles: {supported}")
    return OCR_PROFILES[normalized]
