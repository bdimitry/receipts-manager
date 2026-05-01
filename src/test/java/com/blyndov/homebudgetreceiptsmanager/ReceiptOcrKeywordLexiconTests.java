package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
import org.junit.jupiter.api.Test;

class ReceiptOcrKeywordLexiconTests {

    @Test
    void merchantAliasesLiveInLexiconNotParserRules() {
        ReceiptOcrKeywordLexicon lexicon = new ReceiptOcrKeywordLexicon();

        assertThat(lexicon.extractMerchantAlias("MArA3NH N0VUS")).contains("NOVUS");
        assertThat(lexicon.extractMerchantAlias("ukrsib bank payment")).contains("UkrsibBank");
    }
}
