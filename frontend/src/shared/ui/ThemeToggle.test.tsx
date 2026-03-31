import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithProviders } from "../../test/test-utils";
import { ThemeToggle } from "./ThemeToggle";

describe("theme toggle", () => {
  it("toggles theme and persists the selection", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ThemeToggle />);

    const toggle = screen.getByRole("button", { name: "Theme" });
    expect(document.documentElement.dataset.theme).toBe("light");

    await user.click(toggle);

    expect(window.localStorage.getItem("hb.theme")).toBe("\"dark\"");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });
});
