import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { DashboardPage } from "./pages/DashboardPage";
import { renderWithProviders } from "../../test/test-utils";
import { server } from "../../test/server";

function getCurrentPeriod() {
  const now = new Date();
  return {
    year: now.getFullYear(),
    month: now.getMonth() + 1,
  };
}

describe("dashboard", () => {
  it("loads chart data, translated category labels, and recent activity", async () => {
    const { year, month } = getCurrentPeriod();

    server.use(
      http.get("/api/purchases", ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get("year")).toBe(String(year));
        expect(url.searchParams.get("month")).toBe(String(month));

        return HttpResponse.json([
          {
            id: 1,
            title: "Groceries",
            category: "FOOD",
            amount: 540,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-05`,
            storeName: "Fresh Market",
            comment: null,
            createdAt: "2026-03-31T09:00:00Z",
            items: [],
          },
          {
            id: 2,
            title: "Utilities",
            category: "UTILITIES",
            amount: 330,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-07`,
            storeName: "City Power",
            comment: null,
            createdAt: "2026-03-31T09:10:00Z",
            items: [],
          },
          {
            id: 3,
            title: "Streaming",
            category: "SUBSCRIPTIONS",
            amount: 45,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-08`,
            storeName: "Stream+",
            comment: null,
            createdAt: "2026-03-31T09:12:00Z",
            items: [],
          },
          {
            id: 4,
            title: "Taxi",
            category: "TRANSPORT",
            amount: 25,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-09`,
            storeName: "City Ride",
            comment: null,
            createdAt: "2026-03-31T09:15:00Z",
            items: [],
          },
          {
            id: 5,
            title: "Books",
            category: "EDUCATION",
            amount: 20,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-10`,
            storeName: "Book House",
            comment: null,
            createdAt: "2026-03-31T09:18:00Z",
            items: [],
          },
          {
            id: 6,
            title: "Mini fee",
            category: "BILLS",
            amount: 10,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-11`,
            storeName: "Fee",
            comment: null,
            createdAt: "2026-03-31T09:19:00Z",
            items: [],
          },
        ]);
      }),
      http.get("/api/receipts", () =>
        HttpResponse.json([
          {
            id: 11,
            purchaseId: 1,
            originalFileName: "receipt-11.png",
            contentType: "image/png",
            fileSize: 1024,
            currency: "UAH",
            s3Key: "receipts/11.png",
            uploadedAt: "2026-03-31T10:00:00Z",
            ocrStatus: "DONE",
            parsedStoreName: "Fresh Market",
            parsedTotalAmount: 540,
            parsedPurchaseDate: `${year}-${String(month).padStart(2, "0")}-05`,
            parsedLineItemCount: 3,
            ocrErrorMessage: null,
            ocrProcessedAt: "2026-03-31T10:01:00Z",
          },
        ]),
      ),
      http.get("/api/reports", () =>
        HttpResponse.json([
          {
            id: 40,
            year,
            month,
            reportType: "MONTHLY_SPENDING",
            reportFormat: "PDF",
            status: "DONE",
            s3Key: "reports/40.pdf",
            errorMessage: null,
            createdAt: "2026-03-31T10:00:00Z",
            updatedAt: "2026-03-31T10:05:00Z",
          },
        ]),
      ),
    );

    renderWithProviders(<DashboardPage />);

    expect(await screen.findByTestId("dashboard-donut-chart")).toBeInTheDocument();
    expect(screen.getByText("Food")).toBeInTheDocument();
    expect(screen.getByText("Utilities")).toBeInTheDocument();
    expect(screen.getByText("Other")).toBeInTheDocument();
    expect(screen.getByText(/OCR completed/i)).toBeInTheDocument();
    expect(screen.getByText(/Report is ready/i)).toBeInTheDocument();
  });

  it("keeps mixed currency totals separated on the dashboard", async () => {
    const { year, month } = getCurrentPeriod();

    server.use(
      http.get("/api/purchases", () =>
        HttpResponse.json([
          {
            id: 1,
            title: "Groceries",
            category: "FOOD",
            amount: 540,
            currency: "UAH",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-05`,
            storeName: "Fresh Market",
            comment: null,
            createdAt: "2026-03-31T09:00:00Z",
            items: [],
          },
          {
            id: 2,
            title: "Software",
            category: "SUBSCRIPTIONS",
            amount: 20,
            currency: "USD",
            purchaseDate: `${year}-${String(month).padStart(2, "0")}-07`,
            storeName: "App Store",
            comment: null,
            createdAt: "2026-03-31T09:10:00Z",
            items: [],
          },
        ]),
      ),
      http.get("/api/receipts", () => HttpResponse.json([])),
      http.get("/api/reports", () => HttpResponse.json([])),
    );

    renderWithProviders(<DashboardPage />);

    expect(await screen.findByTestId("dashboard-donut-chart")).toBeInTheDocument();
    expect(screen.getByText(/selected currency only/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /UAH/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /USD/i })).toBeInTheDocument();
  });
});
