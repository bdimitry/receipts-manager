import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { AppLayout } from "../../app/layout/AppLayout";
import { ProtectedRoute } from "../../app/layout/ProtectedRoute";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { server } from "../../test/server";
import { renderWithRouter } from "../../test/test-utils";

describe("auth flow", () => {
  it("redirects guests away from protected routes", async () => {
    const routes = [
      { path: "/login", element: <div>Login screen</div> },
      {
        element: <ProtectedRoute />,
        children: [{ path: "/", element: <div>Private screen</div> }],
      },
    ];

    renderWithRouter(routes, ["/"]);

    expect(await screen.findByText("Login screen")).toBeInTheDocument();
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

    const routes = [
      { path: "/register", element: <RegisterPage /> },
      { path: "/login", element: <div>Login route</div> },
    ];
    const user = userEvent.setup();
    const { router } = renderWithRouter(routes, ["/register"]);

    await user.type(screen.getByLabelText("Email"), "new@example.com");
    await user.type(screen.getByLabelText("Password"), "Password123");
    await user.type(screen.getByLabelText("Confirm password"), "Password123");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/login");
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

    const routes = [
      { path: "/login", element: <LoginPage /> },
      { path: "/", element: <div>Dashboard route</div> },
    ];
    const user = userEvent.setup();
    const { router } = renderWithRouter(routes, ["/login"]);

    await user.type(screen.getByLabelText("Email"), "demo@example.com");
    await user.type(screen.getByLabelText("Password"), "Password123");
    await user.click(screen.getByRole("button", { name: "Login" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/");
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

    const routes = [
      { path: "/login", element: <div>Login page</div> },
      {
        element: <ProtectedRoute />,
        children: [
          {
            path: "/",
            element: <AppLayout />,
            children: [{ index: true, element: <div>Overview page</div> }],
          },
        ],
      },
    ];
    const user = userEvent.setup();
    const { router } = renderWithRouter(routes, ["/"], "jwt-token");

    expect(await screen.findByText("demo@example.com")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Logout" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/login");
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

    const routes = [
      { path: "/login", element: <div>Login page</div> },
      {
        element: <ProtectedRoute />,
        children: [
          {
            path: "/",
            element: <AppLayout />,
            children: [{ index: true, element: <div>Overview page</div> }],
          },
        ],
      },
    ];

    const { router } = renderWithRouter(routes, ["/"], "expired-token");

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/login");
    });
    expect(window.localStorage.getItem("hb.jwt")).toBeNull();
  });
});
