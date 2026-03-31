import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import {
  createMemoryRouter,
  MemoryRouter,
  RouterProvider,
  type RouteObject,
} from "react-router-dom";
import { AuthProvider } from "../shared/auth/AuthContext";
import type { LanguageCode } from "../shared/api/types";
import { setStoredToken } from "../shared/auth/session";
import { I18nProvider } from "../shared/i18n/I18nContext";
import { ThemeProvider } from "../shared/theme/ThemeContext";

function Providers({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
      },
    },
  });

  return (
    <ThemeProvider>
      <I18nProvider>
        <AuthProvider>
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        </AuthProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

export function renderWithProviders(ui: ReactElement, token?: string, language: LanguageCode = "en") {
  window.localStorage.setItem("hb.language", JSON.stringify(language));
  window.localStorage.setItem("hb.theme", "\"light\"");
  if (token) {
    setStoredToken(token);
  } else {
    window.localStorage.removeItem("hb.jwt");
  }

  return render(<Providers><MemoryRouter>{ui}</MemoryRouter></Providers>);
}

export function renderWithRouter(
  routes: RouteObject[],
  initialEntries: string[],
  token?: string,
  language: LanguageCode = "en",
) {
  window.localStorage.setItem("hb.language", JSON.stringify(language));
  window.localStorage.setItem("hb.theme", "\"light\"");
  if (token) {
    setStoredToken(token);
  } else {
    window.localStorage.removeItem("hb.jwt");
  }

  const router = createMemoryRouter(routes, {
    initialEntries,
  });

  return {
    router,
    ...render(
      <Providers>
        <RouterProvider router={router} />
      </Providers>,
    ),
  };
}
