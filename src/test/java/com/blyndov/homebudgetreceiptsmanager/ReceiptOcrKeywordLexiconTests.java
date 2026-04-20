package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptOcrKeywordLexiconTests {

    private ReceiptOcrKeywordLexicon keywordLexicon;

    @BeforeEach
    void setUp() {
        keywordLexicon = new ReceiptOcrKeywordLexicon();
    }

    @Test
    void recognizesSummaryKeywordsAcrossSupportedLanguagesAndSafeOcrVariants() {
        assertThat(keywordLexicon.containsSummaryKeyword("TOTAL UAH 103.98")).isTrue();
        assertThat(keywordLexicon.containsSummaryKeyword("CYMA 103.98")).isTrue();
        assertThat(keywordLexicon.containsSummaryKeyword("\u0421\u0423\u041c\u0410 103.98")).isTrue();
        assertThat(keywordLexicon.containsSummaryKeyword("Coffee beans 103.98")).isFalse();
    }

    @Test
    void recognizesPaymentAndServiceVocabularyWithoutRewritingRegularProductText() {
        assertThat(keywordLexicon.containsPaymentKeyword("MasterCard terminal payment")).isTrue();
        assertThat(keywordLexicon.containsPaymentKeyword("\u041f\u043b\u0430\u0442\u0456\u0436 \u043a\u0430\u0440\u0442\u043a\u043e\u044e")).isTrue();
        assertThat(keywordLexicon.containsPaymentKeyword("SE3rOTIBKOBA KAPTKA")).isTrue();
        assertThat(keywordLexicon.containsPaymentKeyword("Milk 2 x 42.50")).isFalse();
    }

    @Test
    void exposesSmallMerchantAliasSetForKnownHighConfidenceCases() {
        assertThat(keywordLexicon.extractMerchantAlias("NOYUS MARKET")).contains("NOVUS");
        assertThat(keywordLexicon.extractMerchantAlias("UKRSIB BANK ONLINE")).contains("UkrsibBank");
        assertThat(keywordLexicon.extractMerchantAlias("Corner shop")).isEmpty();
    }
}
