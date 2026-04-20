package com.blyndov.homebudgetreceiptsmanager.client;

public record OcrRequestOptions(String profile) {

    public static OcrRequestOptions defaultOptions() {
        return new OcrRequestOptions(null);
    }
}
