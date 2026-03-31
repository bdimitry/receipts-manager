import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { PurchasesPage } from "./pages/PurchasesPage";
import { renderWithProviders } from "../../test/test-utils";
import { server } from "../../test/server";

describe("purchases page", () => {
  it("loads purchases and creates a simple purchase without items", async () => {
    let purchases = [
      {
        id: 1,
        title: "Groceries",
        category: "FOOD",
        amount: 42.75,
        currency: "UAH",
        purchaseDate: "2026-03-30",
        storeName: "Fresh Market",
        comment: null,
        createdAt: "2026-03-31T09:00:00Z",
        items: [],
      },
    ];

    let lastRequest: Record<string, unknown> | null = null;

    server.use(
      http.get("/api/purchases", () => HttpResponse.json(purchases)),
      http.post("/api/purchases", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        lastRequest = body;
        purchases = [
          {
            id: 2,
            title: body.title as string,
            category: body.category as string,
            amount: Number(body.amount),
            currency: body.currency as "USD" | "EUR" | "UAH" | "RUB",
            purchaseDate: body.purchaseDate as string,
            storeName: (body.storeName as string) || null,
            comment: (body.comment as string) || null,
            createdAt: "2026-03-31T10:00:00Z",
            items: [],
          },
          ...purchases,
        ];

        return HttpResponse.json(purchases[0], { status: 201 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<PurchasesPage />);

    expect(await screen.findByText("Groceries")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Title"), "Bus Pass");
    await user.type(screen.getAllByLabelText("Category")[1], "TRANSPORT");
    await user.clear(screen.getByLabelText("Amount"));
    await user.type(screen.getByLabelText("Amount"), "15");
    await user.selectOptions(screen.getByLabelText("Currency"), "EUR");
    await user.type(screen.getByLabelText("Store"), "Metro");
    await user.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("Bus Pass")).toBeInTheDocument();
    expect(screen.getByText(/€15.00/i)).toBeInTheDocument();
    expect(screen.getByText(/Purchase saved/i)).toBeInTheDocument();
    expect(lastRequest).toMatchObject({
      title: "Bus Pass",
      category: "TRANSPORT",
      amount: 15,
      currency: "EUR",
    });
    expect(lastRequest?.items).toBeUndefined();
  });

  it("opens calculator and applies the result to amount", async () => {
    server.use(http.get("/api/purchases", () => HttpResponse.json([])));

    const user = userEvent.setup();
    renderWithProviders(<PurchasesPage />);

    await screen.findByText("No product items added yet.");
    await user.click(screen.getByRole("button", { name: "Calculator" }));
    await user.click(screen.getByRole("button", { name: "7" }));
    await user.click(screen.getByRole("button", { name: "+" }));
    await user.click(screen.getByRole("button", { name: "5" }));
    await user.click(screen.getByRole("button", { name: "=" }));
    await user.click(screen.getByRole("button", { name: "Apply to amount" }));

    expect(screen.getByLabelText("Amount")).toHaveValue(12);
  });

  it("creates a purchase with multiple items and calculated total", async () => {
    let purchases = [] as Array<Record<string, unknown>>;
    let lastRequest: Record<string, unknown> | null = null;

    server.use(
      http.get("/api/purchases", () => HttpResponse.json(purchases)),
      http.post("/api/purchases", async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        lastRequest = body;
        purchases = [
          {
            id: 3,
            title: body.title,
            category: body.category,
            amount: body.amount,
            currency: body.currency,
            purchaseDate: body.purchaseDate,
            storeName: body.storeName ?? null,
            comment: body.comment ?? null,
            createdAt: "2026-03-31T11:00:00Z",
            items: body.items,
          },
        ];

        return HttpResponse.json(purchases[0], { status: 201 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<PurchasesPage />);

    await user.type(screen.getByLabelText("Title"), "Saturday basket");
    await user.type(screen.getAllByLabelText("Category")[1], "FOOD");
    await user.selectOptions(screen.getByLabelText("Currency"), "UAH");
    await user.click(screen.getByRole("button", { name: "Add item" }));
    await user.click(screen.getByRole("button", { name: "Add item" }));

    await user.type(screen.getByLabelText("Item 1"), "Milk");
    await user.type(document.querySelector('input[name="items.0.quantity"]') as HTMLInputElement, "2");
    await user.type(document.querySelector('input[name="items.0.unit"]') as HTMLInputElement, "pcs");
    await user.type(document.querySelector('input[name="items.0.unitPrice"]') as HTMLInputElement, "21");

    await user.type(screen.getByLabelText("Item 2"), "Bread");
    await user.type(document.querySelector('input[name="items.1.lineTotal"]') as HTMLInputElement, "18.5");

    await waitFor(() => {
      expect(document.querySelector('input[name="amount"]')).toHaveValue(60.5);
    });

    await user.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("Saturday basket")).toBeInTheDocument();
    expect(screen.getByText("Milk")).toBeInTheDocument();
    expect(screen.getByText("Bread")).toBeInTheDocument();
    expect(lastRequest).toMatchObject({
      amount: 60.5,
      currency: "UAH",
    });
    expect(lastRequest?.items).toEqual([
      {
        title: "Milk",
        quantity: 2,
        unit: "pcs",
        unitPrice: 21,
        lineTotal: 42,
      },
      {
        title: "Bread",
        lineTotal: 18.5,
      },
    ]);
  });

  it("filters purchases by category", async () => {
    server.use(
      http.get("/api/purchases", ({ request }) => {
        const url = new URL(request.url);
        const category = url.searchParams.get("category");

        if (category === "TRANSPORT") {
          return HttpResponse.json([
            {
              id: 3,
              title: "Metro Ticket",
              category: "TRANSPORT",
              amount: 3.5,
              currency: "UAH",
              purchaseDate: "2026-03-31",
              storeName: "Metro",
              comment: null,
              createdAt: "2026-03-31T09:00:00Z",
              items: [],
            },
          ]);
        }

        return HttpResponse.json([
          {
            id: 1,
            title: "Groceries",
            category: "FOOD",
            amount: 42.75,
            currency: "UAH",
            purchaseDate: "2026-03-30",
            storeName: "Fresh Market",
            comment: null,
            createdAt: "2026-03-31T09:00:00Z",
            items: [],
          },
        ]);
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<PurchasesPage />);

    expect(await screen.findByText("Groceries")).toBeInTheDocument();

    await user.clear(screen.getAllByLabelText("Category")[0]);
    await user.type(screen.getAllByLabelText("Category")[0], "TRANSPORT");

    await waitFor(() => {
      expect(screen.getByText("Metro Ticket")).toBeInTheDocument();
    });
  });
});
