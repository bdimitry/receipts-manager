import { apiFetch } from "../../shared/api/http";
import type {
  AuthResponse,
  CurrentUserResponse,
} from "../../shared/api/types";

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export function registerUser(request: RegisterRequest) {
  return apiFetch<CurrentUserResponse>("/api/auth/register", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}

export function loginUser(request: LoginRequest) {
  return apiFetch<AuthResponse>("/api/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}
