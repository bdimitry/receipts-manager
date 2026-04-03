package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.PaddleOcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.PaddleOcrServiceResponse;
import com.blyndov.homebudgetreceiptsmanager.config.OcrClientProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PaddleOcrClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extractResultMapsRawTextAndLinesFromPaddleServiceResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ocr", this::handleOcrRequest);
        server.start();

        OcrClientProperties properties = new OcrClientProperties();
        properties.setPaddleBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        PaddleOcrClient client = new PaddleOcrClient(RestClient.builder(), properties);

        PaddleOcrServiceResponse response = client.extractResult(
            "receipt.png",
            "image/png",
            "fake-image".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(response.rawText()).isEqualTo("STORE\nTOTAL 123.45");
        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines().getFirst().text()).isEqualTo("STORE");
        assertThat(response.lines().getFirst().confidence()).isEqualTo(0.9912d);
        assertThat(client.extractText("receipt.png", "image/png", "fake-image".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("STORE\nTOTAL 123.45");
    }

    private void handleOcrRequest(HttpExchange exchange) throws IOException {
        byte[] responseBody = """
            {
              "rawText": "STORE\\nTOTAL 123.45",
              "preprocessingApplied": true,
              "pages": [
                {
                  "pageIndex": 0,
                  "imageSizeBefore": { "width": 1200, "height": 1800 },
                  "imageSizeAfter": { "width": 960, "height": 1600 },
                  "stepsApplied": ["crop_receipt", "deskew", "contrast", "threshold"]
                }
              ],
              "lines": [
                { "text": "STORE", "confidence": 0.9912 },
                { "text": "TOTAL 123.45", "confidence": 0.9821 }
              ]
            }
            """.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBody.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBody);
        } finally {
            exchange.close();
        }
    }
}
