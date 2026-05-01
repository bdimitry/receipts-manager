from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import cv2
import numpy as np
from PIL import Image


@dataclass(frozen=True)
class ImageSize:
    width: int
    height: int


@dataclass(frozen=True)
class ImageQualityProfile:
    contrast_std: float
    laplacian_variance: float
    dark_ratio: float
    bright_ratio: float
    edge_ratio: float
    entropy: float
    looks_clean: bool
    looks_like_white_page: bool
    visual_classification: str

    def to_response(self) -> dict:
        return {
            "contrastStd": self.contrast_std,
            "laplacianVariance": self.laplacian_variance,
            "darkRatio": self.dark_ratio,
            "brightRatio": self.bright_ratio,
            "edgeRatio": self.edge_ratio,
            "entropy": self.entropy,
            "looksClean": self.looks_clean,
            "looksLikeWhitePage": self.looks_like_white_page,
            "visualClassification": self.visual_classification,
        }


@dataclass(frozen=True)
class PreprocessedReceiptImage:
    image: Image.Image
    applied: bool
    size_before: ImageSize
    size_after: ImageSize
    strategy: str
    steps_applied: tuple[str, ...]
    upscale_factor: float
    crop_box: tuple[int, int, int, int] | None
    deskew_applied: bool
    quality_before: ImageQualityProfile | None
    quality_after: ImageQualityProfile | None

    def to_response(self, page_index: int) -> dict:
        return {
            "pageIndex": page_index,
            "imageSizeBefore": {
                "width": self.size_before.width,
                "height": self.size_before.height,
            },
            "imageSizeAfter": {
                "width": self.size_after.width,
                "height": self.size_after.height,
            },
            "strategy": self.strategy,
            "stepsApplied": list(self.steps_applied),
            "upscaleFactor": self.upscale_factor,
            "cropBox": list(self.crop_box) if self.crop_box else None,
            "deskewApplied": self.deskew_applied,
            "imageDiagnosticsBefore": self.quality_before.to_response() if self.quality_before else None,
            "imageDiagnosticsAfter": self.quality_after.to_response() if self.quality_after else None,
        }


class ReceiptImagePreprocessor:
    def __init__(self, enabled: bool = True, target_long_edge: int = 1600) -> None:
        self.enabled = enabled
        self.target_long_edge = target_long_edge

    def preprocess(self, image: Image.Image, enabled_override: bool | None = None) -> PreprocessedReceiptImage:
        effective_enabled = self.enabled if enabled_override is None else enabled_override
        normalized = image.convert("RGB")
        size_before = ImageSize(width=normalized.width, height=normalized.height)
        working = _pil_to_bgr(normalized)

        if not effective_enabled:
            quality = self._analyze_image(working)
            return PreprocessedReceiptImage(
                image=normalized,
                applied=False,
                size_before=size_before,
                size_after=size_before,
                strategy="disabled",
                steps_applied=tuple(),
                upscale_factor=1.0,
                crop_box=None,
                deskew_applied=False,
                quality_before=quality,
                quality_after=quality,
            )

        steps: list[str] = []
        initial_quality = self._analyze_image(working)

        working, upscaled, upscale_factor = self._upscale_if_needed(working, initial_quality)
        if upscaled:
            steps.append("upscale")

        working, cropped, crop_box = self._crop_receipt_region(working)
        if cropped:
            steps.append("crop_receipt")

        working, deskewed = self._deskew(working)
        if deskewed:
            steps.append("deskew")

        quality = self._analyze_image(working)
        working, strategy, enhanced_steps = self._enhance_for_ocr(working, quality)
        steps.extend(enhanced_steps)
        final_quality = self._analyze_image(working)

        processed_image = _bgr_to_pil(working)
        size_after = ImageSize(width=processed_image.width, height=processed_image.height)

        return PreprocessedReceiptImage(
            image=processed_image,
            applied=bool(steps),
            size_before=size_before,
            size_after=size_after,
            strategy=strategy,
            steps_applied=tuple(steps),
            upscale_factor=upscale_factor,
            crop_box=crop_box,
            deskew_applied=deskewed,
            quality_before=initial_quality,
            quality_after=final_quality,
        )

    def _upscale_if_needed(self, image: np.ndarray, quality: ImageQualityProfile) -> tuple[np.ndarray, bool, float]:
        height, width = image.shape[:2]
        long_edge = max(width, height)
        if long_edge >= self.target_long_edge:
            return image, False, 1.0

        target_long_edge = self.target_long_edge
        if quality.looks_clean:
            target_long_edge = min(target_long_edge, 900)

        scale = target_long_edge / float(long_edge)
        max_scale = 2.25 if quality.looks_clean else 2.0
        scale = min(scale, max_scale)
        if scale < 1.15:
            return image, False, 1.0

        resized = cv2.resize(
            image,
            (int(round(width * scale)), int(round(height * scale))),
            interpolation=cv2.INTER_LANCZOS4,
        )
        return resized, True, scale

    def _crop_receipt_region(self, image: np.ndarray) -> tuple[np.ndarray, bool, tuple[int, int, int, int] | None]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        edges = cv2.Canny(blurred, 50, 150)
        edges = cv2.dilate(edges, np.ones((5, 5), dtype=np.uint8), iterations=2)
        contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        height, width = image.shape[:2]
        image_area = height * width

        for contour in sorted(contours, key=cv2.contourArea, reverse=True):
            area = cv2.contourArea(contour)
            if area < image_area * 0.2:
                continue

            perimeter = cv2.arcLength(contour, True)
            approximation = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
            if len(approximation) == 4:
                warped = _four_point_warp(image, approximation.reshape(4, 2).astype("float32"))
                if warped is not None:
                    return warped, True, None

            x, y, box_width, box_height = cv2.boundingRect(contour)
            if box_width * box_height >= image_area * 0.35:
                margin = 12
                x = max(0, x - margin)
                y = max(0, y - margin)
                box_width = min(width - x, box_width + 2 * margin)
                box_height = min(height - y, box_height + 2 * margin)
                cropped = image[y : y + box_height, x : x + box_width]
                if cropped.size > 0:
                    return cropped, True, (x, y, box_width, box_height)

        return image, False, None

    def _deskew(self, image: np.ndarray) -> tuple[np.ndarray, bool]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        threshold = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
        coordinates = np.column_stack(np.where(threshold > 0))
        if coordinates.shape[0] < 200:
            return image, False

        angle = cv2.minAreaRect(coordinates.astype(np.float32))[-1]
        if angle < -45:
            angle = 90 + angle
        elif angle > 45:
            angle = angle - 90

        if abs(angle) < 0.7 or abs(angle) > 20:
            return image, False

        rotated = _rotate_bound(image, angle)
        return rotated, True

    def _enhance_for_ocr(self, image: np.ndarray, quality: ImageQualityProfile) -> tuple[np.ndarray, str, Sequence[str]]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        denoise_strength = 4 if quality.looks_clean else 7 if quality.visual_classification == "balanced" else 9
        denoised = cv2.fastNlMeansDenoising(gray, None, denoise_strength, 7, 21)
        clahe_clip_limit = 1.4 if quality.looks_clean else 1.8 if quality.visual_classification == "balanced" else 2.1
        clahe = cv2.createCLAHE(clipLimit=clahe_clip_limit, tileGridSize=(8, 8)).apply(denoised)
        contrast_weight = 0.3 if quality.looks_clean else 0.45 if quality.visual_classification == "balanced" else 0.7
        contrast_enhanced = cv2.addWeighted(denoised, 1.0 - contrast_weight, clahe, contrast_weight, 0)
        sharpened = _light_unsharp_mask(
            contrast_enhanced,
            amount=0.45 if quality.looks_clean else 0.55 if quality.visual_classification == "balanced" else 0.35,
            sigma=1.0 if quality.looks_clean else 1.2,
        )

        if quality.visual_classification != "noisy":
            enhanced = cv2.cvtColor(sharpened, cv2.COLOR_GRAY2BGR)
            return enhanced, "soft", ("denoise", "contrast", "light_sharpen")

        thresholded = cv2.adaptiveThreshold(
            sharpened,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY,
            35,
            11,
        )
        guided = _apply_threshold_guidance(sharpened, thresholded)
        enhanced = cv2.cvtColor(guided, cv2.COLOR_GRAY2BGR)
        return enhanced, "strong", ("denoise", "contrast", "threshold_guidance")

    def _analyze_image(self, image: np.ndarray) -> ImageQualityProfile:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        contrast_std = float(gray.std())
        laplacian_variance = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        dark_ratio = float((gray < 110).mean())
        bright_ratio = float((gray > 220).mean())
        edges = cv2.Canny(gray, 50, 150)
        edge_ratio = float((edges > 0).mean())
        histogram = cv2.calcHist([gray], [0], None, [256], [0, 256]).ravel()
        histogram /= max(float(histogram.sum()), 1.0)
        entropy = float(-(histogram[histogram > 0] * np.log2(histogram[histogram > 0])).sum())

        looks_like_white_page = bright_ratio > 0.82 and dark_ratio < 0.08
        looks_clean = looks_like_white_page or (
            dark_ratio < 0.08 and laplacian_variance > 900 and entropy < 5.5
        ) or (
            bright_ratio > 0.2 and dark_ratio < 0.05 and laplacian_variance > 1400
        )

        if looks_clean:
            visual_classification = "clean"
        elif edge_ratio > 0.09 or (dark_ratio > 0.18 and entropy > 6.0) or (
            contrast_std < 65 and dark_ratio > 0.12 and bright_ratio < 0.45
        ):
            visual_classification = "noisy"
        else:
            visual_classification = "balanced"

        return ImageQualityProfile(
            contrast_std=contrast_std,
            laplacian_variance=laplacian_variance,
            dark_ratio=dark_ratio,
            bright_ratio=bright_ratio,
            edge_ratio=edge_ratio,
            entropy=entropy,
            looks_clean=looks_clean,
            looks_like_white_page=looks_like_white_page,
            visual_classification=visual_classification,
        )


def estimate_skew_angle(image: Image.Image) -> float:
    bgr = _pil_to_bgr(image.convert("RGB"))
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    threshold = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
    coordinates = np.column_stack(np.where(threshold > 0))
    if coordinates.shape[0] < 200:
        return 0.0

    angle = cv2.minAreaRect(coordinates.astype(np.float32))[-1]
    if angle < -45:
        angle = 90 + angle
    elif angle > 45:
        angle = angle - 90
    return float(angle)


def _pil_to_bgr(image: Image.Image) -> np.ndarray:
    return cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)


def _bgr_to_pil(image: np.ndarray) -> Image.Image:
    return Image.fromarray(cv2.cvtColor(image, cv2.COLOR_BGR2RGB))


def _order_points(points: np.ndarray) -> np.ndarray:
    rectangle = np.zeros((4, 2), dtype="float32")
    sums = points.sum(axis=1)
    rectangle[0] = points[np.argmin(sums)]
    rectangle[2] = points[np.argmax(sums)]

    differences = np.diff(points, axis=1)
    rectangle[1] = points[np.argmin(differences)]
    rectangle[3] = points[np.argmax(differences)]
    return rectangle


def _four_point_warp(image: np.ndarray, points: np.ndarray) -> np.ndarray | None:
    rectangle = _order_points(points)
    top_left, top_right, bottom_right, bottom_left = rectangle

    width_a = np.linalg.norm(bottom_right - bottom_left)
    width_b = np.linalg.norm(top_right - top_left)
    max_width = int(max(width_a, width_b))

    height_a = np.linalg.norm(top_right - bottom_right)
    height_b = np.linalg.norm(top_left - bottom_left)
    max_height = int(max(height_a, height_b))

    if max_width < 50 or max_height < 50:
        return None

    destination = np.array(
        [
            [0, 0],
            [max_width - 1, 0],
            [max_width - 1, max_height - 1],
            [0, max_height - 1],
        ],
        dtype="float32",
    )

    matrix = cv2.getPerspectiveTransform(rectangle, destination)
    return cv2.warpPerspective(image, matrix, (max_width, max_height))


def _rotate_bound(image: np.ndarray, angle: float) -> np.ndarray:
    height, width = image.shape[:2]
    center = (width / 2, height / 2)
    matrix = cv2.getRotationMatrix2D(center, angle, 1.0)

    cosine = abs(matrix[0, 0])
    sine = abs(matrix[0, 1])

    bound_width = int((height * sine) + (width * cosine))
    bound_height = int((height * cosine) + (width * sine))

    matrix[0, 2] += (bound_width / 2) - center[0]
    matrix[1, 2] += (bound_height / 2) - center[1]

    return cv2.warpAffine(
        image,
        matrix,
        (bound_width, bound_height),
        flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_REPLICATE,
    )


def _light_unsharp_mask(image: np.ndarray, amount: float, sigma: float) -> np.ndarray:
    blurred = cv2.GaussianBlur(image, (0, 0), sigma)
    sharpened = cv2.addWeighted(image, 1.0 + amount, blurred, -amount, 0)
    return np.clip(sharpened, 0, 255).astype(np.uint8)


def _apply_threshold_guidance(base_gray: np.ndarray, thresholded: np.ndarray) -> np.ndarray:
    guided = base_gray.copy()
    text_mask = thresholded == 0
    guided[text_mask] = np.clip(guided[text_mask].astype(np.float32) * 0.42, 0, 255).astype(np.uint8)
    return guided
