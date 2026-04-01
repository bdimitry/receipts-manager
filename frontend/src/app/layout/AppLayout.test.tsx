import { createRoutesFromElements, Route } from "react-router-dom";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { AppLayout } from "./AppLayout";
import { renderWithRouter } from "../../test/test-utils";
import { server } from "../../test/server";

describe("app layout", () => {
  it("opens the calculator from the sidebar and keeps it outside the page content", async () => {
    server.use(
      http.get("/api/users/me", () =>
        HttpResponse.json({
          id: 7,
          email: "demo@example.com",
          createdAt: "2026-04-01T09:00:00Z",
        }),
      ),
    );

    const user = userEvent.setup();
    renderWithRouter(
      createRoutesFromElements(
        <Route element={<AppLayout />}>
          <Route index element={<div>Overview content</div>} />
        </Route>,
      ),
      ["/"],
      "demo-token",
    );

    expect(await screen.findByTestId("sidebar")).toBeInTheDocument();
    expect(screen.getByTestId("content-area")).toHaveTextContent("Overview content");
    expect(screen.queryByTestId("calculator-window")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("sidebar-calculator-toggle"));
    expect(screen.getByTestId("calculator-window")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.queryByTestId("calculator-window")).not.toBeInTheDocument();
  });
});
