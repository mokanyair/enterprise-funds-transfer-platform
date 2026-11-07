import { useEffect, useMemo, useState, type ReactNode } from "react";
import Keycloak from "keycloak-js";
import { AuthContext } from "./AuthContext";
import { setTokenSource } from "../api/http";
import { env } from "../lib/env";
import { createKeycloakTokenSource, MIN_TOKEN_VALIDITY_SECONDS } from "./keycloakTokenSource";
import type { AuthState, AuthUser } from "./types";
import { clearPendingTransfer } from "../features/transfers/pendingTransfer";

let session: { keycloak: Keycloak; ready: Promise<boolean> } | null = null;

/**
 * One Keycloak instance and one init() per page load. React StrictMode runs effects twice in
 * dev; two instances would both try to redeem the same authorization code on the redirect back.
 */
function keycloakSession() {
  if (session) return session;
  const keycloak = new Keycloak({
    url: env.keycloak.url,
    realm: env.keycloak.realm,
    clientId: env.keycloak.clientId,
  });
  setTokenSource(createKeycloakTokenSource(keycloak));

  // Proactive refresh so an idle tab still holds a valid token; the token source also refreshes
  // before every request. A failed refresh means the Keycloak session ended: sign in again.
  keycloak.onTokenExpired = () => {
    keycloak.updateToken(MIN_TOKEN_VALIDITY_SECONDS).catch(() => keycloak.login());
  };
  keycloak.onAuthRefreshError = () => keycloak.login();

  const ready = keycloak.init({ onLoad: "login-required", pkceMethod: "S256", checkLoginIframe: false });
  session = { keycloak, ready };
  return session;
}

/**
 * Authorization Code + PKCE against the Keycloak realm, no client secret in the SPA
 * (Part II, Section 18 of the design guide).
 */
export function KeycloakAuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthState["status"]>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);

  useEffect(() => {
    const { keycloak, ready } = keycloakSession();
    ready
      .then((authenticated) => {
        if (!authenticated) {
          setStatus("unauthenticated");
          return;
        }
        setUser({
          name:
            (keycloak.tokenParsed?.name as string | undefined) ??
            (keycloak.tokenParsed?.preferred_username as string | undefined) ??
            "Customer",
          subject: keycloak.tokenParsed?.sub ?? "",
        });
        setStatus("authenticated");
      })
      .catch(() => setStatus("unauthenticated"));
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      status,
      user,
      logout: () => {
        clearPendingTransfer();
        void keycloakSession().keycloak.logout({ redirectUri: window.location.origin });
      },
    }),
    [status, user],
  );

  if (status === "loading") {
    return <div className="centered-page">Signing you in…</div>;
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
