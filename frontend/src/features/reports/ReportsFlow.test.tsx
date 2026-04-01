import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { ReportsPage } from "./pages/ReportsPage";
import { renderWithProviders } from "../../test/test-utils";
import { server } from "../../test/server";

describe("reports flow", () => {
  it("creates a report job and triggers download for a ready report", async () => {
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    let reports = [
      {
        id: 10,
        year: 2026,
        month: 3,
        reportType: "MONTHLY_SPENDING",
        reportFormat: "PDF",
        status: "DONE",
        s3Key: "reports/10.pdf",
        errorMessage: null,
        createdAt: "2026-03-31T09:00:00Z",
        updatedAt: "2026-03-31T09:10:00Z",
      },
    ];

    server.use(
      http.get("/api/reports", () => HttpResponse.json(reports)),
      http.post("/api/reports", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        reports = [
          {
            id: 11,
            year: Number(body.year),
            month: Number(body.month),
            reportType: body.reportType as string,
            reportFormat: body.reportFormat as string,
            status: "NEW",
            s3Key: null,
            errorMessage: null,
            createdAt: "2026-03-31T10:00:00Z",
            updatedAt: "2026-03-31T10:00:00Z",
          },
          ...reports,
        ];

        return HttpResponse.json(reports[0], { status: 201 });
      }),
      http.get("/api/reports/10/download", () =>
        HttpResponse.json({
          reportJobId: 10,
          reportType: "MONTHLY_SPENDING",
          reportFormat: "PDF",
          status: "DONE",
          fileName: "monthly.pdf",
          contentType: "application/pdf",
          downloadUrl: "/api/reports/10/file",
          expiresAt: "2026-03-31T12:00:00Z",
        }),
      ),
      http.get("/api/reports/10/file", () =>
        new HttpResponse(new Uint8Array([1, 2, 3, 4]), {
          headers: {
            "Content-Type": "application/pdf",
          },
        }),
      ),
    );

    const user = userEvent.setup();
    renderWithProviders(<ReportsPage />);

    expect(await screen.findByText("Monthly spending")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Report type"), "CATEGORY_SUMMARY");
    await user.selectOptions(screen.getByLabelText("Report format"), "XLSX");
    await user.click(screen.getByRole("button", { name: "New report" }));

    await screen.findAllByText("Category summary");
    expect(screen.getByText(/Report job created/i)).toBeInTheDocument();

    const monthlyButton = screen
      .getAllByRole("button")
      .find((button) => button.textContent?.includes("Monthly spending"));

    expect(monthlyButton).toBeDefined();
    await user.click(monthlyButton!);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Download" })).toBeEnabled();
    });
    await user.click(screen.getByRole("button", { name: "Download" }));

    expect(window.URL.createObjectURL).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalled();
    expect(window.URL.revokeObjectURL).toHaveBeenCalledWith("blob:mock-report");

    clickSpy.mockRestore();
  });
});
