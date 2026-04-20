package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.client.OcrRequestOptions;
import com.blyndov.homebudgetreceiptsmanager.entity.OcrLanguageDetectionSource;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrRoutingService {

    private static final String FALLBACK_PROFILE = "en";
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{4}[-./]\\d{2}[-./]\\d{2}\\b|\\b\\d{2}[-./]\\d{2}[-./]\\d{4}\\b");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\b\\d{1,5}[.,]\\d{2}\\b");
    private static final Pattern LETTER_PATTERN = Pattern.compile("[\\p{L}]");
    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("[\\p{IsCyrillic}]");
    private static final Pattern LATIN_PATTERN = Pattern.compile("[A-Za-z]");
    private static final List<String> ENGLISH_HINTS = List.of("total", "date", "receipt", "market", "amount", "store");
    private static final List<String> CYRILLIC_HINTS = List.of("грн", "сума", "сумма", "дата", "разом", "магаз");
    private static final List<String> POLISH_HINTS = List.of("paragon", "razem", "kwota", "sklep", "sprzedaz", "sprzedaż");
    private static final List<String> GERMAN_HINTS = List.of("summe", "betrag", "gesamt", "rechnung", "markt", "datum");

    private final OcrClient ocrClient;
    private final ReceiptOcrLanguageDetector languageDetector;

    public ReceiptOcrRoutingService(OcrClient ocrClient, ReceiptOcrLanguageDetector languageDetector) {
        this.ocrClient = ocrClient;
        this.languageDetector = languageDetector;
    }

    public ReceiptOcrRoutingDecision route(Receipt receipt, byte[] content) {
        if (receipt.getReceiptCountryHint() != null) {
            ProfileStrategy strategy = strategyForCountry(receipt.getReceiptCountryHint());
            return runStrategy(receipt, content, strategy, OcrLanguageDetectionSource.USER_SELECTED, strategy.primaryProfile());
        }

        OcrExtractionResult preview = extractWithProfile(receipt, content, FALLBACK_PROFILE);
        DetectedOcrProfile detectedProfile = languageDetector.detect(preview);
        if (detectedProfile != null) {
            ProfileStrategy strategy = strategyForProfile(detectedProfile.profileName());
            return runStrategy(
                receipt,
                content,
                strategy,
                OcrLanguageDetectionSource.AUTO_DETECTED,
                detectedProfile.profileName(),
                preview
            );
        }

        return new ReceiptOcrRoutingDecision(
            null,
            OcrLanguageDetectionSource.DEFAULT_FALLBACK,
            FALLBACK_PROFILE,
            FALLBACK_PROFILE,
            preview
        );
    }

    private ReceiptOcrRoutingDecision runStrategy(
        Receipt receipt,
        byte[] content,
        ProfileStrategy strategy,
        OcrLanguageDetectionSource detectionSource,
        String preferredProfile
    ) {
        return runStrategy(receipt, content, strategy, detectionSource, preferredProfile, null);
    }

    private ReceiptOcrRoutingDecision runStrategy(
        Receipt receipt,
        byte[] content,
        ProfileStrategy strategy,
        OcrLanguageDetectionSource detectionSource,
        String preferredProfile,
        OcrExtractionResult reusableEnglishPreview
    ) {
        Map<String, OcrExtractionResult> candidates = new LinkedHashMap<>();
        for (String profileName : strategy.candidateProfiles()) {
            if (FALLBACK_PROFILE.equals(profileName) && reusableEnglishPreview != null) {
                candidates.put(profileName, reusableEnglishPreview);
                continue;
            }
            candidates.put(profileName, extractWithProfile(receipt, content, profileName));
        }

        String selectedProfile = chooseBestProfile(candidates, preferredProfile);
        return new ReceiptOcrRoutingDecision(
            receipt.getReceiptCountryHint(),
            detectionSource,
            strategy.name(),
            selectedProfile,
            candidates.get(selectedProfile)
        );
    }

    private OcrExtractionResult extractWithProfile(Receipt receipt, byte[] content, String profile) {
        return ocrClient.extractResult(
            receipt.getOriginalFileName(),
            receipt.getContentType(),
            content,
            new OcrRequestOptions(profile)
        );
    }

    private String chooseBestProfile(Map<String, OcrExtractionResult> candidates, String preferredProfile) {
        String bestProfile = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, OcrExtractionResult> candidate : candidates.entrySet()) {
            double score = score(candidate.getKey(), candidate.getValue());
            scores.put(candidate.getKey(), score);
            if (score > bestScore) {
                bestScore = score;
                bestProfile = candidate.getKey();
            }
        }

        if (preferredProfile != null && scores.containsKey(preferredProfile)) {
            double preferredScore = scores.get(preferredProfile);
            if (bestProfile == null || preferredScore >= bestScore - 0.75d) {
                return preferredProfile;
            }
        }

        return bestProfile == null ? FALLBACK_PROFILE : bestProfile;
    }

    private double score(String profileName, OcrExtractionResult extractionResult) {
        if (extractionResult == null || !StringUtils.hasText(extractionResult.rawText())) {
            return -100.0d;
        }

        String rawText = extractionResult.rawText();
        List<String> nonBlankLines = extractionResult.lines() == null
            ? List.of()
            : extractionResult.lines().stream().map(OcrExtractionLine::text).filter(StringUtils::hasText).toList();
        int amountHits = countMatches(AMOUNT_PATTERN, rawText);
        int dateHits = countMatches(DATE_PATTERN, rawText);
        int mixedScriptLines = (int) nonBlankLines.stream().filter(this::containsMixedScript).count();
        int brokenLines = (int) nonBlankLines.stream().filter(this::looksBroken).count();
        int usefulLines = (int) nonBlankLines.stream().filter(this::looksUseful).count();
        int shortJunkLines = (int) nonBlankLines.stream().filter(this::looksShortJunk).count();
        int fragmentaryLines = (int) nonBlankLines.stream().filter(this::looksFragmentary).count();
        int keywordHits = keywordHits(profileName, rawText);
        double averageConfidence = extractionResult.lines() == null
            ? 0.0d
            : extractionResult.lines()
                .stream()
                .map(OcrExtractionLine::confidence)
                .filter(confidence -> confidence != null && confidence > 0.0d)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0d);

        double score = usefulLines * 2.5d;
        score += amountHits * 3.5d;
        score += dateHits * 2.5d;
        score += keywordHits * 3.0d;
        score += averageConfidence * 6.0d;
        score -= mixedScriptLines * 3.0d;
        score -= brokenLines * 2.5d;
        score -= shortJunkLines * 1.75d;
        score -= fragmentaryLines * 2.25d;
        score -= Math.max(0, nonBlankLines.size() - usefulLines - 2) * 0.75d;

        if ("cyrillic".equals(profileName) && CYRILLIC_PATTERN.matcher(rawText).find()) {
            score += 2.0d;
        }
        if (List.of("en", "polish", "german").contains(profileName) && LATIN_PATTERN.matcher(rawText).find()) {
            score += 2.0d;
        }

        return score;
    }

    private int keywordHits(String profileName, String rawText) {
        String normalized = rawText.toLowerCase(Locale.ROOT);
        int hits = ENGLISH_HINTS.stream().mapToInt(hint -> normalized.contains(hint) ? 1 : 0).sum();
        List<String> additionalHints = switch (profileName) {
            case "cyrillic" -> CYRILLIC_HINTS;
            case "polish" -> POLISH_HINTS;
            case "german" -> GERMAN_HINTS;
            default -> List.of();
        };
        hits += additionalHints.stream().mapToInt(hint -> normalized.contains(hint) ? 1 : 0).sum();
        return hits;
    }

    private int countMatches(Pattern pattern, String value) {
        return (int) pattern.matcher(value).results().count();
    }

    private boolean containsMixedScript(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return CYRILLIC_PATTERN.matcher(value).find() && LATIN_PATTERN.matcher(value).find();
    }

    private boolean looksBroken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        String[] tokens = trimmed.split("\\s+");
        return tokens.length == 1 && trimmed.length() > 18;
    }

    private boolean looksUseful(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.length() >= 6 && LETTER_PATTERN.matcher(trimmed).find();
    }

    private boolean looksShortJunk(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 2 || (!LETTER_PATTERN.matcher(trimmed).find() && trimmed.length() <= 6);
    }

    private boolean looksFragmentary(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length >= 2) {
            double averageTokenLength = trimmed.replace(" ", "").length() / (double) tokens.length;
            if (averageTokenLength <= 2.5d) {
                return true;
            }
        }
        return trimmed.endsWith("-") && trimmed.length() <= 10;
    }

    private ProfileStrategy strategyForCountry(ReceiptCountryHint receiptCountryHint) {
        return switch (receiptCountryHint) {
            case UKRAINE -> new ProfileStrategy("en+cyrillic", "cyrillic", List.of("cyrillic", "en"));
            case POLAND -> new ProfileStrategy("en+polish", "polish", List.of("polish", "en"));
            case GERMANY -> new ProfileStrategy("en+german", "german", List.of("german", "en"));
        };
    }

    private ProfileStrategy strategyForProfile(String profileName) {
        return switch (profileName) {
            case "cyrillic" -> new ProfileStrategy("en+cyrillic", "cyrillic", List.of("cyrillic", "en"));
            case "polish" -> new ProfileStrategy("en+polish", "polish", List.of("polish", "en"));
            case "german" -> new ProfileStrategy("en+german", "german", List.of("german", "en"));
            default -> new ProfileStrategy("en", "en", List.of("en"));
        };
    }

    private record ProfileStrategy(String name, String primaryProfile, List<String> candidateProfiles) {
    }
}
