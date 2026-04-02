package com.blyndov.homebudgetreceiptsmanager.client;

import java.util.List;

public record PaddleOcrServiceResponse(String rawText, List<PaddleOcrLineResponse> lines) {
}
