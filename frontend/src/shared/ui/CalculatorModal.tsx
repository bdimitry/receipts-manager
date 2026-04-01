import { useEffect, useMemo, useState } from "react";
import { evaluateCalculatorExpression } from "../lib/calculator";
import { Button } from "./Button";

interface CalculatorModalProps {
  title: string;
  closeLabel: string;
  clearLabel: string;
  applyLabel: string;
  expressionLabel: string;
  resultLabel: string;
  open: boolean;
  onApply?: (value: number) => void;
  onClose: () => void;
}

const keypad = [
  "7",
  "8",
  "9",
  "/",
  "4",
  "5",
  "6",
  "*",
  "1",
  "2",
  "3",
  "-",
  "0",
  ".",
  "(",
  ")",
  "C",
  "=",
  "+",
];

export function CalculatorModal({
  title,
  closeLabel,
  clearLabel,
  applyLabel,
  expressionLabel,
  resultLabel,
  open,
  onApply,
  onClose,
}: CalculatorModalProps) {
  const [expression, setExpression] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [onClose, open]);

  const result = useMemo(() => {
    if (!expression.trim()) {
      return null;
    }

    try {
      return evaluateCalculatorExpression(expression);
    } catch {
      return null;
    }
  }, [expression]);

  if (!open) {
    return null;
  }

  const appendToken = (token: string) => {
    if (token === "C") {
      setExpression("");
      setError(null);
      return;
    }

    if (token === "=") {
      if (result === null) {
        setError("Invalid expression");
        return;
      }

      setExpression(String(result));
      setError(null);
      return;
    }

    setExpression((current) => `${current}${token}`);
    setError(null);
  };

  return (
    <aside
      aria-modal="false"
      className="calculator-window"
      data-testid="calculator-window"
      role="dialog"
    >
      <div className="calculator-window__header">
        <div>
          <h3>{title}</h3>
          <p>{expressionLabel}</p>
        </div>
        <Button variant="ghost" onClick={onClose}>
          {closeLabel}
        </Button>
      </div>
      <label className="field">
        <span>{expressionLabel}</span>
        <input
          aria-label={expressionLabel}
          value={expression}
          onChange={(event) => {
            setExpression(event.target.value);
            setError(null);
          }}
        />
      </label>
      <div className="calculator-result">
        <span>{resultLabel}</span>
        <strong>{result !== null ? result : "--"}</strong>
      </div>
      {error ? <p className="form-error">{error}</p> : null}
      <div className="calculator-grid">
        {keypad.map((token) => (
          <button
            key={token}
            className={`calculator-key ${token === "=" ? "calculator-key--accent" : ""}`.trim()}
            type="button"
            onClick={() => appendToken(token)}
          >
            {token === "C" ? clearLabel : token}
          </button>
        ))}
      </div>
      <div className="calculator-window__actions">
        <Button variant="ghost" onClick={() => appendToken("C")}>
          {clearLabel}
        </Button>
        {onApply ? (
          <Button disabled={result === null} onClick={() => result !== null && onApply(result)}>
            {applyLabel}
          </Button>
        ) : null}
      </div>
    </aside>
  );
}
