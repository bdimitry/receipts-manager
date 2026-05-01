package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.List;

public record ReceiptOcrCandidateSet(
    List<ReceiptOcrFieldCandidate> merchantCandidates,
    List<ReceiptOcrFieldCandidate> dateCandidates,
    List<ReceiptOcrFieldCandidate> totalAmountCandidates,
    List<ReceiptOcrFieldCandidate> paymentAmountCandidates,
    List<ReceiptOcrFieldCandidate> currencyCandidates,
    List<ReceiptOcrFieldCandidate> itemRowCandidates
) {

    public ReceiptOcrCandidateSet {
        merchantCandidates = copy(merchantCandidates);
        dateCandidates = copy(dateCandidates);
        totalAmountCandidates = copy(totalAmountCandidates);
        paymentAmountCandidates = copy(paymentAmountCandidates);
        currencyCandidates = copy(currencyCandidates);
        itemRowCandidates = copy(itemRowCandidates);
    }

    private static List<ReceiptOcrFieldCandidate> copy(List<ReceiptOcrFieldCandidate> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }
}
