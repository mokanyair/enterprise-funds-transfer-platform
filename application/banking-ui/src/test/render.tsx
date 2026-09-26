import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { DevAuthProvider } from "../auth/DevAuthProvider";

export const CUSTOMER_1 = { name: "Jordan Reyes", subject: "customer-1" };

/** Renders `element` at `path` inside a signed-in dev session with a fresh query cache. */
export function renderRoute(element: ReactElement, { path = "/", url = path }: { path?: string; url?: string } = {}) {
  sessionStorage.setItem("novabank.dev-session", JSON.stringify(CUSTOMER_1));
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <DevAuthProvider>
          <Routes>
            <Route path={path} element={element} />
            <Route path="/transfers/:transferId" element={<p>Transfer detail page</p>} />
          </Routes>
        </DevAuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
