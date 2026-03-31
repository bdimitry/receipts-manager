export function evaluateCalculatorExpression(expression: string): number {
  const sanitizedExpression = expression.replace(/\s+/g, "");

  if (!sanitizedExpression) {
    throw new Error("Expression is empty");
  }

  if (!/^[0-9+\-*/().]+$/.test(sanitizedExpression)) {
    throw new Error("Expression contains invalid characters");
  }

  const result = Function(`"use strict"; return (${sanitizedExpression});`)();

  if (typeof result !== "number" || !Number.isFinite(result)) {
    throw new Error("Expression could not be evaluated");
  }

  return Number(result.toFixed(2));
}
