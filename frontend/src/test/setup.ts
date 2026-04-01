import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { server } from "./server";

class ResizeObserverMock {
  observe() {}

  unobserve() {}

  disconnect() {}
}

const NativeRequest = globalThis.Request;

class CompatibleRequest extends NativeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    const nextInit = init?.signal ? { ...init, signal: undefined } : init;

    super(input, nextInit);
  }
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  Object.defineProperty(window, "AbortController", {
    writable: true,
    value: globalThis.AbortController,
  });
  Object.defineProperty(window, "AbortSignal", {
    writable: true,
    value: globalThis.AbortSignal,
  });
  Object.defineProperty(window, "fetch", {
    writable: true,
    value: globalThis.fetch,
  });
  Object.defineProperty(window, "Request", {
    writable: true,
    value: CompatibleRequest,
  });
  Object.defineProperty(window, "Response", {
    writable: true,
    value: globalThis.Response,
  });
  Object.defineProperty(window, "Headers", {
    writable: true,
    value: globalThis.Headers,
  });
  Object.defineProperty(globalThis, "Request", {
    writable: true,
    value: CompatibleRequest,
  });
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
  Object.defineProperty(window, "ResizeObserver", {
    writable: true,
    value: ResizeObserverMock,
  });
  Object.defineProperty(window, "open", {
    writable: true,
    value: vi.fn(),
  });
  Object.defineProperty(window.URL, "createObjectURL", {
    writable: true,
    value: vi.fn(() => "blob:mock-report"),
  });
  Object.defineProperty(window.URL, "revokeObjectURL", {
    writable: true,
    value: vi.fn(),
  });
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    writable: true,
    value: vi.fn(),
  });
});

afterEach(() => {
  server.resetHandlers();
  window.localStorage.clear();
  vi.clearAllMocks();
});

afterAll(() => {
  server.close();
});
