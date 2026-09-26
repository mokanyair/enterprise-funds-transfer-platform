import { useEffect, useMemo, useState, type ReactNode } from "react";
import { AuthContext } from "./AuthContext";
import { setTokenSource } from "../api/http";
import type { AuthState, AuthUser } from "./types";
import { clearPendingTransfer } from "../features/transfers/pendingTransfer";

const STORAGE_KEY = "novabank.dev-session";

/**
 * Local stand-in for Keycloak: no password form, no real token. The "token" is a plain
 * `dev:<subject>` string that only the MSW mock handlers understand — it is never valid
 * against the real backend, which verifies a signed JWT. Use VITE_AUTH_MODE=keycloak for that.
 */
export function DevAuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  });

  useEffect(() => {
    setTokenSource({
      getToken: async () => (user ? `dev:${user.subject}` : null),
      forceRefresh: async () => false, // dev tokens never expire
      onUnauthenticated: () => setUser(null),
    });
  }, [user]);

  const value = useMemo<AuthState>(
    () => ({
      status: user ? "authenticated" : "unauthenticated",
      user,
      loginAs: (nextUser) => {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(nextUser));
        setUser(nextUser);
      },
      logout: () => {
        sessionStorage.removeItem(STORAGE_KEY);
        clearPendingTransfer();
        setUser(null);
      },
    }),
    [user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
