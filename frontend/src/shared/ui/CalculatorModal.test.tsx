import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "../../test/test-utils";
import { CalculatorModal } from "./CalculatorModal";

describe("calculator window", () => {
  it("opens as a floating window, evaluates basic operations, applies the result, and closes", async () => {
    const user = userEvent.setup();
    const onApply = vi.fn();
    const onClose = vi.fn();

    renderWithProviders(
      <CalculatorModal
        title="Calculator"
        closeLabel="Close"
        clearLabel="Clear"
        applyLabel="Apply to amount"
        expressionLabel="Expression"
        resultLabel="Result"
        open
        onApply={onApply}
        onClose={onClose}
      />,
    );

    expect(screen.getByTestId("calculator-window")).toHaveAttribute("aria-modal", "false");

    await user.click(screen.getByRole("button", { name: "7" }));
    await user.click(screen.getByRole("button", { name: "+" }));
    await user.click(screen.getByRole("button", { name: "5" }));
    await user.click(screen.getByRole("button", { name: "=" }));

    expect(screen.getByText("12")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Apply to amount" }));
    expect(onApply).toHaveBeenCalledWith(12);

    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalled();
  });
});
