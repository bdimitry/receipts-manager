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
class PreprocessedReceiptImage:
    image: Image.Image
    applied: bool
    size_before: ImageSize
    size_after: ImageSize
    steps_applied: tuple[str, ...]

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
            "stepsApplied": list(self.steps_applied),
        }


class ReceiptImagePreprocessor:
    def __init__(self, enabled: bool = True, target_long_edge: int = 1600) -> None:
        self.enabled = enabled
        self.target_long_edge = target_long_edge

    def preprocess(self, image: Image.Image, enabled_override: bool | None = None) -> PreprocessedReceiptImage:
        effective_enabled = self.enabled if enabled_override is None else enabled_override
        normalized = image.convert("RGB")
        size_before = ImageSize(width=normalized.width, height=normalized.height)

        if not effective_enabled:
            return PreprocessedReceiptImage(
                image=normalized,
                applied=False,
                size_before=size_before,
                size_after=size_before,
                steps_applied=tuple(),
            )

        working = _pil_to_bgr(normalized)
        steps: list[str] = []

        working, upscaled = self._upscale_if_needed(working)
        if upscaled:
            steps.append("upscale")

        working, cropped = self._crop_receipt_region(working)
        if cropped:
            steps.append("crop_receipt")

        working, deskewed = self._deskew(working)
        if deskewed:
            steps.append("deskew")

        working, enhanced_steps = self._enhance_for_ocr(working)
        steps.extend(enhanced_steps)

        processed_image = _bgr_to_pil(working)
        size_after = ImageSize(width=processed_image.width, height=processed_image.height)

        return PreprocessedReceiptImage(
            image=processed_image,
            applied=bool(steps),
            size_before=size_before,
            size_after=size_after,
            steps_applied=tuple(steps),
        )

    def _upscale_if_needed(self, image: np.ndarray) -> tuple[np.ndarray, bool]:
        height, width = image.shape[:2]
        long_edge = max(width, height)
        if long_edge >= self.target_long_edge:
            return image, False

        scale = self.target_long_edge / float(long_edge)
        if scale < 1.1:
            return image, False

        resized = cv2.resize(
            image,
            (int(round(width * scale)), int(round(height * scale))),
            interpolation=cv2.INTER_CUBIC,
        )
        return resized, True

    def _crop_receipt_region(self, image: np.ndarray) -> tuple[np.ndarray, bool]:
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
                    return warped, True

            x, y, box_width, box_height = cv2.boundingRect(contour)
            if box_width * box_height >= image_area * 0.35:
                margin = 12
                x = max(0, x - margin)
                y = max(0, y - margin)
                box_width = min(width - x, box_width + 2 * margin)
                box_height = min(height - y, box_height + 2 * margin)
                cropped = image[y : y + box_height, x : x + box_width]
                if cropped.size > 0:
                    return cropped, True

        return image, False

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

    def _enhance_for_ocr(self, image: np.ndarray) -> tuple[np.ndarray, Sequence[str]]:
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        denoised = cv2.fastNlMeansDenoising(gray, None, 10, 7, 21)
        clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8)).apply(denoised)
        normalized = cv2.normalize(clahe, None, 0, 255, cv2.NORM_MINMAX)
        thresholded = cv2.adaptiveThreshold(
            normalized,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY,
            31,
            15,
        )
        thresholded = cv2.medianBlur(thresholded, 3)
        enhanced = cv2.cvtColor(thresholded, cv2.COLOR_GRAY2BGR)
        return enhanced, ("denoise", "contrast", "threshold")


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
