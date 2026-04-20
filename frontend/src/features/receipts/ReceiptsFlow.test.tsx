import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createRoutesFromElements, Route } from "react-router-dom";
import { ReceiptDetailPage } from "./pages/ReceiptDetailPage";
import { ReceiptsPage } from "./pages/ReceiptsPage";
import { renderWithRouter } from "../../test/test-utils";
import { server } from "../../test/server";
import * as receiptsApi from "./api";

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    uploadReceipt: vi.fn(),
  };
});

const uploadReceiptMock = vi.mocked(receiptsApi.uploadReceipt);

describe("receipts flow", () => {
  beforeEach(() => {
    uploadReceiptMock.mockReset();
  });

  it("uploads a receipt and shows it in the list", async () => {
    let uploadedCurrency: string | null = null;
    let uploadedCountryHint: string | null = null;
    let receipts = [
      {
        id: 10,
        purchaseId: 1,
        originalFileName: "receipt-10.png",
        contentType: "image/png",
        fileSize: 3000,
        currency: "UAH",
        receiptCountryHint: null,
        s3Key: "receipts/10.png",
        uploadedAt: "2026-03-31T10:00:00Z",
        ocrStatus: "PROCESSING",
        parsedStoreName: null,
        parsedTotalAmount: null,
        parsedPurchaseDate: null,
        parsedLineItemCount: 0,
        ocrErrorMessage: null,
        ocrProcessedAt: null,
      },
    ];

    server.use(
      http.get("/api/purchases", () =>
        HttpResponse.json([
          {
            id: 1,
            title: "Demo Groceries",
            category: "FOOD",
            amount: 42.75,
            currency: "EUR",
            purchaseDate: "2026-03-30",
            storeName: "Fresh Market",
            comment: null,
            createdAt: "2026-03-31T09:00:00Z",
            items: [],
          },
        ]),
      ),
      http.get("/api/receipts", () => HttpResponse.json(receipts)),
    );

    uploadReceiptMock.mockImplementation(async (_file, currency, purchaseId, receiptCountryHint) => {
      uploadedCurrency = currency;
      uploadedCountryHint = receiptCountryHint ?? null;
      const createdReceipt = {
        id: 11,
        purchaseId: purchaseId ?? 1,
        originalFileName: "new-receipt.png",
        contentType: "image/png",
        fileSize: 2048,
        currency,
        receiptCountryHint: receiptCountryHint ?? null,
        s3Key: "receipts/11.png",
        uploadedAt: "2026-03-31T10:05:00Z",
        ocrStatus: "NEW" as const,
        parsedStoreName: null,
        parsedTotalAmount: null,
        parsedPurchaseDate: null,
        parsedLineItemCount: 0,
        ocrErrorMessage: null,
        ocrProcessedAt: null,
      };
      receipts = [createdReceipt, ...receipts];
      return createdReceipt;
    });

    const user = userEvent.setup();
    renderWithRouter(
      createRoutesFromElements(<Route path="/receipts" element={<ReceiptsPage />} />),
      ["/receipts"],
    );

    expect(await screen.findByText("receipt-10.png")).toBeInTheDocument();

    const file = new File(["demo receipt"], "new-receipt.png", { type: "image/png" });
    await user.upload(screen.getByLabelText("File"), file);
    await user.selectOptions(screen.getByLabelText(/Link to purchase/i), "1");
    await user.selectOptions(screen.getByLabelText("Currency"), "EUR");
    await user.selectOptions(screen.getByRole("combobox", { name: /Receipt country/i }), "UKRAINE");
    await user.click(screen.getByRole("button", { name: "Upload receipt" }));

    await waitFor(() => {
      expect(uploadedCurrency).toBe("EUR");
      expect(uploadedCountryHint).toBe("UKRAINE");
      expect(uploadReceiptMock).toHaveBeenCalledWith(expect.any(File), "EUR", 1, "UKRAINE");
    });
    expect(await screen.findByText("new-receipt.png", {}, { timeout: 5_000 })).toBeInTheDocument();
    expect(screen.getByText(/Receipt uploaded/i)).toBeInTheDocument();
  });

  it("opens receipt detail and shows OCR result", async () => {
    server.use(
      http.get("/api/receipts/11", () =>
        HttpResponse.json({
          id: 11,
          purchaseId: 1,
          originalFileName: "receipt-11.png",
          contentType: "image/png",
          fileSize: 3000,
          currency: "UAH",
          receiptCountryHint: "UKRAINE",
          s3Key: "receipts/11.png",
          uploadedAt: "2026-03-31T10:05:00Z",
          ocrStatus: "DONE",
          parsedStoreName: "Fresh Market",
          parsedTotalAmount: 42.75,
          parsedPurchaseDate: "2026-03-30",
          parsedLineItemCount: 2,
          ocrErrorMessage: null,
          ocrProcessedAt: "2026-03-31T10:06:00Z",
        }),
      ),
      http.get("/api/receipts/11/ocr", () =>
        HttpResponse.json({
          receiptId: 11,
          currency: "UAH",
          ocrStatus: "DONE",
          rawOcrText: "FRESH MARKET\nTOTAL 42.75",
          receiptCountryHint: "UKRAINE",
          languageDetectionSource: "USER_SELECTED",
          ocrProfileStrategy: "en+cyrillic",
          ocrProfileUsed: "en",
          parsedStoreName: "Fresh Market",
          parsedTotalAmount: 42.75,
          parsedCurrency: "UAH",
          parsedPurchaseDate: "2026-03-30",
          parseWarnings: [],
          weakParseQuality: false,
          lineItems: [
            {
              id: 1,
              lineIndex: 1,
              title: "Milk",
              quantity: 2,
              unit: "pcs",
              unitPrice: 10.5,
              lineTotal: 21,
              rawFragment: "Milk 2 x 10.50 21.00",
            },
            {
              id: 2,
              lineIndex: 2,
              title: "Bread",
              quantity: null,
              unit: null,
              unitPrice: null,
              lineTotal: 21.75,
              rawFragment: "Bread 21.75",
            },
          ],
          ocrErrorMessage: null,
          ocrProcessedAt: "2026-03-31T10:06:00Z",
        }),
      ),
    );

    renderWithRouter(
      createRoutesFromElements(<Route path="/receipts/:id" element={<ReceiptDetailPage />} />),
      ["/receipts/11"],
    );

    expect(await screen.findByText("Fresh Market")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "OCR routing" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Parsed line items" })).toBeInTheDocument();
    expect(screen.getByText(/TOTAL 42.75/i)).toBeInTheDocument();
    expect(screen.getByText(/Raw OCR text/i)).toBeInTheDocument();
    expect(screen.getByText(/Manual country selection/i)).toBeInTheDocument();
    expect(screen.getByText("en+cyrillic")).toBeInTheDocument();
    expect(screen.getByText("Milk")).toBeInTheDocument();
    expect(screen.getByText("Bread")).toBeInTheDocument();
    expect(screen.getByText("Milk 2 x 10.50 21.00")).toBeInTheDocument();
    expect(screen.getByText("UAH")).toBeInTheDocument();
  });
});
