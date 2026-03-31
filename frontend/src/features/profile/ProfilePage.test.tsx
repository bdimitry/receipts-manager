import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { ProfilePage } from "./pages/ProfilePage";
import { renderWithProviders } from "../../test/test-utils";
import { server } from "../../test/server";

describe("profile page", () => {
  it("loads and updates notification settings", async () => {
    let preferredNotificationChannel = "EMAIL";
    let telegramChatId: string | null = null;

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
          telegramChatId,
          preferredNotificationChannel,
        }),
      ),
      http.put("/api/users/me/notification-settings", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        preferredNotificationChannel = body.preferredNotificationChannel as string;
        telegramChatId = (body.telegramChatId as string) || null;

        return HttpResponse.json({
          email: "demo@example.com",
          telegramChatId,
          preferredNotificationChannel,
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<ProfilePage />);

    expect(await screen.findByDisplayValue("Email")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Preferred channel"), "TELEGRAM");
    await user.type(screen.getByLabelText("Telegram chat ID"), "555000111");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(screen.getByDisplayValue("555000111")).toBeInTheDocument();
    });
    expect(screen.getByText(/Notification settings saved/i)).toBeInTheDocument();
  });
});
