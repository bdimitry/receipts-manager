export const OPEN_CALCULATOR_EVENT = "hb:calculator-open";
export const APPLY_CALCULATOR_RESULT_EVENT = "hb:calculator-apply";

export function requestCalculatorOpen() {
  window.dispatchEvent(new CustomEvent(OPEN_CALCULATOR_EVENT));
}

export function dispatchCalculatorResult(value: number) {
  window.dispatchEvent(
    new CustomEvent<number>(APPLY_CALCULATOR_RESULT_EVENT, {
      detail: value,
    }),
  );
}
