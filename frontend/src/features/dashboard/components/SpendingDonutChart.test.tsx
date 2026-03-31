import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SpendingDonutChart } from "./SpendingDonutChart";
import { renderWithProviders } from "../../../test/test-utils";

describe("spending donut chart", () => {
  it("renders total, legend rows, and percentage labels", () => {
    renderWithProviders(
      <SpendingDonutChart
        total={900}
        currency="EUR"
        data={[
          { key: "FOOD", label: "Food", value: 540, percentage: 0.6 },
          { key: "UTILITIES", label: "Utilities", value: 360, percentage: 0.4 },
        ]}
      />,
    );

    expect(screen.getByTestId("dashboard-donut-chart")).toBeInTheDocument();
    expect(screen.getByText(/€900.00/i)).toBeInTheDocument();
    expect(screen.getByText(/Top category: Food 60%/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Spending legend by category")).toBeInTheDocument();
    expect(screen.getByText("Food")).toBeInTheDocument();
    expect(screen.getByText("Utilities")).toBeInTheDocument();
    expect(screen.getAllByText(/share of total/i)).toHaveLength(2);
  });
});
