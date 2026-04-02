import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { Route, Routes, useLocation } from "react-router-dom";
import { AppLayout } from "../../app/layout/AppLayout";
import { ProtectedRoute } from "../../app/layout/ProtectedRoute";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { server } from "../../test/server";
import { renderWithProvidersAtEntries } from "../../test/test-utils";

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location-display">{location.pathname}</div>;
}

describe("auth flow", () => {
  it("redirects guests away from protected routes", async () => {
    renderWithProvidersAtEntries(
      <>
        <Routes>
          <Route path="/login" element={<div>Login screen</div>} />
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<div>Private screen</div>} />
          </Route>
        </Routes>
        <LocationProbe />
      </>,
      ["/"],
    );

    expect(await screen.findByText("Login screen")).toBeInTheDocument();
    expect(screen.getByTestId("location-display")).toHaveTextContent("/login");
  });

  it("registers a user and sends them to login", async () => {
    server.use(
      http.post("/api/auth/register", () =>
        HttpResponse.json(
          {
            id: 1,
            email: "new@example.com",
            createdAt: "2026-03-31T09:00:00Z",
          },
          { status: 201 },
        ),
      ),
    );

    const user = userEvent.setup();
    renderWithProvidersAtEntries(
      <>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/login" element={<div>Login route</div>} />
        </Routes>
        <LocationProbe />
      </>,
      ["/register"],
    );

    await user.type(screen.getByLabelText("Email"), "new@example.com");
    await user.type(screen.getByLabelText("Password"), "Password123");
    await user.type(screen.getByLabelText("Confirm password"), "Password123");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => {
      expect(screen.getByTestId("location-display")).toHaveTextContent("/login");
    });
  });

  it("logs in and stores jwt", async () => {
    server.use(
      http.post("/api/auth/login", () =>
        HttpResponse.json({
          accessToken: "jwt-token",
          tokenType: "Bearer",
        }),
      ),
    );

    const user = userEvent.setup();
    renderWithProvidersAtEntries(
      <>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<div>Dashboard route</div>} />
        </Routes>
        <LocationProbe />
      </>,
      ["/login"],
    );

    await user.type(screen.getByLabelText("Email"), "demo@example.com");
    await user.type(screen.getByLabelText("Password"), "Password123");
    await user.click(screen.getByRole("button", { name: "Login" }));

    await waitFor(() => {
      expect(screen.getByTestId("location-display")).toHaveTextContent("/");
    });
    expect(window.localStorage.getItem("hb.jwt")).toBe("jwt-token");
  });

  it("logs out from the application shell", async () => {
    server.use(
      http.get("/api/users/me", () =>
        HttpResponse.json({
          id: 5,
          email: "demo@example.com",
          createdAt: "2026-03-31T09:00:00Z",
        }),
      ),
    );

    const user = userEvent.setup();
    renderWithProvidersAtEntries(
      <>
        <Routes>
          <Route path="/login" element={<div>Login page</div>} />
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<AppLayout />}>
              <Route index element={<div>Overview page</div>} />
            </Route>
          </Route>
        </Routes>
        <LocationProbe />
      </>,
      ["/"],
      "jwt-token",
    );

    expect(await screen.findByText("demo@example.com")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Logout" }));

    await waitFor(() => {
      expect(screen.getByTestId("location-display")).toHaveTextContent("/login");
    });
    expect(window.localStorage.getItem("hb.jwt")).toBeNull();
  });

  it("clears stale session after backend 401", async () => {
    server.use(
      http.get("/api/users/me", () =>
        HttpResponse.json(
          {
            timestamp: "2026-03-31T09:00:00Z",
            status: 401,
            error: "Unauthorized",
            message: "Token expired",
            path: "/api/users/me",
          },
          { status: 401 },
        ),
      ),
    );

    renderWithProvidersAtEntries(
      <>
        <Routes>
          <Route path="/login" element={<div>Login page</div>} />
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<AppLayout />}>
              <Route index element={<div>Overview page</div>} />
            </Route>
          </Route>
        </Routes>
        <LocationProbe />
      </>,
      ["/"],
      "expired-token",
    );

    await waitFor(() => {
      expect(screen.getByTestId("location-display")).toHaveTextContent("/login");
    });
    expect(window.localStorage.getItem("hb.jwt")).toBeNull();
  });
});
