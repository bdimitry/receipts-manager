import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithProviders } from "../../test/test-utils";
import { LanguageDropdown } from "./LanguageDropdown";

describe("language dropdown", () => {
  it("opens, switches language, persists it, and closes on escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LanguageDropdown />);

    expect(screen.getByTestId("language-flag-current")).toBeInTheDocument();
    expect(screen.getByTestId("language-dropdown-trigger")).toHaveTextContent("EN");

    await user.click(screen.getByTestId("language-dropdown-trigger"));
    expect(screen.getByRole("button", { name: "RU" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "UK" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "EN" })).toBeInTheDocument();
    expect(screen.getByTestId("language-flag-ru")).toBeInTheDocument();
    expect(screen.getByTestId("language-flag-uk")).toBeInTheDocument();
    expect(screen.getByTestId("language-flag-en")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "RU" }));
    expect(window.localStorage.getItem("hb.language")).toBe("\"ru\"");
    expect(document.documentElement.lang).toBe("ru");
    expect(screen.getByTestId("language-dropdown-trigger")).toHaveTextContent("RU");

    await user.click(screen.getByTestId("language-dropdown-trigger"));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("button", { name: "EN" })).not.toBeInTheDocument();
  });
});
