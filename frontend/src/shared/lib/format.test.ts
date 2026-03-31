import { describe, expect, it } from "vitest";
import { formatCurrency } from "./format";

describe("formatCurrency", () => {
  it("formats supported currencies", () => {
    expect(formatCurrency(10, "en", "USD")).toContain("$");
    expect(formatCurrency(10, "en", "EUR")).toContain("€");
    expect(formatCurrency(10, "en", "UAH")).toMatch(/UAH|₴/i);
    expect(formatCurrency(10, "en", "RUB")).toMatch(/RUB|₽/i);
  });
});
