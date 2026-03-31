import { useQuery } from "@tanstack/react-query";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { getCurrentUser } from "../../features/user/api";
import { useAuth } from "../../shared/auth/AuthContext";
import { useI18n } from "../../shared/i18n/I18nContext";
import { Button } from "../../shared/ui/Button";
import { LanguageDropdown } from "../../shared/ui/LanguageDropdown";
import { LoadingState } from "../../shared/ui/LoadingState";
import { ThemeToggle } from "../../shared/ui/ThemeToggle";

const navItems = [
  { to: "/", key: "overview" as const },
  { to: "/purchases", key: "purchases" as const },
  { to: "/receipts", key: "receipts" as const },
  { to: "/reports", key: "reports" as const },
  { to: "/profile", key: "profile" as const },
];

export function AppLayout() {
  const { t } = useI18n();
  const { logout } = useAuth();
  const location = useLocation();
  const { data: currentUser, isLoading } = useQuery({
    queryKey: ["current-user"],
    queryFn: getCurrentUser,
  });

  const routeTitleMap: Record<string, string> = {
    "/": t("overview"),
    "/purchases": t("purchases"),
    "/receipts": t("receipts"),
    "/reports": t("reports"),
    "/profile": t("profile"),
  };
  const currentTitle = Object.entries(routeTitleMap).find(([path]) =>
    path === "/"
      ? location.pathname === path
      : location.pathname === path || location.pathname.startsWith(`${path}/`),
  )?.[1] ?? t("overview");

  return (
    <div className="app-shell">
      <aside className="sidebar" data-testid="sidebar">
        <div className="sidebar__brand">
          <h2>{t("appName")}</h2>
          <p>{t("appTagline")}</p>
        </div>
        <nav className="sidebar__nav" aria-label="Primary">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              className={({ isActive }) =>
                `sidebar__nav-link ${isActive ? "sidebar__nav-link--active" : ""}`.trim()
              }
              end={item.to === "/"}
              to={item.to}
            >
              {t(item.key)}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar__footer">
          {isLoading ? (
            <LoadingState label={t("loading")} />
          ) : (
            <>
              <div className="sidebar__user">
                <span className="sidebar__user-label">{currentUser?.email}</span>
                <small>{currentTitle}</small>
              </div>
              <Button variant="ghost" onClick={logout}>
                {t("logout")}
              </Button>
            </>
          )}
        </div>
      </aside>
      <div className="content-shell">
        <header className="topbar">
          <div>
            <p className="topbar__eyebrow">{t("appName")}</p>
            <h1>{currentTitle}</h1>
          </div>
          <div className="topbar__actions">
            <ThemeToggle />
            <LanguageDropdown />
          </div>
        </header>
        <main className="content" data-testid="content-area">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
