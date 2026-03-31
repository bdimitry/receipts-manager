import { clearStoredToken, getStoredToken } from "../auth/session";
import type { ErrorResponse } from "./types";

let unauthorizedHandler: (() => void) | null = null;

export class ApiError extends Error {
  status: number;
  data?: ErrorResponse;

  constructor(status: number, message: string, data?: ErrorResponse) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.data = data;
  }
}

export function setUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = getStoredToken();

  if (!headers.has("Accept")) {
    headers.set("Accept", "application/json");
  }

  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(path, {
    ...init,
    headers,
  });

  if (!response.ok) {
    let errorResponse: ErrorResponse | undefined;

    try {
      errorResponse = (await response.json()) as ErrorResponse;
    } catch {
      errorResponse = undefined;
    }

    if (response.status === 401) {
      clearStoredToken();
      unauthorizedHandler?.();
    }

    throw new ApiError(
      response.status,
      errorResponse?.message ?? response.statusText,
      errorResponse,
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.includes("application/json")) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
