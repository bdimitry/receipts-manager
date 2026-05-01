import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { getCurrentUser } from "../../features/user/api";
import { useAuth } from "../../shared/auth/AuthContext";
import { useI18n } from "../../shared/i18n/I18nContext";
import { dispatchCalculatorResult, OPEN_CALCULATOR_EVENT } from "../../shared/lib/calculator-events";
import { Button } from "../../shared/ui/Button";
import { CalculatorModal } from "../../shared/ui/CalculatorModal";
import { LanguageDropdown } from "../../shared/ui/LanguageDropdown";
import { LoadingState } from "../../shared/ui/LoadingState";
import { ThemeToggle } from "../../shared/ui/ThemeToggle";

const navItems = [
  { to: "/", key: "overview" as const, icon: "H" },
  { to: "/purchases", key: "purchases" as const, icon: "$" },
  { to: "/receipts", key: "receipts" as const, icon: "#" },
  { to: "/reports", key: "reports" as const, icon: "R" },
];

export function AppLayout() {
  const { t } = useI18n();
  const { logout } = useAuth();
  const location = useLocation();
  const [calculatorOpen, setCalculatorOpen] = useState(false);
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
    "/admin": t("admin"),
  };
  const currentTitle = Object.entries(routeTitleMap).find(([path]) =>
    path === "/"
      ? location.pathname === path
      : location.pathname === path || location.pathname.startsWith(`${path}/`),
  )?.[1] ?? t("overview");

  useEffect(() => {
    const handleOpen = () => setCalculatorOpen(true);
    window.addEventListener(OPEN_CALCULATOR_EVENT, handleOpen);
    return () => window.removeEventListener(OPEN_CALCULATOR_EVENT, handleOpen);
  }, []);

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
              <span aria-hidden="true" className="sidebar__nav-icon">{item.icon}</span>
              <span>{t(item.key)}</span>
            </NavLink>
          ))}
          <button
            className={`sidebar__nav-link sidebar__nav-link--button ${calculatorOpen ? "sidebar__nav-link--active" : ""}`.trim()}
            data-testid="sidebar-calculator-toggle"
            onClick={() => setCalculatorOpen((current) => !current)}
            type="button"
          >
            <span aria-hidden="true" className="sidebar__nav-icon">=</span>
            <span>{t("calculator")}</span>
          </button>
          <NavLink
            className={({ isActive }) =>
              `sidebar__nav-link ${isActive ? "sidebar__nav-link--active" : ""}`.trim()
            }
            to="/profile"
          >
            <span aria-hidden="true" className="sidebar__nav-icon">U</span>
            <span>{t("profile")}</span>
          </NavLink>
          {currentUser?.admin ? (
            <NavLink
              className={({ isActive }) =>
                `sidebar__nav-link ${isActive ? "sidebar__nav-link--active" : ""}`.trim()
              }
              to="/admin"
            >
              <span aria-hidden="true" className="sidebar__nav-icon">A</span>
              <span>{t("admin")}</span>
            </NavLink>
          ) : null}
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
      <CalculatorModal
        title={t("calculator")}
        closeLabel={t("close")}
        clearLabel={t("clear")}
        applyLabel={t("applyToAmount")}
        expressionLabel={t("expression")}
        open={calculatorOpen}
        onApply={(value) => {
          dispatchCalculatorResult(value);
          setCalculatorOpen(false);
        }}
        onClose={() => setCalculatorOpen(false)}
        resultLabel={t("lineTotal")}
      />
    </div>
  );
}
