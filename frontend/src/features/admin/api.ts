import { apiFetch } from "../../shared/api/http";
import type { AdminOverviewResponse } from "../../shared/api/types";

export function getAdminOverview() {
  return apiFetch<AdminOverviewResponse>("/api/admin/overview");
}
