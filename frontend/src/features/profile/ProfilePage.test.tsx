import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { ProfilePage } from "./pages/ProfilePage";
import { renderWithProviders } from "../../test/test-utils";
import { server } from "../../test/server";

describe("profile page", () => {
  it("renders telegram connect flow and pending deep link", async () => {
    let pendingDeepLink: string | null = null;
    let pendingExpiresAt: string | null = null;

    server.use(
      http.get("/api/users/me", () =>
        HttpResponse.json({
          id: 5,
          email: "demo@example.com",
          createdAt: "2026-03-31T09:00:00Z",
        }),
      ),
      http.get("/api/users/me/notification-settings", () =>
        HttpResponse.json({
          email: "demo@example.com",
          telegramChatId: null,
          telegramConnected: false,
          telegramConnectedAt: null,
          preferredNotificationChannel: "EMAIL",
        }),
      ),
      http.get("/api/users/me/telegram/connection", () =>
        HttpResponse.json({
          connected: false,
          connectedAt: null,
          botUsername: "home_budget_receipts_bot",
          pendingDeepLink,
          pendingExpiresAt,
        }),
      ),
      http.post("/api/users/me/telegram/connect-session", () => {
        pendingDeepLink = "https://t.me/home_budget_receipts_bot?start=abc123";
        pendingExpiresAt = "2026-04-01T10:15:00Z";

        return HttpResponse.json({
          botUsername: "home_budget_receipts_bot",
          deepLink: pendingDeepLink,
          expiresAt: pendingExpiresAt,
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />);

    expect(await screen.findByText("Telegram not connected yet")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Connect Telegram" }));

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Open bot" })).toHaveAttribute(
        "href",
        "https://t.me/home_budget_receipts_bot?start=abc123",
      );
    });
  });

  it("loads and updates preferred notification channel after telegram is connected", async () => {
    let preferredNotificationChannel = "EMAIL";

    server.use(
      http.get("/api/users/me", () =>
        HttpResponse.json({
          id: 5,
          email: "demo@example.com",
          createdAt: "2026-03-31T09:00:00Z",
        }),
      ),
      http.get("/api/users/me/notification-settings", () =>
        HttpResponse.json({
          email: "demo@example.com",
          telegramChatId: "555000111",
          telegramConnected: true,
          telegramConnectedAt: "2026-04-01T09:45:00Z",
          preferredNotificationChannel,
        }),
      ),
      http.get("/api/users/me/telegram/connection", () =>
        HttpResponse.json({
          connected: true,
          connectedAt: "2026-04-01T09:45:00Z",
          botUsername: "home_budget_receipts_bot",
          pendingDeepLink: null,
          pendingExpiresAt: null,
        }),
      ),
      http.put("/api/users/me/notification-settings", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        preferredNotificationChannel = body.preferredNotificationChannel as string;

        return HttpResponse.json({
          email: "demo@example.com",
          telegramChatId: "555000111",
          telegramConnected: true,
          telegramConnectedAt: "2026-04-01T09:45:00Z",
          preferredNotificationChannel,
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />);

    expect(await screen.findByText("Telegram connected")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Preferred channel"), "TELEGRAM");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(screen.getByText(/Notification settings saved/i)).toBeInTheDocument();
    });
  });
});
