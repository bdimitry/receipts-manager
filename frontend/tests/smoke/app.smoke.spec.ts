import { expect, test, type Page, type Route } from "@playwright/test";

const currentUser = {
  id: 5,
  email: "demo@example.com",
  createdAt: "2026-03-31T09:00:00Z",
};

const purchases = [
  {
    id: 1,
    title: "Groceries",
    category: "FOOD",
    amount: 42.75,
    currency: "EUR",
    purchaseDate: "2026-03-30",
    storeName: "Fresh Market",
    comment: null,
    createdAt: "2026-03-31T09:00:00Z",
    items: [],
  },
  {
    id: 2,
    title: "Metro Ticket",
    category: "TRANSPORT",
    amount: 3.5,
    currency: "EUR",
    purchaseDate: "2026-03-31",
    storeName: "Metro",
    comment: null,
    createdAt: "2026-03-31T09:10:00Z",
    items: [],
  },
];

const receipts = [
  {
    id: 10,
    purchaseId: 1,
    originalFileName: "receipt-10.png",
    contentType: "image/png",
    fileSize: 2048,
    currency: "EUR",
    s3Key: "receipts/10.png",
    uploadedAt: "2026-03-31T10:00:00Z",
    ocrStatus: "DONE",
    parsedStoreName: "Fresh Market",
    parsedTotalAmount: 42.75,
    parsedPurchaseDate: "2026-03-30",
    parsedLineItemCount: 2,
    ocrErrorMessage: null,
    ocrProcessedAt: "2026-03-31T10:01:00Z",
  },
];

const reports = [
  {
    id: 15,
    year: 2026,
    month: 3,
    reportType: "MONTHLY_SPENDING",
    reportFormat: "PDF",
    status: "DONE",
    s3Key: "reports/15.pdf",
    errorMessage: null,
    createdAt: "2026-03-31T11:00:00Z",
    updatedAt: "2026-03-31T11:05:00Z",
  },
];

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

async function mockApi(page: Page) {
  await page.route("**/*", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (!path.startsWith("/api/")) {
      await route.continue();
      return;
    }

    if (method === "POST" && path === "/api/auth/register") {
      return fulfillJson(
        route,
        {
          id: 6,
          email: "new@example.com",
          createdAt: "2026-03-31T12:00:00Z",
        },
        201,
      );
    }

    if (method === "POST" && path === "/api/auth/login") {
      return fulfillJson(route, {
        accessToken: "jwt-token",
        tokenType: "Bearer",
      });
    }

    if (method === "GET" && path === "/api/users/me") {
      return fulfillJson(route, currentUser);
    }

    if (method === "GET" && path === "/api/users/me/notification-settings") {
      return fulfillJson(route, {
        email: currentUser.email,
        telegramChatId: null,
        preferredNotificationChannel: "EMAIL",
      });
    }

    if (method === "GET" && path === "/api/purchases") {
      return fulfillJson(route, purchases);
    }

    if (method === "GET" && path === "/api/receipts") {
      return fulfillJson(route, receipts);
    }

    if (method === "GET" && path === "/api/reports") {
      return fulfillJson(route, reports);
    }

    return fulfillJson(
      route,
      {
        timestamp: "2026-03-31T12:00:00Z",
        status: 404,
        error: "Not Found",
        message: `No mock for ${method} ${path}`,
        path,
      },
      404,
    );
  });
}

test.describe("frontend smoke", () => {
  test("registers, logs in, renders dashboard, opens main sections, and persists topbar preferences", async ({
    page,
  }) => {
    await mockApi(page);
    await page.addInitScript(() => {
      window.localStorage.setItem("hb.language", "\"en\"");
      window.localStorage.setItem("hb.theme", "\"light\"");
      window.localStorage.removeItem("hb.jwt");
    });

    await page.goto("/", { waitUntil: "networkidle" });
    await expect(page.getByRole("heading", { name: "Login" })).toBeVisible();
    await page.locator('a[href="/register"]').click();
    await expect(page).toHaveURL(/\/register$/);

    await page.getByLabel("Email").fill("new@example.com");
    await page.getByLabel(/^Password$/).fill("Password123");
    await page.getByLabel("Confirm password").fill("Password123");
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel("Email").fill("new@example.com");
    await page.getByLabel(/^Password$/).fill("Password123");
    await page.getByRole("button", { name: "Login" }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId("dashboard-donut-chart")).toBeVisible();
    await expect(page.getByTestId("language-flag-current")).toBeVisible();

    await page.locator('.sidebar__nav a[href="/purchases"]').click();
    await expect(page.locator(".content .page-intro h1")).toHaveText("Purchases");
    await page.getByRole("button", { name: "Calculator" }).click();
    await expect(page.getByTestId("calculator-window")).toBeVisible();

    const calculatorBox = await page.getByTestId("calculator-window").boundingBox();
    expect(calculatorBox).not.toBeNull();
    expect(calculatorBox!.x).toBeLessThan(500);

    const beforeSidebarTop = await page.getByTestId("sidebar").evaluate((element) =>
      element.getBoundingClientRect().top,
    );
    await page.getByTestId("content-area").evaluate((element) => {
      const filler = document.createElement("div");
      filler.style.height = "1800px";
      filler.setAttribute("data-testid", "scroll-filler");
      element.appendChild(filler);
      element.scrollTop = 700;
    });
    await expect
      .poll(() => page.getByTestId("content-area").evaluate((element) => element.scrollTop))
      .toBeGreaterThan(500);
    const afterSidebarTop = await page.getByTestId("sidebar").evaluate((element) =>
      element.getBoundingClientRect().top,
    );
    expect(Math.abs(afterSidebarTop - beforeSidebarTop)).toBeLessThan(2);

    await page.locator('.sidebar__nav a[href="/receipts"]').click();
    await expect(page.locator(".content .page-intro h1")).toHaveText("Receipts");

    await page.locator('.sidebar__nav a[href="/reports"]').click();
    await expect(page.locator(".content .page-intro h1")).toHaveText("Report center");

    await page.locator('.sidebar__nav a[href="/profile"]').click();
    await expect(page.locator(".content .page-intro h1")).toHaveText("Profile");

    await page.getByTestId("language-dropdown-trigger").click();
    await expect(page.getByRole("button", { name: "RU" })).toBeVisible();
    await expect(page.getByTestId("language-flag-ru")).toBeVisible();
    await page.getByRole("button", { name: "RU" }).click();
    await page.getByTestId("theme-toggle").click();

    const persistedPage = await page.context().newPage();
    await mockApi(persistedPage);
    await persistedPage.goto("/", { waitUntil: "networkidle" });

    await expect(persistedPage.getByTestId("language-dropdown-trigger")).toBeVisible();
    await expect.poll(() =>
      persistedPage.evaluate(() => ({
        theme: document.documentElement.dataset.theme,
        language: document.documentElement.lang,
      })),
    ).toEqual({ theme: "dark", language: "ru" });
    await persistedPage.close();
  });
});
